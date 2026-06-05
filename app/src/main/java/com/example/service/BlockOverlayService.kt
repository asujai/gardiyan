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
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.data.local.database.GuardianDatabase
import com.example.data.repository.GuardianRepository
import com.example.data.local.entity.AppRestrictionEntity
import kotlinx.coroutines.*

class BlockOverlayService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var trackingJob: Job? = null

    private var lastForegroundPackage: String = ""
    private var isCurrentlyBlocked = false

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

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
        return START_STICKY
    }

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

    private val discouragingMessages = listOf(
        "Vazgeç.",
        "Bu savaşı kazanamazsın.",
        "İraden zayıf mı?",
        "Ekranı kapat ve git.",
        "Burada zaman kaybetmek yerine hayatına dön."
    )
    private var infiniteLoopCounter = 10
    private var currentMessageIndex = 0
    private var overlayTextView: TextView? = null

    // ─────────────────────────────────────────────────────────────────
    // ZERO-DELAY OVERLAY MİMARİSİ
    // delay(1000) ile her saniye taranır. Hedef paket bulununca Overlay açılır.
    // ─────────────────────────────────────────────────────────────────
    private fun startTracking() {
        trackingJob = serviceScope.launch {
            val db = GuardianDatabase.getDatabase(applicationContext)
            val repository = GuardianRepository(db.guardianDao())

            while (isActive) {
                try {
                    val activeRestrictions = repository.getAllAppRestrictionsSync().filter { it.isActive }
                    val targetPkgs = activeRestrictions.map { it.packageName }
                    
                    val currentApp = getForegroundPackage()
                    if (!currentApp.isNullOrEmpty()) {
                        lastForegroundPackage = currentApp
                    }

                    if (targetPkgs.contains(lastForegroundPackage)) {
                        val activeRestriction = activeRestrictions.find { it.packageName == lastForegroundPackage }
                        
                        if (activeRestriction != null) {
                            var remainingSeconds = activeRestriction.remainingSecondsToday

                            if (remainingSeconds > 0) {
                                remainingSeconds -= 1
                                val remainingMinutes = remainingSeconds / 60

                                repository.saveAppRestriction(
                                    activeRestriction.copy(
                                        remainingSecondsToday = remainingSeconds,
                                        remainingMinutesToday = remainingMinutes
                                    )
                                )
                            }

                            if (remainingSeconds <= 0) {
                                // SÜRE DOLDU - OVERLAY ÇIKAR VE INFINITE LOOP BAŞLAT
                                withContext(Dispatchers.Main) {
                                    showOverlay()
                                    updateOverlayText()
                                }
                                
                                infiniteLoopCounter -= 1
                                if (infiniteLoopCounter <= 0) {
                                    // Döngü başa sarıyor
                                    infiniteLoopCounter = 10
                                    currentMessageIndex = (currentMessageIndex + 1) % discouragingMessages.size
                                }
                                
                                if (!isCurrentlyBlocked) {
                                    isCurrentlyBlocked = true
                                    repository.failActiveTarget(lastForegroundPackage)
                                }
                            }
                        }
                    } else {
                        if (isCurrentlyBlocked) {
                            withContext(Dispatchers.Main) {
                                hideOverlay()
                            }
                            isCurrentlyBlocked = false
                            infiniteLoopCounter = 10
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                delay(1000L)
            }
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        
        overlayView = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E53935")) // Kırmızı Arka Plan
            overlayTextView = TextView(this@BlockOverlayService).apply {
                text = "GARDİYAN\n\nZaman Doldu!\n"
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            addView(overlayTextView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        
        try {
            windowManager?.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback (fakat artık infinite loop ile pes etmesi bekleniyor)
        }
    }

    private fun updateOverlayText() {
        val currentMsg = discouragingMessages[currentMessageIndex]
        overlayTextView?.text = "GARDİYAN\n\n$currentMsg\n\nYeniden başlatılıyor: $infiniteLoopCounter"
    }

    private fun hideOverlay() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
            overlayTextView = null
        }
    }

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
        
        // Son 3 saniyelik zaman dilimindeki eventleri çek (Kullanıcının isteği 2-3 saniye)
        val usageEvents = usm.queryEvents(time - 3000, time)
        val event = UsageEvents.Event()
        
        var currentForegroundApp: String? = null
        
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            // ACTIVITY_RESUMED (1) veya eski API'ler için MOVE_TO_FOREGROUND (1) kontrolü
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == 1) {
                currentForegroundApp = event.packageName
            }
        }
        
        return currentForegroundApp
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceJob.cancel()
        hideOverlay()
    }
}
