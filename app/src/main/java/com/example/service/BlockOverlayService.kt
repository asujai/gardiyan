package com.example.service

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayShowing = false
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
    // APP LOCKER MIMARISI (OVERLAY)
    // 250ms döngü ile hedefe girildiği "an" silinemez kırmızı perde indirilir.
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

                            // Süre bitmişse OVERLAY BASTIR
                            if (remainingSeconds <= 0) {
                                showOverlay(session.targetAppName)
                                
                                if (!isCurrentlyBlocked) {
                                    isCurrentlyBlocked = true
                                    repository.insertLog(
                                        eventType = "BLOCKED",
                                        appName = session.targetAppName,
                                        details = "Uygulama tam ekran maskelendi (Süre doldu)."
                                    )
                                }
                            }
                        } else {
                            // Hedef uygulamadan çıkıldığı an maskeyi kaldır (Anında tepki)
                            removeOverlay()
                            isCurrentlyBlocked = false
                            
                            // Tick süresini senkronize et ki uygulamaya girince direkt 1 sn gitmesin
                            lastSecondTick = System.currentTimeMillis()
                        }
                    } else {
                        removeOverlay()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Çok hızlı (250ms) algılama, sıfır gecikme!
                delay(250L)
            }
        }
    }

    private fun showOverlay(appName: String) {
        if (isOverlayShowing) return
        isOverlayShowing = true // Çift çağrıyı hemen engelle

        Handler(Looper.getMainLooper()).post {
            if (overlayView != null) return@post

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            // LayoutParams.FLAG_LAYOUT_IN_SCREEN | FLAG_NOT_TOUCH_MODAL (Kullanıcı arkaya tıklayamasın diye modal değil, odak alabilen tam ekran)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#B71C1C")) // Koyu Kırmızı / Danger
                gravity = Gravity.CENTER
                
                val tvHeader = TextView(context).apply {
                    text = "SÜRE DOLDU"
                    setTextColor(Color.WHITE)
                    textSize = 40f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 24)
                    }
                }
                
                val tvDesc = TextView(context).apply {
                    text = "$appName kilitlendi.\nBugünlük irade sınırınızı doldurdunuz."
                    setTextColor(Color.parseColor("#FFCDD2"))
                    textSize = 16f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 80)
                    }
                }
                
                val btnHome = Button(context).apply {
                    text = "ANA EKRANA DÖN"
                    setBackgroundColor(Color.WHITE)
                    setTextColor(Color.parseColor("#B71C1C"))
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(60, 40, 60, 40)
                    setOnClickListener {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                    }
                }
                
                addView(tvHeader)
                addView(tvDesc)
                addView(btnHome)
            }
            
            overlayView = layout
            try {
                windowManager?.addView(overlayView, params)
            } catch (e: Exception) {
                e.printStackTrace()
                isOverlayShowing = false
            }
        }
    }

    private fun removeOverlay() {
        if (!isOverlayShowing) return
        isOverlayShowing = false

        Handler(Looper.getMainLooper()).post {
            if (overlayView != null) {
                try {
                    windowManager?.removeView(overlayView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                overlayView = null
            }
        }
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
        removeOverlay()
        serviceJob.cancel()
    }
}
