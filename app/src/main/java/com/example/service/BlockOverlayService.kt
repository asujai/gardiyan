package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.database.GuardianDatabase
import com.example.data.repository.GuardianRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service — 10 saniye sonsuz döngüde kilit ekranı.
 *
 * Sorumlulukları:
 * 1. Foreground notification ile sürekli çalışır vaziyette kalmak (Android 14+)
 * 2. onTaskRemoved → AlarmManager ile yeniden başlatma
 * 3. AppBlockAccessibilityService'ten gelen "showLockOverlay" komutuyla
 *    WindowManager.addView() kullanarak hedef uygulamanın ÜZERİNE lock overlay çizmek
 * 4. 10 saniyelik geri sayım → 0'a ulaştığında ANINDA 10'a reset → sonsuz döngü
 * 5. Kilit ekranından çıkış YOK; sadece Gardiyan MainActivity'den 5sn basılı
 *    tutarak iptal edilebilir
 *
 * KRİTİK: Sayaç 0'a ulaştığında:
 * - isActive = false KULLANILMAZ
 * - Overlay kapanmaz
 * - Sayaç anında 10'a resetlenir
 * - Döngü sonsuza kadar devam eder
 *
 * İptal akışı:
 * - ViewModel.cancelAllWithFiveSecondHold() çağrılır
 * - hideLockOverlay() çağrılarak overlay kapatılır
 */
class BlockOverlayService : Service() {

    companion object {
        private const val TAG = "BlockOverlayService"
        private const val ALARM_RESTART_REQUEST_CODE = 300
        const val CHANNEL_ID = "gardiyan_service_channel"
        const val NOTIF_ID = 101

        // 10 saniye sonsuz döngü
        const val LOCK_CYCLE_SECONDS = 10

        // Emoji harici dosya yolu (URI). null ise varsayılan emoji kullanılır.
        // Bu değer, MainActivity'den veya ayarlardan set edilebilir.
        @Volatile
        var customEmojiUri: String? = null

        // Emoji yazısı (TextView fallback). Default 🚫
        @Volatile
        var defaultEmojiText: String = "🚫"

        @JvmStatic
        val isServiceRunning = AtomicBoolean(false)

        @JvmStatic
        val isLockOverlayVisible = AtomicBoolean(false)

        @Volatile
        private var serviceInstance: BlockOverlayService? = null

        @Volatile
        private var appContextRef: Context? = null

        @Volatile
        private var pendingOverlayTarget: Pair<String, String>? = null

        /**
         * AppBlockAccessibilityService tarafından çağrılır: lock overlay'i
         * pencere yöneticisine ekle. Eğer zaten görünürse no-op.
         * Service çalışmıyorsa pending olarak saklar, service onCreate'te uygular.
         */
        @JvmStatic
        fun showLockOverlay(targetAppName: String, targetAppPackage: String) {
            Log.i(TAG, "showLockOverlay requested for $targetAppName")
            val instance = serviceInstance
            if (instance != null) {
                instance.addLockOverlayView(targetAppName, targetAppPackage)
            } else {
                pendingOverlayTarget = targetAppName to targetAppPackage
                Log.w(TAG, "Service instance null, queuing pending overlay and restarting")
                val ctx = appContextRef ?: return
                try {
                    start(ctx)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service: ${e.message}")
                }
            }
        }

        /**
         * A11y service hedef uygulamadan çıkışı tespit ettiğinde çağırır.
         * Veya ViewModel 5sn basılı tutma sonrası iptal için çağırır.
         */
        @JvmStatic
        fun hideLockOverlay() {
            Log.i(TAG, "hideLockOverlay requested")
            pendingOverlayTarget = null
            serviceInstance?.removeLockOverlayView()
        }

        /**
         * Harici URI'den emoji yüklemek için. Property assignment kullanın:
         * `BlockOverlayService.customEmojiUri = uri` (companion var setter).
         */
        fun start(context: Context) {
            val serviceIntent = Intent(context, BlockOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var lockOverlayView: View? = null
    private var cycleJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning.set(true)
        serviceInstance = this
        appContextRef = applicationContext
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForegroundCompat()
        Log.i(TAG, "Service created (10s infinite loop lock)")

        pendingOverlayTarget?.let { (name, pkg) ->
            pendingOverlayTarget = null
            mainHandler.post {
                addLockOverlayViewInternal(name)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed, scheduling restart via AlarmManager")
        scheduleRestart()
    }

    override fun onDestroy() {
        Log.w(TAG, "Service destroyed")
        isServiceRunning.set(false)
        serviceInstance = null
        appContextRef = null
        cycleJob?.cancel()
        cycleJob = null
        removeLockOverlayView()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun scheduleRestart() {
        try {
            val restartServiceIntent = Intent(applicationContext, BlockOverlayService::class.java).apply {
                setPackage(packageName)
            }
            val pendingIntent = PendingIntent.getService(
                this, ALARM_RESTART_REQUEST_CODE, restartServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmService.canScheduleExactAlarms()) {
                    alarmService.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME,
                        SystemClock.elapsedRealtime() + 1000L,
                        pendingIntent
                    )
                } else {
                    alarmService.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME,
                        SystemClock.elapsedRealtime() + 1000L,
                        pendingIntent
                    )
                }
            } else {
                alarmService.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000L,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to schedule exact alarm: ${e.message}")
        }
    }

    private fun startForegroundCompat() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gardiyan Aktif")
            .setContentTitle(getString(R.string.notification_channel_service))
            .setContentText(getString(R.string.notification_channel_service_desc))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                foregroundServiceType
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /**
     * Hedef uygulamanın ÜZERİNE WindowManager.addView ile gerçek bir View çiz.
     * Bu yöntem Android 11+ background activity start kısıtlamasından etkilenmez.
     */
    private fun addLockOverlayView(targetAppName: String, targetAppPackage: String) {
        if (isLockOverlayVisible.get() && lockOverlayView != null) {
            forceOverlayToFrontInternal()
            return
        }
        if (lockOverlayView != null) return

        mainHandler.post {
            addLockOverlayViewInternal(targetAppName)
        }
    }

    private fun addLockOverlayViewInternal(targetAppName: String) {
        val wm = windowManager ?: run {
            Log.e(TAG, "WindowManager null, cannot add overlay")
            return
        }

        try {
            val overlayView = LayoutInflater.from(this).inflate(R.layout.lock_overlay, null)
            val targetText = overlayView.findViewById<TextView>(R.id.targetAppText)
            val countdownText = overlayView.findViewById<TextView>(R.id.countdownText)
            val emojiText = overlayView.findViewById<TextView>(R.id.emojiText)
            val emojiImage = overlayView.findViewById<ImageView>(R.id.emojiImage)

            targetText.text = if (targetAppName.isNotEmpty()) {
                "$targetAppName için günlük süren doldu."
            } else {
                "Bu uygulama için günlük süren doldu."
            }

            // Emoji: önce harici URI dene, yoksa varsayılan TextView emoji'sini kullan
            val uri = customEmojiUri
            if (!uri.isNullOrEmpty()) {
                tryLoadEmojiFromUri(uri, emojiImage, emojiText)
            } else {
                emojiText.text = defaultEmojiText
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            wm.addView(overlayView, params)
            lockOverlayView = overlayView
            isLockOverlayVisible.set(true)

            startInfiniteLoop(countdownText, emojiText, emojiImage)
            Log.i(TAG, "Lock overlay added for $targetAppName (10s infinite loop started)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add lock overlay: ${e.message}", e)
        }
    }

    /**
     * Harici URI'den ImageView'a resim yüklemeyi dene. Başarısız olursa
     * TextView emoji fallback'ine düş.
     */
    private fun tryLoadEmojiFromUri(uri: String, emojiImage: ImageView, emojiText: TextView) {
        serviceScope.launch {
            try {
                val parsed = Uri.parse(uri)
                val bitmap = withContext(Dispatchers.IO) {
                    val input: InputStream? = contentResolver.openInputStream(parsed)
                    input?.use { BitmapFactory.decodeStream(it) }
                }
                if (bitmap != null) {
                    mainHandler.post {
                        emojiImage.setImageBitmap(bitmap)
                        emojiImage.visibility = View.VISIBLE
                        emojiText.visibility = View.GONE
                    }
                } else {
                    Log.w(TAG, "Failed to decode emoji URI bitmap, fallback to text")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load emoji from URI: ${e.message}")
            }
        }
    }

    private fun forceOverlayToFrontInternal() {
        mainHandler.post {
            val view = lockOverlayView
            val wm = windowManager
            if (view != null && wm != null && isLockOverlayVisible.get()) {
                try {
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                    }
                    wm.updateViewLayout(view, params)
                    Log.d(TAG, "forceOverlayToFrontInternal: overlay layout updated to top most")
                } catch (e: Exception) {
                    Log.w(TAG, "forceOverlayToFrontInternal failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Overlay'i pencere yöneticisinden kaldır.
     */
    private fun removeLockOverlayView() {
        if (lockOverlayView == null && !isLockOverlayVisible.get()) return
        mainHandler.post {
            removeLockOverlayViewInternal()
        }
    }

    private fun removeLockOverlayViewInternal() {
        cycleJob?.cancel()
        cycleJob = null

        val view = lockOverlayView ?: return
        val wm = windowManager ?: return

        if (!isLockOverlayVisible.get()) {
            lockOverlayView = null
            return
        }

        try {
            wm.removeView(view)
            Log.i(TAG, "Lock overlay removed")
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed (already detached?): ${e.message}")
        } finally {
            lockOverlayView = null
            isLockOverlayVisible.set(false)
        }
    }

    /**
     * 10 saniyelik KIRILMAZ sonsuz döngü:
     *  1. 10sn geri sayım başlat
     *  2. Her saniye TextView güncelle
     *  3. 0'a ulaşınca ANINDA tekrar 10'a dön (gap yok, emoji gösterimi yok)
     *  4. isActive ASLA false yapılmaz
     *  5. Overlay ASLA bu fonksiyon tarafından kapatılmaz
     *  6. Sadece hideLockOverlay() çağrıldığında isLockOverlayVisible false olur
     *     ve while döngüsü doğal olarak sonlanır
     */
    private fun startInfiniteLoop(
        countdownText: TextView,
        emojiText: TextView,
        emojiImage: ImageView
    ) {
        cycleJob?.cancel()
        Log.d(TAG, "startInfiniteLoop: 10s cycle begins (FOREVER — no break)")

        cycleJob = serviceScope.launch {
            while (isLockOverlayVisible.get()) {
                // 10 saniyelik geri sayım
                for (remaining in LOCK_CYCLE_SECONDS downTo 1) {
                    if (!isLockOverlayVisible.get()) return@launch
                    runOnUiThreadSafe {
                        countdownText.text = formatSeconds(remaining)
                        forceOverlayToFrontInternal()
                    }
                    kotlinx.coroutines.delay(1000L)
                }

                // 0'a ulaşıldı — ANINDA tekrar başa sar, hiç gap yok
                Log.d(TAG, "10s reached 0, instantly resetting to $LOCK_CYCLE_SECONDS (no gap, no break)")
                runOnUiThreadSafe {
                    countdownText.text = formatSeconds(LOCK_CYCLE_SECONDS)
                    forceOverlayToFrontInternal()
                }

                // Döngü while ile başa sarar — sonsuz, kırılmaz
            }
        }
    }

    private fun formatSeconds(totalSec: Int): String {
        val mm = totalSec / 60
        val ss = totalSec % 60
        return String.format("%02d:%02d", mm, ss)
    }

    /**
     * UI thread'ine güvenli geçiş (service bir background thread'den çağrılabilir).
     */
    private fun runOnUiThreadSafe(action: () -> Unit) {
        try {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post(action)
        } catch (e: Exception) {
            action()
        }
    }
}
