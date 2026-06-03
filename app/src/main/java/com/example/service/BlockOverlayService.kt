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

class BlockOverlayService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var trackingJob: Job? = null

    // Son bilinen ön plan uygulamasını hafızada tutarız.
    private var lastForegroundPackage: String = ""

    companion object {
        var isServiceRunning = false
        var isBlockActivityShown = false
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
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gardiyan Aktif")
            .setContentText("Kilitli uygulamalarınız arka planda denetleniyor.")
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
    // GERÇEK ZAMANLI FOREGROUND TAKİBİ
    // Uygulamanın süresi SADECE kullanıcı hedef uygulamanın (Instagram) 
    // içindeyken saniye saniye azalır. Başka bir uygulamadaysa azalmaz.
    // ─────────────────────────────────────────────────────────────────
    private fun startTracking() {
        trackingJob = serviceScope.launch {
            val db = GuardianDatabase.getDatabase(applicationContext)
            val repository = GuardianRepository(db.guardianDao())

            while (isActive) {
                try {
                    val session = repository.getSessionSync()

                    if (session != null && session.isActive && session.targetAppPackage.isNotEmpty()) {
                        val targetPkg = session.targetAppPackage

                        // 1. Ekrandaki güncel aktif uygulamayı bul
                        val currentApp = getForegroundPackage()
                        
                        // Eğer aktif uygulamayı okuyabildiysek, hafızaya alalım
                        if (currentApp != null && currentApp.isNotEmpty()) {
                            lastForegroundPackage = currentApp
                        }

                        // 2. Eğer kullanıcı ŞU ANDA hedef uygulamanın (Örn: Instagram) içindeyse
                        if (lastForegroundPackage == targetPkg) {
                            
                            var remainingSeconds = session.remainingSecondsToday
                            
                            // Süreden 1 saniye düşüyoruz çünkü Instagram açık
                            if (remainingSeconds > 0) {
                                remainingSeconds -= 1
                                val remainingMinutes = remainingSeconds / 60

                                // DB'ye güncel süreyi anında yazıyoruz ki Dashboard güncellensin
                                repository.saveSession(
                                    session.copy(
                                        remainingSecondsToday = remainingSeconds,
                                        remainingMinutesToday = remainingMinutes,
                                        lastCheckedMillis = System.currentTimeMillis()
                                    )
                                )
                            }

                            // 3. Süre bittiyse ve KULLANICI HALA INSTAGRAM İÇİNDEYSE (Veya girmeye çalıştıysa)
                            if (remainingSeconds <= 0) {
                                if (!isBlockActivityShown) {
                                    isBlockActivityShown = true
                                    
                                    repository.insertLog(
                                        eventType = "BLOCKED",
                                        appName = session.targetAppName,
                                        details = "Hedef uygulamaya erişim engellendi (Süre doldu)."
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
                            }
                        } else {
                            // Kullanıcı Instagram'da değil (Ana Ekranda, WhatsApp'ta vb.)
                            // Süre düşmez, hiçbir şey yapılmaz.
                            
                            // Eğer kullanıcının bulunduğu paket Gardiyan (com.example) değilse
                            // Veya kilit ekranından çıktıysa bayrağı sıfırla ki tekrar girebilirse tetiklensin.
                            if (lastForegroundPackage != packageName) {
                                isBlockActivityShown = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Döngü her 1 saniyede bir döner (Saniye bazlı kesin takip)
                delay(1000L)
            }
        }
    }

    /**
     * UsageEvents kullanarak son 1 dakika içerisindeki "ACTIVITY_RESUMED"
     * eventlerini tarar ve ekrandaki en son aktif uygulamayı bulur.
     */
    private fun getForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val time = System.currentTimeMillis()
        
        // Son 1 dakikadaki eventlere bak (Çok hızlı bir işlemdir)
        val usageEvents = usm.queryEvents(time - 1000 * 60, time)
        val event = UsageEvents.Event()
        
        var currentForegroundApp: String? = null
        
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            // Sadece Activity'nin ekrana gelme (RESUMED) olayını takip et
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentForegroundApp = event.packageName
            }
        }
        
        return currentForegroundApp
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isBlockActivityShown = false
        serviceJob.cancel()
    }
}
