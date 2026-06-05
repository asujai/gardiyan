package com.example.service

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.data.local.database.GuardianDatabase
import com.example.data.local.entity.RestrictedAppEntity
import com.example.data.repository.GuardianRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Çoklu uygulama + event-driven ön plan tespiti ve zamanlayıcı.
 *
 * Mimari:
 * - PRIMARY: TYPE_WINDOW_STATE_CHANGED event'lerini dinler
 * - SECONDARY: UsageStatsManager.queryEvents() ile 2sn polling yedek katmanı
 * - Kısıtlanmış uygulamalar listesindeki (RestrictedAppEntity) bir hedefe
 *   girildiğinde entryTimeMillis kaydedilir
 * - Çıkışta elapsed = now - entryTimeMillis hesaplanır
 * - Veritabanındaki remainingSecondsToday değerinden delta düşülür
 * - Eğer remainingSecondsToday <= 0 ise, hedef uygulamaya her girişte
 *   overlay anında çizilir (10sn sonsuz döngüde)
 * - Aynı anda yalnız bir uygulamada kalınabilir; en son girilen uygulama
 *   state'i taşır
 */
class AppBlockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AppBlockA11yService"

        /** UsageStats polling aralığı (ms) */
        private const val USAGE_STATS_POLL_INTERVAL_MS = 300L

        @Volatile
        var instance: AppBlockAccessibilityService? = null

        @Volatile
        var isRunning: Boolean = false

        // Şu anda izlenen hedef uygulamaya GİRİŞ zamanı (epoch ms).
        // Kullanıcı kısıtlı uygulamayı açtığı an kaydedilir, çıktığı an sıfırlanır.
        @Volatile
        var entryTimeMillis: Long = 0L

        // Şu anda izlenen hedef uygulamanın paket adı.
        // null ise kullanıcı kısıtlı bir uygulamada değil.
        @Volatile
        var currentTrackedPackage: String? = null

        // Hangi restricted app'in DB satırı izleniyor (id).
        @Volatile
        var currentTrackedAppId: Long = -1L
    }

    private val a11yJob = SupervisorJob()
    private val a11yScope = CoroutineScope(Dispatchers.IO + a11yJob)

    // Kısıtlı uygulamada kalındığı sürece overlay'i tetikleyecek bekleyen coroutine.
    // Her girişte iptal edilip yeniden başlatılır.
    private var tickJob: Job? = null

    // UsageStats polling coroutine
    private var usageStatsPollingJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        Log.i(TAG, "Accessibility service connected")

        // UsageStats polling yedek katmanını başlat
        startUsageStatsPolling()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        instance = null
        tickJob?.cancel()
        tickJob = null
        usageStatsPollingJob?.cancel()
        usageStatsPollingJob = null
        Log.w(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        tickJob?.cancel()
        tickJob = null
        usageStatsPollingJob?.cancel()
        usageStatsPollingJob = null
        a11yJob.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val foregroundPackage = event.packageName?.toString() ?: return
        // Kendi paketimizi yoksay
        if (foregroundPackage == packageName) return

        handleForegroundChange(foregroundPackage)
    }

    // ========================================================================
    // UsageStatsManager Polling — Yedek Katman
    // ========================================================================

    /**
     * Her 2 saniyede bir UsageStatsManager.queryEvents() ile son foreground
     * uygulamayı sorgular. AccessibilityService event'lerinin atlandığı
     * durumlarda (PiP, popup, Activity geçişleri) yedek olarak devreye girer.
     */
    private fun startUsageStatsPolling() {
        usageStatsPollingJob?.cancel()
        usageStatsPollingJob = a11yScope.launch {
            Log.i(TAG, "UsageStats polling started (interval: ${USAGE_STATS_POLL_INTERVAL_MS}ms)")
            while (isActive) {
                try {
                    val foregroundPkg = queryForegroundPackage()
                    if (foregroundPkg != null && foregroundPkg != packageName) {
                        // Sadece AccessibilityService'in kaçırdığı değişiklikleri yakala
                        val currentlyTracked = currentTrackedPackage
                        if (foregroundPkg != currentlyTracked) {
                            Log.d(TAG, "UsageStats polling detected foreground change: $foregroundPkg (tracked: $currentlyTracked)")
                            handleForegroundChange(foregroundPkg)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "UsageStats polling error: ${e.message}")
                }
                delay(USAGE_STATS_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * UsageStatsManager.queryEvents() ile son 5 saniye içindeki en son
     * MOVE_TO_FOREGROUND event'ini bularak aktif foreground uygulamasını döndürür.
     */
    private fun queryForegroundPackage(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null

            val endTime = System.currentTimeMillis()
            val startTime = endTime - 5000L // Son 5 saniye

            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            var lastForegroundPackage: String? = null

            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastForegroundPackage = event.packageName
                }
            }

            lastForegroundPackage
        } catch (e: SecurityException) {
            Log.w(TAG, "UsageStats permission not granted: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "queryForegroundPackage error: ${e.message}")
            null
        }
    }

    // ========================================================================
    // Foreground Change Handler
    // ========================================================================

    /**
     * Ön plan uygulama değişikliği işleyicisi. Event-driven timer'ın kalbi.
     * Çoklu uygulama listesine göre çalışır.
     *
     * Hem AccessibilityService event'lerinden hem UsageStats polling'den çağrılır.
     * Aynı paket için tekrar çağrılırsa no-op.
     */
    private fun handleForegroundChange(foregroundPackage: String) {
        a11yScope.launch {
            try {
                val db = GuardianDatabase.getDatabase(applicationContext)
                val repository = GuardianRepository(db.guardianDao())
                val activeApps = withContext(Dispatchers.IO) {
                    repository.getActiveRestrictedAppsForTodaySync()
                }

                if (activeApps.isEmpty()) {
                    // Aktif kısıtlama yok, hiçbir şey yapma
                    return@launch
                }

                val matchingApp = activeApps.firstOrNull { it.packageName == foregroundPackage }

                when {
                    // CASE 1: Kısıtlı uygulamaya GİRİLDİ
                    matchingApp != null -> {
                        // Önceki izlenen uygulamadan çıkışı işle (varsa ve farklıysa)
                        if (currentTrackedPackage != null && currentTrackedPackage != matchingApp.packageName) {
                            handleExit(repository, activeApps, System.currentTimeMillis())
                        }

                        // Yeni giriş veya aynı uygulamaya devam
                        if (currentTrackedPackage != matchingApp.packageName) {
                            entryTimeMillis = System.currentTimeMillis()
                            currentTrackedPackage = matchingApp.packageName
                            currentTrackedAppId = matchingApp.id
                            Log.d(TAG, "Target app entered: ${matchingApp.appName} (${matchingApp.packageName}) at $entryTimeMillis")
                        }

                        // remaining <= 0 ise overlay'i hemen göster; aksi halde tick coroutine başlat
                        if (matchingApp.remainingSecondsToday <= 0) {
                            tickJob?.cancel()
                            tickJob = null
                            repository.failRestrictedApp(matchingApp.id)
                            BlockOverlayService.showLockOverlay(
                                applicationContext,
                                matchingApp.appName,
                                matchingApp.packageName
                            )
                        } else {
                            val remaining = matchingApp.remainingSecondsToday
                            tickJob?.cancel()
                            tickJob = a11yScope.launch {
                                try {
                                    delay(remaining * 1000L)
                                    Log.d(TAG, "Tick fired: ${matchingApp.appName} remaining=$remaining reached zero")
                                    repository.updateRestrictedApp(
                                        matchingApp.copy(
                                            remainingSecondsToday = 0,
                                            remainingMinutesToday = 0
                                        )
                                    )
                                    repository.failRestrictedApp(matchingApp.id)
                                    BlockOverlayService.showLockOverlay(
                                        applicationContext,
                                        matchingApp.appName,
                                        matchingApp.packageName
                                    )
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    Log.d(TAG, "Tick cancelled (exited before time up)")
                                    throw e
                                }
                            }
                        }
                    }

                    // CASE 2: Kısıtlı olmayan bir uygulamaya geçildi
                    else -> {
                        if (currentTrackedPackage != null) {
                            handleExit(repository, activeApps, System.currentTimeMillis())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in handleForegroundChange: ${e.message}", e)
            }
        }
    }

    /**
     * İzlenen uygulamadan çıkışı işle: elapsed hesapla, remainingSeconds'i
     * güncelle, state'i sıfırla.
     *
     * NOT: isActive ASLA false yapılmaz. Uygulama aktif kalır.
     * Overlay sadece hideLockOverlay() ile kapatılır (5sn hold veya
     * kullanıcı uygulamadan çıkınca).
     */
    private suspend fun handleExit(
        repository: GuardianRepository,
        activeApps: List<RestrictedAppEntity>,
        exitTime: Long
    ) {
        val trackedPkg = currentTrackedPackage ?: return
        val trackedApp = activeApps.firstOrNull { it.packageName == trackedPkg }
            ?: run {
                // Tracked paket artık aktif listesinde yok, state'i temizle
                currentTrackedPackage = null
                currentTrackedAppId = -1L
                entryTimeMillis = 0L
                tickJob?.cancel()
                tickJob = null
                if (BlockOverlayService.isLockOverlayVisible.get()) {
                    BlockOverlayService.hideLockOverlay()
                }
                return
            }

        val elapsedMs = exitTime - entryTimeMillis
        val elapsedSec = (elapsedMs / 1000L).toInt().coerceAtLeast(1)
        val newRemaining = (trackedApp.remainingSecondsToday - elapsedSec).coerceAtLeast(0)
        val newMinutes = newRemaining / 60

        // Sadece süreyi güncelle — isActive DEĞİŞTİRİLMEZ
        withContext(Dispatchers.IO) {
            repository.updateRestrictedApp(
                trackedApp.copy(
                    remainingSecondsToday = newRemaining,
                    remainingMinutesToday = newMinutes
                )
            )
            if (newRemaining <= 0) {
                repository.failRestrictedApp(trackedApp.id)
            }
        }

        Log.d(TAG, "Exited ${trackedApp.appName}. Elapsed: ${elapsedSec}s, Remaining: ${newRemaining}s")

        tickJob?.cancel()
        tickJob = null

        // Overlay açıksa kapat (kullanıcı kısıtlı uygulamadan çıktı)
        if (BlockOverlayService.isLockOverlayVisible.get()) {
            BlockOverlayService.hideLockOverlay()
        }

        currentTrackedPackage = null
        currentTrackedAppId = -1L
        entryTimeMillis = 0L
    }
}
