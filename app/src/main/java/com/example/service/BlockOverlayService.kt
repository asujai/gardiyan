package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.BlockActivity
import com.example.data.local.database.GuardianDatabase
import com.example.data.repository.GuardianRepository
import kotlinx.coroutines.*
import java.util.Calendar

class BlockOverlayService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var trackingJob: Job? = null

    companion object {
        var isServiceRunning = false
        // BlockActivity'nin sürekli tekrar açılmasını engelleyen flag
        var isBlockActivityShown = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startForegroundCompat()
        startAppInterception()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * Android 14+ (API 34+) uyumlu startForeground.
     * foregroundServiceType belirtilmezse Android 14+'da servis hemen öldürülüyor.
     */
    private fun startForegroundCompat() {
        val channelId = "gardiyan_service_channel"
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Gardiyan Aktif")
            .setContentText("Kilitli uygulamalara erişiminiz izleniyor.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+ → ServiceCompat ile type-safe startForeground
            ServiceCompat.startForeground(
                this,
                101,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
        } else {
            startForeground(101, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "gardiyan_service_channel",
                "Gardiyan İzleme Servisi",
                NotificationManager.IMPORTANCE_LOW
            )
            serviceChannel.description = "Gardiyan arka plan izleme bildirimi"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private var activeSecondsCount = 0
    private var lastTrackedPkg = ""
    private var lastCheckedDayOfYear = -1

    private fun startAppInterception() {
        trackingJob = serviceScope.launch {
            val db = GuardianDatabase.getDatabase(applicationContext)
            val repository = GuardianRepository(db.guardianDao())

            while (isActive) {
                try {
                    val session = repository.getSessionSync()
                    if (session != null && session.isActive) {
                        val targetPkg = session.targetAppPackage

                        if (targetPkg.isNotEmpty()) {
                            // ─── GÜN SIFIRLAMASI: Gece yarısı geçildiyse kalan süreyi sıfırla ───
                            val todayDayOfYear = getCurrentDayOfYear()
                            if (lastCheckedDayOfYear != -1 && lastCheckedDayOfYear != todayDayOfYear) {
                                // Yeni güne geçildi: Kalan süreyi günlük limite sıfırla
                                repository.saveSession(
                                    session.copy(
                                        remainingMinutesToday = session.dailyLimitMinutes,
                                        lastCheckedMillis = System.currentTimeMillis()
                                    )
                                )
                                repository.insertLog(
                                    eventType = "DAILY_RESET",
                                    appName = session.targetAppName,
                                    details = "Yeni gün başladı. Kalan süre ${session.dailyLimitMinutes} dakikaya sıfırlandı."
                                )
                                activeSecondsCount = 0
                                lastCheckedDayOfYear = todayDayOfYear
                                delay(1000)
                                continue
                            }
                            lastCheckedDayOfYear = todayDayOfYear

                            // Hedef paket değiştiyse sayacı sıfırla
                            if (lastTrackedPkg != targetPkg) {
                                lastTrackedPkg = targetPkg
                                activeSecondsCount = 0
                            }

                            val remainingMinutes = session.remainingMinutesToday
                            val isExpired = remainingMinutes <= 0

                            // ─── FOREGROUND UYGULAMA TESPİTİ ───
                            val foregroundPkg = getForegroundPackageName() ?: ""

                            // Hedef uygulamanın kullanımda olup olmadığını kontrol et
                            val isUsingTargetApp = foregroundPkg.isNotEmpty() &&
                                    foregroundPkg.contains(targetPkg, ignoreCase = true)

                            // ─── SÜRE SAYACI: Kullanımdaysa ve süresi dolmamışsa say ───
                            if (isUsingTargetApp && !isExpired && foregroundPkg != packageName) {
                                activeSecondsCount++
                                if (activeSecondsCount >= 60) {
                                    activeSecondsCount = 0
                                    val newRemaining = (remainingMinutes - 1).coerceAtLeast(0)
                                    repository.saveSession(session.copy(remainingMinutesToday = newRemaining))

                                    repository.insertLog(
                                        eventType = "TIME_DECREMENT",
                                        appName = session.targetAppName,
                                        details = "Uygulama kullanımda. Kalan süre 1 dakika düştü. Kalan: $newRemaining dakika."
                                    )
                                }
                            }

                            // ─── KİLİT MEKANİZMASI: Süre dolduysa ve uygulamadaysa engelle ───
                            val shouldBlock = isExpired && isUsingTargetApp && foregroundPkg != packageName

                            if (shouldBlock && !isBlockActivityShown) {
                                // Flag'i true yap: Aynı anda birden fazla BlockActivity açılmasın
                                isBlockActivityShown = true

                                repository.insertLog(
                                    eventType = "BLOCKED",
                                    appName = session.targetAppName,
                                    details = "Günlük limit aşıldı. Erişim engellendi."
                                )

                                val blockIntent = Intent(applicationContext, BlockActivity::class.java).apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    )
                                    putExtra("app_name", session.targetAppName)
                                    putExtra("app_pkg", targetPkg)
                                }
                                startActivity(blockIntent)
                            }

                            // ─── UNBLOCK: Hedef uygulamadan çıkıldıysa flag sıfırla ───
                            if (!isUsingTargetApp) {
                                isBlockActivityShown = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(1000) // Her 1 saniyede bir kontrol
            }
        }
    }

    /**
     * Mevcut günün yıl içindeki sıra numarasını döndürür (gün sıfırlama için).
     */
    private fun getCurrentDayOfYear(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * DÜZELTME: Son 15s → Son 3s + MOVE_TO_FOREGROUND fallback.
     *
     * Önceki implementasyon son 15 saniyeye bakıyordu. Kullanıcı bir uygulamayı
     * açıp 15 saniyeden fazla hareketsiz kaldıysa ACTIVITY_RESUMED eventi
     * pencereye girmiyordu ve fonksiyon null dönüyordu. Bu durumda takip
     * tamamen duruyordu. Şimdi:
     * 1. Son 3 saniyede ACTIVITY_RESUMED ara
     * 2. Bulamazsan son 5 saniyede MOVE_TO_FOREGROUND ara (aktif uygulama tespiti)
     * 3. Yine bulamazsan son 1 dakikadaki son ACTIVITY_RESUMED'u döndür (yedek)
     */
    private fun getForegroundPackageName(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()

        // 1. Aşama: Son 3 saniyede ACTIVITY_RESUMED
        val events3s = try { usm.queryEvents(now - 3_000, now) } catch (e: Exception) { null }
        if (events3s != null) {
            var lastPkg: String? = null
            val event = UsageEvents.Event()
            while (events3s.hasNextEvent()) {
                events3s.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastPkg = event.packageName
                }
            }
            if (lastPkg != null) return lastPkg
        }

        // 2. Aşama: Son 5 saniyede MOVE_TO_FOREGROUND (bazı cihazlarda kullanılır)
        val events5s = try { usm.queryEvents(now - 5_000, now) } catch (e: Exception) { null }
        if (events5s != null) {
            var lastPkg: String? = null
            val event = UsageEvents.Event()
            while (events5s.hasNextEvent()) {
                events5s.getNextEvent(event)
                @Suppress("DEPRECATION")
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastPkg = event.packageName
                }
            }
            if (lastPkg != null) return lastPkg
        }

        // 3. Aşama: Son 1 dakika içindeki en son ACTIVITY_RESUMED (yedek)
        val events60s = try { usm.queryEvents(now - 60_000, now) } catch (e: Exception) { null }
        if (events60s != null) {
            var lastPkg: String? = null
            val event = UsageEvents.Event()
            while (events60s.hasNextEvent()) {
                events60s.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastPkg = event.packageName
                }
            }
            if (lastPkg != null) return lastPkg
        }

        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isBlockActivityShown = false
        serviceJob.cancel()
    }
}
