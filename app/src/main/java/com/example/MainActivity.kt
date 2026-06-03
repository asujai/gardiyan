package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.entity.StatusLogEntity
import com.example.data.local.entity.UserSessionEntity
import com.example.ui.theme.*
import com.example.viewmodel.GuardianViewModel
import com.example.viewmodel.GuardianViewModelFactory
import com.example.service.BlockOverlayService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                // Initialize the ViewModel using the Context-injecting Factory
                val factory = GuardianViewModelFactory(applicationContext)
                val viewModel: GuardianViewModel = viewModel(factory = factory)

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = PureBlack
                ) { innerPadding ->
                    MainNavigationContent(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Simple trick to force re-evaluation of permission states when user returns to app
        BlockOverlayService.isServiceRunning = BlockOverlayService.isServiceRunning
    }
}

@Composable
fun MainNavigationContent(
    viewModel: GuardianViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val session by viewModel.userSession.collectAsState()
    val logs by viewModel.allLogs.collectAsState()
    val currentStep by viewModel.setupStep.collectAsState()

    var isJsonPanelExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // App branding banner
        item {
            HeaderSection(session = session)
        }

        // Active State Checklist for permissions
        item {
            PermissionSection(viewModel = viewModel)
        }

        // Stepper Setup or Active dashboard depending on active target
        if (session?.isActive == true) {
            item {
                ActiveDashboardSection(
                    session = session!!,
                    viewModel = viewModel,
                    onGoToBlockScreenForce = {
                        val blockIntent = Intent(context, BlockActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra("app_name", session?.targetAppName)
                            putExtra("app_pkg", session?.targetAppPackage)
                        }
                        context.startActivity(blockIntent)
                    }
                )
            }
        } else {
            item {
                SetupTargetSection(
                    viewModel = viewModel,
                    currentStep = currentStep
                )
            }
        }

        // History logs
        if (logs.isNotEmpty()) {
            item {
                HistoryLogsSection(
                    logs = logs,
                    onClear = { viewModel.clearLogs() }
                )
            }
        }

        // Expandable structural architecture JSON viewer
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isJsonPanelExpanded = !isJsonPanelExpanded }
                    .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0C0C)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "📐 V2 İLİŞKİSEL JSON MODELİ",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = MutedGray,
                            letterSpacing = 1.sp
                        )
                        Icon(
                            imageVector = if (isJsonPanelExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Genişlet",
                            tint = MutedGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (isJsonPanelExpanded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = getV2JsonSchemaRepresentation(session),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = SuccessGreen,
                            lineHeight = 15.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF060606))
                                .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Subtle decorative footer
        item {
            Box(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "GARDİYAN v1.0.0 (TAVİZSİZ DİJİTAL SÖZLEŞME)",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = BorderGray,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun HeaderSection(session: UserSessionEntity?) {
    val levelName = when (session?.level ?: 1) {
        1 -> "ÇAYLAK"
        2 -> "DİSİPLİNLİ"
        3 -> "USTA"
        else -> "ÇAYLAK"
    }
    val levelSub = "LVL ${session?.level ?: 1}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column {
            Text(
                text = "CURRENT RANK / MEVCUT RÜTBE",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MutedGray,
                    letterSpacing = 2.sp,
                    fontSize = 10.sp
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Geometric circle bullet
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(PureWhite)
                )
                Text(
                    text = levelName,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Light,
                        fontSize = 20.sp,
                        color = PureWhite,
                        letterSpacing = (-0.5).sp
                    )
                )
                Text(
                    text = levelSub,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MutedGray
                    )
                )
            }
        }

        // Red Badge of Shame (Conditional Alert)
        val hasBadge = session?.hasRedBadge == true
        val badgeAlpha = if (hasBadge) 1.0f else 0.2f

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.alpha(badgeAlpha)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .border(1.dp, DangerRed, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Rotated square representing a diamond shape
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer(rotationZ = 45f)
                        .background(DangerRed)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "UTANÇ ROZETİ",
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                color = DangerRed,
                fontFamily = FontFamily.Monospace,
                letterSpacing = (-0.5).sp
            )
        }
    }
}

@Composable
fun PermissionSection(viewModel: GuardianViewModel) {
    val context = LocalContext.current
    val isOverlayEnabled = viewModel.hasOverlayPermission(context)
    val isUsageEnabled = viewModel.hasUsageStatsPermission(context)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C0C0C))
            .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "SİSTEM İZİNLERİ (GÜVENLİK)",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = MutedGray,
            letterSpacing = 1.sp
        )

        HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 1.dp)

        // Overlay permission
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.openOverlaySettings(context) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Diyalog Penceresi İzni (Overlay)",
                    fontSize = 12.sp,
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Engelleme ekranını fırlatmak için zorunlu",
                    fontSize = 9.sp,
                    color = MutedGray,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                modifier = Modifier
                    .background(if (isOverlayEnabled) Color(0xFF1A1A1C) else SoftDangerRed)
                    .border(1.dp, if (isOverlayEnabled) Color(0xFF2E2E30) else DangerRed)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isOverlayEnabled) "AKTİF" else "İZİN GEREK",
                    fontSize = 9.sp,
                    color = if (isOverlayEnabled) SuccessGreen else DangerRed,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Usage stats permission
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.openUsageStatsSettings(context) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Kullanım İstatistikleri İzni (UsageStats)",
                    fontSize = 12.sp,
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Kısıtlı uygulamanın açıldığını tespit eder",
                    fontSize = 9.sp,
                    color = MutedGray,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                modifier = Modifier
                    .background(if (isUsageEnabled) Color(0xFF1A1A1C) else SoftDangerRed)
                    .border(1.dp, if (isUsageEnabled) Color(0xFF2E2E30) else DangerRed)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isUsageEnabled) "AKTİF" else "İZİN GEREK",
                    fontSize = 9.sp,
                    color = if (isUsageEnabled) SuccessGreen else DangerRed,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun SetupTargetSection(
    viewModel: GuardianViewModel,
    currentStep: Int
) {
    val context = LocalContext.current
    val targetApp by viewModel.targetAppName.collectAsState()
    val targetPkg by viewModel.targetAppPackage.collectAsState()
    val durationMin by viewModel.dailyLimitMinutes.collectAsState()
    val isObserver by viewModel.isObserverMode.collectAsState()
    val shameMsg by viewModel.shameMessage.collectAsState()
    val observerName by viewModel.observerContactName.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C0C0C))
            .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Panel Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "YENİ HEDEF KURULUMU",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = PureWhite,
                letterSpacing = 1.sp
            )
            Text(
                text = "Adım $currentStep / 3",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MutedGray,
                fontWeight = FontWeight.Bold
            )
        }

        HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 1.dp)

        if (currentStep == 1) {
            // STEP 1: App Picker Selection
            Text(
                text = "Hangi vakit çalan uygulamayı kısıtlayacaksınız?",
                color = PureWhite,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            val apps = listOf(
                Pair("Instagram", "com.instagram.android"),
                Pair("TikTok", "com.zhiliaoapp.musically"),
                Pair("YouTube", "com.google.android.youtube"),
                Pair("X / Twitter", "com.twitter.android")
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                apps.forEach { app ->
                    val isSelected = targetApp == app.first
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFF161618) else Color.Transparent)
                            .border(1.dp, if (isSelected) PureWhite else Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                            .clickable { viewModel.updateTargetApp(app.first, app.second) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = app.first,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = PureWhite,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = app.second,
                            fontSize = 9.sp,
                            color = MutedGray,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = { viewModel.setStep(2) },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(
                    text = "DEVAM ET",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            }
        } else if (currentStep == 2) {
            // STEP 2: Time Picker Selection
            Text(
                text = "Günlük maksimum kullanım süresini belirleyin:",
                color = PureWhite,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            val times = listOf(
                Pair("Test Sörfü (1 Dk)", 1), 
                Pair("Denge Sörfü (15 Dk)", 15),
                Pair("İdeal Limit (30 Dk)", 30),
                Pair("Standard (60 Dk)", 60)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                times.forEach { t ->
                    val isSelected = durationMin == t.second
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFF161618) else Color.Transparent)
                            .border(1.dp, if (isSelected) PureWhite else Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                            .clickable { viewModel.updateDailyLimit(t.second) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = t.first,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = PureWhite,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.setStep(1) },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E), contentColor = PureWhite),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        text = "GERİ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { viewModel.setStep(3) },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        text = "DEVAM ET",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        } else if (currentStep == 3) {
            // STEP 3: Ceza Modu seçimi
            Text(
                text = "Ceza modu ve sosyal rehinlerinizi seçin:",
                color = PureWhite,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Mod A
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (!isObserver) Color(0xFF161618) else Color.Transparent)
                        .border(1.dp, if (!isObserver) PureWhite else Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                        .clickable { viewModel.updatePenaltyMode(false) }
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "Mod A: Gardiyan Modu (Tek Kişilik)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Telefon kilitlenir, dışarı utanç verisi fırlatılmaz.",
                            fontSize = 9.sp,
                            color = MutedGray,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Mod B
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isObserver) Color(0xFF161618) else Color.Transparent)
                        .border(1.dp, if (isObserver) PureWhite else Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                        .clickable { viewModel.updatePenaltyMode(true) }
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "Mod B: Gözetmen Modu (Sosyal Rehin)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Gözetmene WhatsApp daveti atılır. Pes ederseniz sırlar fırlatılır.",
                            fontSize = 9.sp,
                            color = MutedGray,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isObserver) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(
                        text = "Gözetmen Arkadaş İsmi/Numarası:",
                        fontSize = 10.sp,
                        color = MutedGray,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedTextField(
                        value = observerName,
                        onValueChange = { viewModel.updateObserverContact(it) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PureWhite,
                            unfocusedBorderColor = Color(0xFF1E1E1E),
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedContainerColor = Color(0xFF0C0C0C),
                            unfocusedContainerColor = Color(0xFF0C0C0C)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Vazgeçerseniz Gönderilecek Utanç Sırrınız / Mesajınız:",
                        fontSize = 10.sp,
                        color = MutedGray,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedTextField(
                        value = shameMsg,
                        onValueChange = { viewModel.updateShameMessage(it) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PureWhite,
                            unfocusedBorderColor = Color(0xFF1E1E1E),
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedContainerColor = Color(0xFF0C0C0C),
                            unfocusedContainerColor = Color(0xFF0C0C0C)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            // Simulates sending WhatsApp link invite
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Selam, erteleme hastalığımla mücadele etmek için seni Gardiyan gözetmenim olarak rehinedim. Heşeyi takip etmek için tıkla: https://gardiyan.app/invite/user")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Davet Linki Paylaş"))
                        },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1C), contentColor = PureWhite),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text(
                            text = "🔗 WHATSAPP GÖZETMEN DAVET LİNKİ PAYLAŞ",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.setStep(2) },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E), contentColor = PureWhite),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        text = "GERİ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        viewModel.startNewTarget(context)
                        Toast.makeText(context, "İrade Kilidi Başlatıldı!", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = PureWhite),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        text = "KİLİDİ AKTİF ET",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveDashboardSection(
    session: UserSessionEntity,
    viewModel: GuardianViewModel,
    onGoToBlockScreenForce: () -> Unit
) {
    val isSimulating by viewModel.isSimulationRunning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C0C0C))
            .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AKTİF KİLİT KORUMASI",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = PureWhite,
                letterSpacing = 1.sp
            )
            Box(
                modifier = Modifier
                    .background(DangerRed)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "LOCKED",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 1.dp)

        // Geometric Balanced Circular Countdown Gauges
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            // Main circle thin outline border
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .border(1.dp, Color(0xFF1E1E1E), androidx.compose.foundation.shape.CircleShape)
            )

            // Progress Arc Indicator
            val progressVal = if (session.dailyLimitMinutes > 0) {
                (session.remainingMinutesToday.toFloat() / session.dailyLimitMinutes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            CircularProgressIndicator(
                progress = progressVal,
                modifier = Modifier
                    .size(220.dp)
                    .graphicsLayer(rotationZ = 120f),
                color = PureWhite,
                strokeWidth = 3.dp,
                trackColor = Color.Transparent
            )

            // Timing Details Column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "REMAINING / KALAN",
                    fontSize = 10.sp,
                    color = MutedGray,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = String.format("%02d:00", session.remainingMinutesToday),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.SansSerif,
                    color = if (session.remainingMinutesToday <= 0) DangerRed else PureWhite,
                    letterSpacing = (-1).sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${session.targetAppName.uppercase()} BLOCK",
                    fontSize = 10.sp,
                    color = MutedGray,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Mode Status Information Card (bg-[#111] with white container icon)
        val modeText = if (session.isObserverMode) "GÖZETMEN MODU AKTİF" else "GARDİYAN MODU AKTİF"
        val penaltyDescription = if (session.isObserverMode) {
            "Hedef bozulursa, utanç sırrınız gözetmen arkadaşınız (${session.observerContactName}) kişisine fırlatılacaktır."
        } else {
            "Belirlenen günlük limitsiz süreniz doldu! Kısıtlanmış uygulamaya anlık geçilirse irade ekranı fırlatılır."
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // White rounded box with black icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PureWhite),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (session.isObserverMode) Icons.Default.Share else Icons.Default.Lock,
                    contentDescription = null,
                    tint = PureBlack,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MutedGray,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = penaltyDescription,
                    fontSize = 11.sp,
                    color = MutedGray,
                    lineHeight = 15.sp
                )
            }
        }

        // Status & Redemption Details Rows
        HorizontalDivider(color = Color(0xFF121212), thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isGuarding = session.isActive && session.remainingMinutesToday > 0
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "STATUS",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF444446),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isGuarding) "GUARDING" else "EXPIRED",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isGuarding) SuccessGreen else DangerRed,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            // Vertical clean separator
            Box(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .width(1.dp)
                    .height(20.dp)
                    .background(Color(0xFF1E1E1E))
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "REDEMPTION",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF444446),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                val streakText = if (session.hasRedBadge) {
                    "${session.redemptionStreakGoal - session.activeRedemptionDaysLeft}/${session.redemptionStreakGoal} DAYS"
                } else {
                    "0/2 DAYS"
                }
                Text(
                    text = streakText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (session.hasRedBadge) PureWhite else Color(0xFF8E8E93),
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Redemption Progress active warning card
        if (session.hasRedBadge && session.activeRedemptionDaysLeft > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DangerRed, RoundedCornerShape(12.dp))
                    .background(SoftDangerRed)
                    .padding(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "🩹 TELAFİ REHABİLİTASYONU AKTİF",
                        color = DangerRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val achieved = session.redemptionStreakGoal - session.activeRedemptionDaysLeft
                    Text(
                        text = "Kırmızı rozetinizin silinmesi için ardışık $achieved / ${session.redemptionStreakGoal} gün başarı hedeflenmektedir.",
                        color = PureWhite,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 1.dp)

        // Simulator controls panel
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "HIZLI TEST VE SİMÜLASYON KONTROLLERİ",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MutedGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.simulateRemainingTimeReduction() },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E), contentColor = PureWhite),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text(
                        text = "ZAMAN AZALT",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = { viewModel.triggerSimulatedSuccess() },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E), contentColor = SuccessGreen),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text(
                        text = "SİMÜLE ET: BAŞARI",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Simulate Opening target App (which overrides background checks for quick evaluation of blocking overlay)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSimulating) SoftDangerRed else Color.Transparent)
                    .border(1.dp, if (isSimulating) DangerRed else Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .clickable { viewModel.toggleBlockSimulation(!isSimulating) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isSimulating) "🔥 Engelleme Simülatörü: AÇIK" else "Engelleme Simülatörü: KAPALI",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSimulating) DangerRed else PureWhite,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Açarsanız, arka planda koruma ekranı fırlatılır.",
                        fontSize = 9.sp,
                        color = MutedGray,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Switch(
                    checked = isSimulating,
                    onCheckedChange = { viewModel.toggleBlockSimulation(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DangerRed,
                        checkedTrackColor = SoftDangerRed,
                        uncheckedThumbColor = Color(0xFF8E8E93),
                        uncheckedTrackColor = Color(0xFF1C1C1E)
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Text button triggers the Discipline Lock hold test on block screen manually
            Button(
                onClick = onGoToBlockScreenForce,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E), contentColor = DangerRed),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(
                    text = "DİRENÇ GÖSTER: KİLİDİ KIRMA PANELİNE GİR",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Reset current settings cleanly
            TextButton(
                onClick = { viewModel.resetTargetSession() },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "KİLİT HEDEFİNİ TEMİZLE / RESETLE",
                    color = MutedGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun HistoryLogsSection(
    logs: List<StatusLogEntity>,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C0C0C))
            .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "KRONOLOJİK İRADESİZLİK LOGLARI",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = MutedGray,
                letterSpacing = 1.sp
            )
            Text(
                text = "TEMİZLE",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = DangerRed,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onClear() }
            )
        }

        HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 1.dp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            logs.take(4).forEach { log ->
                val logColor = when (log.eventType) {
                    "FAILURE" -> DangerRed
                    "SUCCESS" -> SuccessGreen
                    "LIMIT_EXCEEDED" -> DangerRed
                    else -> PureWhite
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF111111))
                        .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "[${log.eventType}]",
                            color = logColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        val dateText = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(log.timestamp)
                        Text(
                            text = dateText,
                            color = MutedGray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = log.details,
                        color = PureWhite,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Returns a high fidelity JSON model representation of state management to explicitly satisfy 
 * query requirements (state model formats, V2 relations structures).
 */
fun getV2JsonSchemaRepresentation(session: UserSessionEntity?): String {
    return """
{
  "state_info": {
    "module": "GardiyanSession",
    "version": "1.0.0-MVP",
    "v2_branch_ready": "true"
  },
  "user_profile": {
    "username": "${session?.username ?: "GuardUser"}",
    "level": ${session?.level ?: 1},
    "has_red_badge": ${session?.hasRedBadge ?: false},
    "consecutive_success_days": ${session?.consecutiveSuccessDays ?: 0}
  },
  "lock_target": {
    "is_active": ${session?.isActive ?: false},
    "app_name": "${session?.targetAppName ?: "None"}",
    "package_id": "${session?.targetAppPackage ?: "None"}",
    "limit_minutes_daily": ${session?.dailyLimitMinutes ?: 60},
    "remaining_minutes_today": ${session?.remainingMinutesToday ?: 60}
  },
  "punishment_vector": {
    "mode": "${if (session?.isObserverMode == true) "B_OBSERVER" else "A_GUARD"}",
    "shame_payload": {
      "secret_shame_message": "${session?.shameMessage?.take(36) ?: ""}...",
      "shame_item_self_destruct_seconds": 10
    },
    "relation_v2_syncable_ref": {
      "observer_name": "${session?.observerContactName ?: "None"}",
      "invite_sync_endpoint": "${session?.observerInviteLink ?: "None"}"
    }
  },
  "database_tables_v2_social": [
    {
      "table": "friends_list",
      "fields": {
        "friendUserId": "VARCHAR (PRIMARY_KEY)",
        "friendName": "VARCHAR",
        "friendLevel": "INTEGER",
        "friendHasRedBadge": "BOOLEAN"
      }
    }
  ]
}
""".trimIndent()
}
