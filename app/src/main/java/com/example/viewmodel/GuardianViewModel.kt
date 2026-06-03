package com.example.viewmodel

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.database.GuardianDatabase
import com.example.data.local.entity.FriendEntity
import com.example.data.local.entity.StatusLogEntity
import com.example.data.local.entity.UserSessionEntity
import com.example.data.repository.GuardianRepository
import com.example.service.BlockOverlayService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GuardianViewModel(context: Context) : ViewModel() {

    private val db = GuardianDatabase.getDatabase(context.applicationContext)
    private val repository = GuardianRepository(db.guardianDao())

    // UI state streams observed from DB (MVVM)
    val userSession: StateFlow<UserSessionEntity?> = repository.userSession
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val allLogs: StateFlow<List<StatusLogEntity>> = repository.allLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Setup Process state management (Step 1: App, Step 2: Time, Step 3: Mode + Shame)
    private val _setupStep = MutableStateFlow(1)
    val setupStep: StateFlow<Int> = _setupStep.asStateFlow()

    // Temporary variables holding target creation parameters during setup steps
    private val _targetAppName = MutableStateFlow("Instagram")
    val targetAppName: StateFlow<String> = _targetAppName.asStateFlow()

    private val _targetAppPackage = MutableStateFlow("com.instagram.android")
    val targetAppPackage: StateFlow<String> = _targetAppPackage.asStateFlow()

    private val _dailyLimitMinutes = MutableStateFlow(60)
    val dailyLimitMinutes: StateFlow<Int> = _dailyLimitMinutes.asStateFlow()

    private val _isObserverMode = MutableStateFlow(false)
    val isObserverMode: StateFlow<Boolean> = _isObserverMode.asStateFlow()

    private val _shameMessage = MutableStateFlow("Üzgünüm, bugün irademe yenildim ve Instagram'da sörf yaparken Gardiyan'a yakalandım. Bu mesaj utancımın kanıtıdır.")
    val shameMessage: StateFlow<String> = _shameMessage.asStateFlow()

    private val _observerContactName = MutableStateFlow("Ahmet (Gözetmen)")
    val observerContactName: StateFlow<String> = _observerContactName.asStateFlow()

    // Service running state observed locally
    private val _isMonitoringActive = MutableStateFlow(BlockOverlayService.isServiceRunning)
    val isMonitoringActive: StateFlow<Boolean> = _isMonitoringActive.asStateFlow()

    init {
        viewModelScope.launch {
            repository.insertDefaultSessionIfMissing()
        }
    }

    fun setStep(step: Int) {
        _setupStep.value = step
    }

    fun updateTargetApp(name: String, pkg: String) {
        _targetAppName.value = name
        _targetAppPackage.value = pkg
    }

    fun updateDailyLimit(minutes: Int) {
        _dailyLimitMinutes.value = minutes
    }

    fun updatePenaltyMode(isObserver: Boolean) {
        _isObserverMode.value = isObserver
    }

    fun updateShameMessage(msg: String) {
        _shameMessage.value = msg
    }

    fun updateObserverContact(name: String) {
        _observerContactName.value = name
    }

    /**
     * Start the objective and commit session configuration to DB
     */
    fun startNewTarget(context: Context) {
        viewModelScope.launch {
            val session = repository.getSessionSync() ?: UserSessionEntity()
            
            // Build unique observer invite link for simulation V2
            val inviteLink = "https://gardiyan.app/invite/user_${System.currentTimeMillis()}"

            val updatedSession = session.copy(
                isActive = true,
                targetAppName = _targetAppName.value,
                targetAppPackage = _targetAppPackage.value,
                dailyLimitMinutes = _dailyLimitMinutes.value,
                remainingMinutesToday = _dailyLimitMinutes.value,
                remainingSecondsToday = _dailyLimitMinutes.value * 60,
                isObserverMode = _isObserverMode.value,
                shameMessage = _shameMessage.value,
                observerContactName = _observerContactName.value,
                observerInviteLink = inviteLink,
                isObserverConfirmed = !_isObserverMode.value
            )

            repository.saveSession(updatedSession)
            
            repository.insertLog(
                eventType = "TARGET_STARTED",
                appName = _targetAppName.value,
                details = "Kilitli hedef başlatıldı: ${if(_isObserverMode.value) "Gözetmen" else "Gardiyan"} Modu, Limit: ${_dailyLimitMinutes.value} Dk"
            )

            // Start foreground interceptor service
            toggleMonitoringService(context, true)
            _setupStep.value = 1
        }
    }

    /**
     * Servis çöktüyse ve aktif oturum varsa yeniden başlatır.
     * MainActivity.onResume() tarafından çağrılır.
     */
    fun restartServiceIfNeeded(context: Context) {
        viewModelScope.launch {
            val session = repository.getSessionSync()
            if (session != null && session.isActive && !BlockOverlayService.isServiceRunning) {
                toggleMonitoringService(context, true)
            }
        }
    }


    /**
     * Controls foreground application scanning and overlay trigger service
     */
    fun toggleMonitoringService(context: Context, enable: Boolean) {
        val serviceIntent = Intent(context, BlockOverlayService::class.java)
        if (enable) {
            // Android 8+ (API 26+) için startForegroundService gereklidir.
            // startService() çağrısı arka plandan yapıldığında sistem tarafından
            // reddedilir ve servis hiç başlamaz.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            _isMonitoringActive.value = true
        } else {
            context.stopService(serviceIntent)
            _isMonitoringActive.value = false
        }
    }

    /**
     * Clear and reset target locks cleanly safely
     */
    fun resetTargetSession() {
        viewModelScope.launch {
            val session = repository.getSessionSync() ?: return@launch
            val updated = session.copy(
                isActive = false,
                targetAppPackage = "",
                targetAppName = "",
                remainingMinutesToday = 60
            )
            repository.saveSession(updated)
            repository.insertLog(
                eventType = "RESET",
                appName = "",
                details = "Mevcut hedef kilit ayarı temizlendi."
            )
        }
    }

    /**
     * Clears local history logs
     */
    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    /**
     * Helper checks if Usage Access settings exist and is enabled via AppOpsManager
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageStatsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallbackIntent)
        }
    }

    /**
     * Dynamically retrieves list of user-installed apps and populates procrastination targets
     */
    fun getInstalledApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val list = mutableListOf<Pair<String, String>>()
        
        val popularPackages = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.google.android.youtube",
            "com.twitter.android",
            "com.facebook.katana",
            "com.reddit.frontpage",
            "com.netflix.mediaclient",
            "com.valvesoftware.android.steam.community"
        )
        
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherPackageNames = try {
            pm.queryIntentActivities(launcherIntent, 0).map {
                it.activityInfo.packageName
            }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        
        try {
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in apps) {
                val pkgName = appInfo.packageName
                if (pkgName == context.packageName) continue
                
                val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isLauncher = launcherPackageNames.isEmpty() || launcherPackageNames.contains(pkgName)
                val isPopular = popularPackages.contains(pkgName)
                
                if (isPopular || (!isSystemApp && isLauncher)) {
                    val label = appInfo.loadLabel(pm).toString()
                    list.add(Pair(label, pkgName))
                }
            }
        } catch (e: Exception) {
            // Fallback inside outer block
        }
        
        val popularList = list.filter { popularPackages.contains(it.second) }.sortedBy { it.first.uppercase() }
        val otherList = list.filter { !popularPackages.contains(it.second) }.sortedBy { it.first.uppercase() }
        
        return popularList + otherList
    }
}
