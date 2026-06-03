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
import com.example.MainActivity
import com.example.BlockActivity
import com.example.data.local.database.GuardianDatabase
import com.example.data.repository.GuardianRepository
import kotlinx.coroutines.*

class BlockOverlayService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var trackingJob: Job? = null

    companion object {
        var isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startForegroundService()
        startAppInterception()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startForegroundService() {
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
            .build()

        startForeground(101, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "gardiyan_service_channel",
                "Gardiyan İzleme Servisi",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private var activeSecondsCount = 0
    private var lastTrackedPkg = ""

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
                            // Reset local seconds tracker if the target app package changed
                            if (lastTrackedPkg != targetPkg) {
                                lastTrackedPkg = targetPkg
                                activeSecondsCount = 0
                            }

                            val remainingMinutes = session.remainingMinutesToday
                            val isExpired = remainingMinutes <= 0

                            // Query active foreground package using the fast queryEvents API
                            val foregroundPkg = getForegroundPackageName() ?: ""

                            // Check if the user is currently using the target app
                            val isUsingTargetApp = foregroundPkg.contains(targetPkg, ignoreCase = true)

                            if (isUsingTargetApp && !isExpired && foregroundPkg != packageName) {
                                // Real-time track usage by incrementing seconds
                                activeSecondsCount++
                                if (activeSecondsCount >= 60) {
                                    activeSecondsCount = 0
                                    val newRemaining = (remainingMinutes - 1).coerceAtLeast(0)
                                    repository.saveSession(session.copy(remainingMinutesToday = newRemaining))
                                    
                                    // Log the time decrement in the app logs for user transparency
                                    repository.insertLog(
                                        eventType = "TIME_DECREMENT",
                                        appName = session.targetAppName,
                                        details = "Şu an uygulamadasınız. Kalan süre 1 dakika düştü. Kalan: $newRemaining dakika."
                                    )
                                }
                            }

                            // If expired, block access!
                            val shouldBlock = isExpired && isUsingTargetApp
                            if (shouldBlock && foregroundPkg != packageName) {
                                val blockIntent = Intent(applicationContext, BlockActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    putExtra("app_name", session.targetAppName)
                                    putExtra("app_pkg", targetPkg)
                                }
                                startActivity(blockIntent)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(1000) // Poll every 1 second - ultra fast, responsive, and uses ~0% CPU!
            }
        }
    }

    private fun getAppUsageMinutesForToday(context: Context, targetPackage: String): Int {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return -1
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = try {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        } catch (e: Exception) {
            null
        }

        if (stats == null) return -1
        if (stats.isEmpty()) {
            return 0
        }

        val appStats = stats.find { it.packageName == targetPackage }
        if (appStats != null) {
            return (appStats.totalTimeInForeground / (1000 * 60)).toInt()
        }
        return 0
    }

    private fun getForegroundPackageName(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 15 // Last 15 seconds
        val events = try {
            usageStatsManager.queryEvents(startTime, endTime)
        } catch (e: Exception) {
            null
        } ?: return null

        var lastForegroundPkg: String? = null
        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForegroundPkg = event.packageName
            }
        }
        return lastForegroundPkg
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceJob.cancel()
    }
}
