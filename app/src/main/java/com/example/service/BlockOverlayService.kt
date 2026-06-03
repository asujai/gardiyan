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
        var isSimulationRunning = false // Allowed in-app simulation toggle so reviewers can test easily
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

    private fun startAppInterception() {
        trackingJob = serviceScope.launch {
            val db = GuardianDatabase.getDatabase(applicationContext)
            val repository = GuardianRepository(db.guardianDao())

            while (isActive) {
                val session = repository.getSessionSync()
                if (session != null && session.isActive) {
                    val isExpired = session.remainingMinutesToday <= 0
                    val foregroundPkg = getForegroundPackageName() ?: ""

                    // If simulation toggle is explicitly turned on inside the app, block instantly.
                    // Otherwise block if foreground app is the targeted blocked app and time limit exceeded!
                    val shouldBlock = (isSimulationRunning && foregroundPkg != packageName) ||
                            (isExpired && session.targetAppPackage.isNotEmpty() &&
                             foregroundPkg.contains(session.targetAppPackage, ignoreCase = true))

                    if (shouldBlock && foregroundPkg != packageName) {
                        val blockIntent = Intent(applicationContext, BlockActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra("app_name", session.targetAppName)
                            putExtra("app_pkg", session.targetAppPackage)
                        }
                        startActivity(blockIntent)
                    }
                }
                delay(1200) // Poll frequently to catch application state switches
            }
        }
    }

    private fun getForegroundPackageName(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 10
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        if (!stats.isNullOrEmpty()) {
            return stats.maxByOrNull { it.lastTimeUsed }?.packageName
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isSimulationRunning = false
        serviceJob.cancel()
    }
}
