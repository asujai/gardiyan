package com.example

import android.content.Intent
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
        
        // Servis tarafından birden fazla açılma engeli
        BlockOverlayService.isBlockActivityShown = true

        // Anti-Screen Capture (FLAG_SECURE) applied for MVP Observer Mode privacy guarantee
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

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

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun BlockScreenContent(
    appName: String,
    session: UserSessionEntity?,
    onGoHome: () -> Unit,
    onUnlockFailPenalty: () -> Unit
) {
    val context = LocalContext.current
    var isHoldUiVisible by remember { mutableStateOf(false) }

    // Hold to bypass state variables
    var holdDurationMs by remember { mutableStateOf(0L) }
    var isPressing by remember { mutableStateOf(false) }

    val maxHoldTimeMs = 60000L
    val targetTimeMs = maxHoldTimeMs
    val progress = (holdDurationMs.toFloat() / targetTimeMs).coerceIn(0f, 1f)

    val coroutineScope = rememberCoroutineScope()
    var timerJob = remember<Job?> { null }

    // Clean up timer job if recomposed out of pressing states
    LaunchedEffect(isPressing) {
        if (isPressing) {
            val stepTime = 50L
            timerJob = launch {
                while (holdDurationMs < targetTimeMs) {
                    delay(stepTime)
                    holdDurationMs += stepTime
                }
                // If we reach the target time, trigger unlock failure action
                onUnlockFailPenalty()
            }
        } else {
            timerJob?.cancel()
            holdDurationMs = 0L
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!isHoldUiVisible) {
            // Main Locked Layout
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxSize()
            ) {
                // Header (Brutalist Strict Style)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Engellendi",
                        tint = DangerRed,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "İRADE DUVARI",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = DangerRed,
                            letterSpacing = 4.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "DİJİTAL GARDİYAN SİSTEMİ",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MutedGray,
                            letterSpacing = 2.sp
                        )
                    )
                }

                // Middle Info Card
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "\"$appName\" Engel Altında",
                        color = PureWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "Profil rütbenizin ve koyduğunuz hedefin kilitli yapısı gereği bu uygulamaya erişiminiz GARDİYAN tarafından engellenmiştir.",
                        color = MutedGray,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    if (session != null && session.isObserverMode) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SoftDangerRed)
                                .border(1.dp, DangerRed, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "⚠️ GÖZETMEN MODU AKTİF",
                                    color = DangerRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Kilidi kırmak veya pes etmek, 'Kırmızı Utanç Rozeti' almanıza ve seçtiğiniz şu utanç verici içeriğin arkadaşınıza (${session.observerContactName}) hediye edilmesine sebep olacaktır:",
                                    color = PureWhite,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 14.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "\"${session.shameMessage.ifEmpty { "Utanç Verici Secret" }}\"",
                                    color = DangerRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Bottom Buttons
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
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
                            .height(50.dp)
                    ) {
                        Text(
                            text = "HEDEFE SADIK KAL (UYGULAMADAN ÇIK)",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(
                        onClick = { isHoldUiVisible = true },
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = "KİLİDİ KIR (PES ET)",
                            color = MutedGray,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        } else {
            // Intense hold to unlock screen panel
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 20.dp)
            ) {
                // Warning text
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PES ETME DİSİPLİN SINAVI",
                        color = DangerRed,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Parmağını aşağıdaki butona basılı tut. Kesintisiz 60 saniye boyunca butonun üzerinde kalmalısın. Parmağını anlık çekersen süre tamamen sıfırlanır.",
                        color = MutedGray,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // Interactive Circle hold trigger zone
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(240.dp)
                            .clip(CircleShape)
                            .background(if (isPressing) SoftDangerRed else Color(0xFF0C0C0C))
                            .border(2.dp, if (isPressing) DangerRed else Color(0xFF1E1E1E), CircleShape)
                            // Custom touch listner
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        isPressing = true
                                        try {
                                            awaitRelease()
                                        } finally {
                                            isPressing = false
                                        }
                                    }
                                )
                            }
                    ) {
                        // Circular progress overlay
                        CircularProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            color = DangerRed,
                            strokeWidth = 6.dp,
                            trackColor = Color.Transparent,
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (isPressing) "HEDEF BOZULUYOR" else "DOKUN VE BEKLE",
                                color = if (isPressing) DangerRed else PureWhite,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val secondsLeft = (targetTimeMs - holdDurationMs) / 1000f
                            Text(
                                text = String.format("%.1f Sn", secondsLeft.coerceIn(0f, targetTimeMs / 1000f)),
                                color = PureWhite,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Kalan",
                                color = MutedGray,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    if (!isPressing && holdDurationMs == 0L) {
                        Text(
                            text = "[ PARMAĞINI BUTONDAN HİÇ ÇEKME ]",
                            color = MutedGray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else if (isPressing) {
                        Text(
                            text = "🔥 VAZGEÇMEK İÇİN BASILI TUTUYORSUN...",
                            color = DangerRed,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Back action Column
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { isHoldUiVisible = false },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E1E1E),
                            contentColor = PureWhite
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "GÖREVE GERİ DÖN",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
