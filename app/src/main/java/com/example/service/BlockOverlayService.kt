package com.example.service

import android.app.AlarmManager
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.data.local.database.GuardianDatabase
import com.example.data.repository.GuardianRepository
import kotlinx.coroutines.*

class BlockOverlayService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var trackingJob: Job? = null

    private var lastForegroundPackage: String = ""
    private var isCurrentlyBlocked = false

    companion object {
        var isServiceRunning = false
        const val CHANNEL_ID = "gardiyan_service_channel"
        const val NOTIF_ID = 101
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startForegroundCompat()
        startTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Servisin Android tarafından öldürülürse yeniden başlatılmasını ister
        return START_STICKY
    }

    /**
     * UYGULAMA RECENT APPS'TEN KAPATILDIĞINDA (SWIPE TO DISMISS) ÇALIŞIR
     * Servisin ölümsüz olması (hemen tekrar canlanması) için AlarmManager kullanıyoruz.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        
        val restartServiceIntent = Intent(applicationContext, BlockOverlayService::class.java).also {
            it.setPackage(packageName)
        }
        
        val restartServicePendingIntent = PendingIntent.getService(
            this, 1, restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val alarmService = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
    }

    private fun startForegroundCompat() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gardiyan Aktif")
            .setContentText("Kilitli uygulamalarınız anlık olarak denetleniyor.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gardiyan İzleme Servisi",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // FORCE HOME (ANA EKRANA FIRLATMA) MİMARİSİ
    // 250ms döngü ile hedefe girildiği "an" kullanıcı ana ekrana geri fırlatılır.
    // ─────────────────────────────────────────────────────────────────
    private fun startTracking() {
        trackingJob = serviceScope.launch {
            val db = GuardianDatabase.getDatabase(applicationContext)
            val repository = GuardianRepository(db.guardianDao())

            var lastSecondTick = System.currentTimeMillis()

            while (isActive) {
                try {
                    val session = repository.getSessionSync()

                    if (session != null && session.isActive && session.targetAppPackage.isNotEmpty()) {
                        val targetPkg = session.targetAppPackage
                        val currentApp = getForegroundPackage()
                        
                        if (!currentApp.isNullOrEmpty()) {
                            lastForegroundPackage = currentApp
                        }

                        // Sadece hedef uygulama açıkken süre sayar ve bloklar
                        if (lastForegroundPackage == targetPkg) {
                            val now = System.currentTimeMillis()
                            var remainingSeconds = session.remainingSecondsToday

                            // Saniyede 1 kez süreyi düşür (çünkü döngü 250ms)
                            if (now - lastSecondTick >= 1000L) {
                                lastSecondTick = now
                                if (remainingSeconds > 0) {
                                    remainingSeconds -= 1
                                    val remainingMinutes = remainingSeconds / 60

                                    repository.saveSession(
                                        session.copy(
                                            remainingSecondsToday = remainingSeconds,
                                            remainingMinutesToday = remainingMinutes,
                                            lastCheckedMillis = now
                                        )
                                    )
                                }
                            }

                            // Süre bitmişse ZORLA ANA EKRANA FIRLAT
                            if (remainingSeconds <= 0) {
                                forceHomeScreen()
                                
                                if (!isCurrentlyBlocked) {
                                    isCurrentlyBlocked = true
                                    repository.insertLog(
                                        eventType = "BLOCKED",
                                        appName = session.targetAppName,
                                        details = "Süre doldu, kullanıcı ana ekrana fırlatıldı."
                                    )
                                }
                            }
                        } else {
                            // Hedef uygulamadan çıkıldığı an bayrağı temizle
                            isCurrentlyBlocked = false
                            // Tick süresini senkronize et ki uygulamaya girince direkt 1 sn gitmesin
                            lastSecondTick = System.currentTimeMillis()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Çok hızlı (250ms) algılama, sıfır gecikme!
                delay(250L)
            }
        }
    }

    /**
     * Kullanıcıyı zorla Android Ana Ekranına (Home) gönderir.
     * Bu sayede "Diğer uygulamaların üzerinde göster" (Overlay) bildirimleri çıkmaz.
     */
    private fun forceHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(homeIntent)
    }

    private fun getForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val time = System.currentTimeMillis()
        
        // Son 1 dakikadaki eventlere bak
        val usageEvents = usm.queryEvents(time - 1000 * 60, time)
        val event = UsageEvents.Event()
        
        var currentForegroundApp: String? = null
        
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentForegroundApp = event.packageName
            }
        }
        
        return currentForegroundApp
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceJob.cancel()
    }
}
