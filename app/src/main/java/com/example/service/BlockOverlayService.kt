package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
            .setContentText("Uygulama kullanımı izleniyor.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        // API 29+ için ServiceCompat kullan
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                // API 34+ için SPECIAL_USE — en az kısıtlı tip
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
            ).apply {
                description = "Gardiyan arka plan izleme bildirimi"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ANA TAKİP DÖNGÜSÜ
    // queryUsageStats() kullanarak gün başından bu yana hedef uygulamanın
    // toplam kullanım süresini ms cinsinden alıp saniyeye çeviriyoruz.
    // Bu yöntem queryEvents()'e göre çok daha güvenilir ve tüm Android
    // sürümlerinde (özellikle 11+) tutarlı çalışır.
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

                        // ─── GÜNÜN BAŞLANGICI (00:00:00) ───
                        val dayStart = getDayStartMillis()
                        val now = System.currentTimeMillis()

                        // ─── queryUsageStats ile BUGÜNKÜ toplam kullanım süresi ───
                        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                        val usedMs: Long = if (usm != null) {
                            try {
                                val stats = usm.queryUsageStats(
                                    UsageStatsManager.INTERVAL_DAILY,
                                    dayStart,
                                    now
                                )
                                stats?.find { it.packageName == targetPkg }
                                    ?.totalTimeInForeground ?: 0L
                            } catch (e: Exception) {
                                0L
                            }
                        } else {
                            0L
                        }

                        // Gün başından bu yana kaç saniye kullanıldı
                        val usedSeconds = (usedMs / 1000L).toInt()

                        // Günlük limit kaç saniye
                        val limitSeconds = session.dailyLimitMinutes * 60

                        // Kalan saniye
                        val remainingSeconds = (limitSeconds - usedSeconds).coerceAtLeast(0)

                        // Kalan dakika (UI'da gösterim için)
                        val remainingMinutes = remainingSeconds / 60

                        // ─── DB GÜNCELLE (yalnızca değer değiştiyse) ───
                        if (session.remainingSecondsToday != remainingSeconds ||
                            session.remainingMinutesToday != remainingMinutes) {
                            repository.saveSession(
                                session.copy(
                                    remainingSecondsToday = remainingSeconds,
                                    remainingMinutesToday = remainingMinutes,
                                    lastCheckedMillis = now
                                )
                            )
                        }

                        // ─── BLOK KONTROL: Süre bitti mi ve hedef uygulama ön planda mı? ───
                        val isExpired = remainingSeconds <= 0
                        val isForeground = isTargetAppInForeground(targetPkg)

                        if (isExpired && isForeground && !isBlockActivityShown) {
                            isBlockActivityShown = true

                            repository.insertLog(
                                eventType = "BLOCKED",
                                appName = session.targetAppName,
                                details = "Günlük limit aşıldı ($usedSeconds sn kullanıldı / $limitSeconds sn limit). Erişim engellendi."
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

                        // Hedef uygulamadan çıkıldıysa flag'i sıfırla
                        if (!isForeground) {
                            isBlockActivityShown = false
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }

                delay(1000L) // Her saniye kontrol
            }
        }
    }

    /**
     * Hedef uygulamanın şu an ön planda (foreground) olup olmadığını kontrol eder.
     * queryUsageStats(INTERVAL_BEST) ile son 10 saniyelik pencerede event kontrolü.
     * Bu yöntem, queryEvents()'e ek olarak çapraz doğrulama sağlar.
     */
    private fun isTargetAppInForeground(targetPkg: String): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val now = System.currentTimeMillis()

        return try {
            // Son 10 saniyede INTERVAL_BEST ile kontrol
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                now - 10_000L,
                now
            )
            if (stats.isNullOrEmpty()) return false

            // En son kullanılan uygulama hedef paket mi?
            val recent = stats
                .filter { it.lastTimeUsed > now - 10_000L }
                .maxByOrNull { it.lastTimeUsed }

            recent?.packageName == targetPkg
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Bugünün başlangıcını (00:00:00) ms cinsinden döndürür.
     */
    private fun getDayStartMillis(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isBlockActivityShown = false
        serviceJob.cancel()
    }
}
