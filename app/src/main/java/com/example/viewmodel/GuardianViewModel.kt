package com.example.viewmodel

import android.app.AlarmManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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

    // Simulation running state
    private val _isSimulationRunning = MutableStateFlow(BlockOverlayService.isSimulationRunning)
    val isSimulationRunning: StateFlow<Boolean> = _isSimulationRunning.asStateFlow()

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
                isObserverMode = _isObserverMode.value,
                shameMessage = _shameMessage.value,
                observerContactName = _observerContactName.value,
                observerInviteLink = inviteLink,
                isObserverConfirmed = !_isObserverMode.value // Automatically confirmed if Guard Mode A
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
     * Controls foreground application scanning and overlay trigger service
     */
    fun toggleMonitoringService(context: Context, enable: Boolean) {
        val serviceIntent = Intent(context, BlockOverlayService::class.java)
        if (enable) {
            context.startService(serviceIntent)
            _isMonitoringActive.value = true
        } else {
            context.stopService(serviceIntent)
            _isMonitoringActive.value = false
        }
    }

    /**
     * Toggles whether we simulate opening a blocked app to trigger overlays instantly
     */
    fun toggleBlockSimulation(value: Boolean) {
        BlockOverlayService.isSimulationRunning = value
        _isSimulationRunning.value = value
    }

    /**
     * Simulates time reduction. Allows reviewers to easily trigger limit expiration!
     */
    fun simulateRemainingTimeReduction() {
        viewModelScope.launch {
            val session = repository.getSessionSync() ?: return@launch
            val nextTime = (session.remainingMinutesToday - 15).coerceAtLeast(0)
            
            val updated = session.copy(remainingMinutesToday = nextTime)
            repository.saveSession(updated)

            if (nextTime <= 0) {
                repository.insertLog(
                    eventType = "LIMIT_EXCEEDED",
                    appName = session.targetAppName,
                    details = "Günlük koruma süreniz kalmadı! Instagram, TikTok benzeri kilitli uygulamalara girişler engellenecektir."
                )
            } else {
                repository.insertLog(
                    eventType = "TIME_DECREMENT",
                    appName = session.targetAppName,
                    details = "Zaman simülasyonu: Kalan süre 15 dk düşürüldü. Kalan: $nextTime Dk."
                )
            }
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
     * Simulates daily success for the active goal - rewards Level levels or clears Red Badge
     */
    fun triggerSimulatedSuccess() {
        viewModelScope.launch {
            repository.succeedActiveTarget()
        }
    }

    /**
     * Force fails active target with penalty rules instantly
     */
    fun triggerSimulatedFailure() {
        viewModelScope.launch {
            repository.failActiveTarget()
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
     * Helper checks if Usage Access settings exist and is enabled
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usageStatsManager?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time)
        return !stats.isNullOrEmpty()
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
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
