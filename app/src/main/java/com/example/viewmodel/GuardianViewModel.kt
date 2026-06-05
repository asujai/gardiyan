package com.example.viewmodel

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.database.GuardianDatabase
import com.example.data.local.entity.RestrictedAppEntity
import com.example.data.local.entity.StatusLogEntity
import com.example.data.local.entity.UserSessionEntity
import com.example.data.repository.GuardianRepository
import com.example.service.AppBlockAccessibilityService
import com.example.service.BlockOverlayService
import com.example.service.KeepAliveScheduler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GuardianViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val db = GuardianDatabase.getDatabase(appContext)
    private val repository = GuardianRepository(db.guardianDao())

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

    val restrictedApps: StateFlow<List<RestrictedAppEntity>> = repository.restrictedApps
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _setupStep = MutableStateFlow(1)
    val setupStep: StateFlow<Int> = _setupStep.asStateFlow()

    private val _shameMessage = MutableStateFlow("\u00DCzg\u00FCn\u00FCm, bug\u00FCn irademe yenildim ve Instagram'da s\u00F6rf yaparken Gardiyan'a yakaland\u0131m. Bu mesaj utanc\u0131m\u0131n kan\u0131t\u0131d\u0131r.")
    val shameMessage: StateFlow<String> = _shameMessage.asStateFlow()

    private val _isMonitoringActive = MutableStateFlow(BlockOverlayService.isServiceRunning.get())
    val isMonitoringActive: StateFlow<Boolean> = _isMonitoringActive.asStateFlow()

    init {
        viewModelScope.launch {
            repository.insertDefaultSessionIfMissing()
        }
    }

    fun setStep(step: Int) {
        _setupStep.value = step
    }

    fun updateShameMessage(msg: String) {
        _shameMessage.value = msg
    }

    // ============================
    // Multi-app restricted list
    // ============================

    /**
     * Kullanıcının kurduğu yeni bir kısıtlama. Aynı paket varsa limit
     * güncellenir, yoksa yeni satır oluşturulur.
     * isActive = true HER ZAMAN — kolay kapatma yok.
     */
    fun addRestrictedApp(packageName: String, appName: String, dailyLimitMinutes: Int, activeDays: String = "Pzt,Sal,Çar,Per,Cum,Cmt,Paz") {
        viewModelScope.launch {
            val id = repository.upsertRestrictedApp(packageName, appName, dailyLimitMinutes, activeDays)
            repository.insertLog(
                eventType = "RESTRICTION_ADDED",
                appName = appName,
                details = "$appName kısıtlama listesine eklendi: günde ${dailyLimitMinutes} dakika"
            )
            // Eski `targetAppPackage` alanı için de set et (geri uyumluluk)
            markSessionHavingRestrictions()
            // Servis canlıysa veya değilse restart
            ensureMonitoringRunning()
        }
    }

    /**
     * Hızlı test modu: seçilen uygulamayı belirli bir saniye sonra
     * kilitlenecek şekilde listeye ekler (örn. 10 saniye). Mevcut servisi
     * tetikler.
     */
    fun startQuickTest(context: Context, packageName: String, appName: String, testSeconds: Int = 10, activeDays: String = "Pzt,Sal,Çar,Per,Cum,Cmt,Paz") {
        viewModelScope.launch {
            repository.insertQuickTestApp(packageName, appName, testSeconds, activeDays)
            repository.insertLog(
                eventType = "QUICK_TEST_STARTED",
                appName = appName,
                details = "$appName için hızlı test başlatıldı: ${testSeconds} saniye sonra kilit"
            )
            markSessionHavingRestrictions()
            // A11y + overlay servisini başlat (henüz yoksa)
            if (!BlockOverlayService.isServiceRunning.get()) {
                startMonitoringService(appContext)
            }
        }
    }

    fun removeRestrictedApp(id: Long) {
        viewModelScope.launch {
            val app = repository.getRestrictedAppByIdSync(id)
            repository.removeRestrictedApp(id)
            if (app != null) {
                repository.insertLog(
                    eventType = "RESTRICTION_REMOVED",
                    appName = app.appName,
                    details = "${app.appName} kısıtlama listesinden çıkarıldı"
                )
            }
        }
    }

    fun clearAllRestrictedApps() {
        viewModelScope.launch {
            repository.clearAllRestrictedApps()
            repository.insertLog(
                eventType = "RESTRICTIONS_CLEARED",
                appName = "",
                details = "Tüm kısıtlamalar silindi"
            )
        }
    }

    fun resetRestrictedApp(id: Long) {
        viewModelScope.launch {
            val app = repository.getRestrictedAppByIdSync(id) ?: return@launch
            repository.resetRestrictedApp(id)
            repository.insertLog(
                eventType = "RESTRICTION_RESET",
                appName = app.appName,
                details = "${app.appName} için günlük sayaç sıfırlandı"
            )
        }
    }

    /**
     * Birden fazla hedef uygulama varsa session.isActive=true olur.
     * Bu, A11y service'in "hiç aktif kısıtlama yok" durumunu
     * yanlış değerlendirmesini engeller.
     */
    private suspend fun markSessionHavingRestrictions() {
        val active = repository.getActiveRestrictedAppsSync()
        val session = repository.getSessionSync() ?: UserSessionEntity()
        if (active.isNotEmpty() && !session.isActive) {
            repository.saveSession(session.copy(isActive = true))
        }
    }

    private fun ensureMonitoringRunning() {
        viewModelScope.launch {
            if (!BlockOverlayService.isServiceRunning.get()) {
                startMonitoringService(appContext)
            }
        }
    }

    fun restartServiceIfNeeded(context: Context) {
        viewModelScope.launch {
            val active = repository.getActiveRestrictedAppsSync()
            if (active.isNotEmpty() && !BlockOverlayService.isServiceRunning.get()) {
                startMonitoringService(context)
            }
        }
    }

    /**
     * Servisi başlat. Kolay kapatma yok — dışarıdan false geçilemez.
     * Servis durdurmak SADECE cancelAllWithFiveSecondHold() ile mümkündür.
     */
    private fun startMonitoringService(context: Context) {
        val serviceIntent = Intent(context, BlockOverlayService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        _isMonitoringActive.value = true
    }

    /**
     * Eski API uyumluluğu için korundu ama enable=false durumunda
     * doğrudan servisi durdurmaz — sadece 5sn hold üzerinden çalışır.
     *
     * enable=true: servisi başlatır
     * enable=false: NO-OP (kolay kapatma engellenmiştir)
     */
    fun toggleMonitoringService(context: Context, enable: Boolean) {
        if (enable) {
            startMonitoringService(context)
        }
        // enable = false → NO-OP
        // Servis durdurmak YALNIZCA cancelAllWithFiveSecondHold() ile mümkündür
    }

    /**
     * 5 saniye basılı tutma sonrası çağrılır. Tüm aktif kısıtlamaları
     * kaldırır, level'ı 1'e çeker, kırmızı rozet ekler, overlay'i
     * temizler ve servisi durdurur.
     *
     * TEK YASAL DURDURMA YÖNTEMİ BU FONKSİYONDUR.
     */
    fun cancelAllWithFiveSecondHold() {
        viewModelScope.launch {
            try {
                repository.cancelAllActiveTargets()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Servisi durdur — sadece burada yapılır
            if (BlockOverlayService.isServiceRunning.get()) {
                val serviceIntent = Intent(appContext, BlockOverlayService::class.java)
                appContext.stopService(serviceIntent)
                _isMonitoringActive.value = false
            }
            // Overlay açıksa kapat
            if (BlockOverlayService.isLockOverlayVisible.get()) {
                BlockOverlayService.hideLockOverlay()
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

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

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AppBlockAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    fun requestBatteryOptimizationIgnore(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    fun ensureServiceAlive(context: Context) {
        KeepAliveScheduler.schedule(appContext)
        restartServiceIfNeeded(context)
    }

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
        }

        val popularList = list.filter { popularPackages.contains(it.second) }.sortedBy { it.first.uppercase() }
        val otherList = list.filter { !popularPackages.contains(it.second) }.sortedBy { it.first.uppercase() }

        return popularList + otherList
    }
}
