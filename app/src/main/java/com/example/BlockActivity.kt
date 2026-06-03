package com.example

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.database.GuardianDatabase
import com.example.data.local.entity.UserSessionEntity
import com.example.data.repository.GuardianRepository
import com.example.service.BlockOverlayService
import com.example.ui.theme.*
import kotlinx.coroutines.*

class BlockActivity : ComponentActivity() {

    private lateinit var repository: GuardianRepository
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        BlockOverlayService.isBlockActivityShown = true

        // Ekran kilitli olsa bile veya başka uygulama açıkken göster
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Diğer uygulamaların üzerinde çıkması için
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

        val db = GuardianDatabase.getDatabase(applicationContext)
        repository = GuardianRepository(db.guardianDao())

        val blockedAppName = intent.getStringExtra("app_name") ?: "Hedef Uygulama"
        val blockedAppPackage = intent.getStringExtra("app_pkg") ?: ""

        setContent {
            MyApplicationTheme {
                val sessionState = repository.userSession.collectAsState(initial = null)
                val session = sessionState.value

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PureBlack
                ) {
                    BlockScreenContent(
                        appName = blockedAppName,
                        session = session,
                        onGoHome = {
                            // Minimize / send user back to launcher home
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(homeIntent)
                            finish()
                        },
                        onUnlockFailPenalty = {
                            serviceScope.launch {
                                repository.failActiveTarget()
                                runOnUiThread {
                                    android.widget.Toast.makeText(
                                        this@BlockActivity,
                                        "İraden yenildi! Seviyen sıfırlandı. Sosyal rehineler gönderildi.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    finish()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Geri tuşunu engelle: Kullanıcı geri tuşuna basarak blok ekranını kapatamaz.
     * Bunun yerine ana ekrana yönlendirilir.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        // finish() çağrılmıyor: Activity stack'te kalıyor,
        // kullanıcı hedef uygulamaya dönemez
    }

    override fun onDestroy() {
        super.onDestroy()
        // Flag sıfırla: Servis bir sonraki erişimde tekrar açabilsin
        BlockOverlayService.isBlockActivityShown = false
        serviceScope.cancel()
    }
}

@Composable
fun BlockScreenContent(
    appName: String,
    session: UserSessionEntity?,
    onGoHome: () -> Unit,
    onUnlockFailPenalty: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 60.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Engellendi",
                    tint = DangerRed,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "SÜRE DOLDU",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = DangerRed,
                        letterSpacing = 4.sp
                    )
                )
            }

            // Middle Info Card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "$appName",
                    color = PureWhite,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Bugün bu uygulama için ayırdığınız günlük süreyi doldurdunuz. İradenize sadık kalın ve uygulamayı kapatın.",
                    color = MutedGray,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }

            // Bottom Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp)
            ) {
                Button(
                    onClick = onGoHome,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PureWhite,
                        contentColor = PureBlack
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "UYGULAMADAN ÇIK",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

