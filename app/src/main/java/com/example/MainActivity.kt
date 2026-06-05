package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.*
import com.example.data.local.entity.RestrictedAppEntity
import com.example.data.local.entity.StatusLogEntity
import com.example.data.local.entity.UserSessionEntity
import com.example.BuildConfig
import com.example.ui.theme.*
import com.example.viewmodel.GuardianViewModel
import com.example.viewmodel.GuardianViewModelFactory
import com.example.service.BlockOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ROUTE_PERMISSIONS = "permissions"
const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_SETUP = "setup"
const val ROUTE_SETTINGS = "settings"

private const val CANCEL_HOLD_DURATION_MS = 5000L

@Composable
fun AppIconView(packageName: String, modifier: Modifier = Modifier) {
    if (packageName.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val context = LocalContext.current
    var iconDrawable by remember(packageName) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val icon = pm.getApplicationIcon(packageName)
                iconDrawable = icon
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    if (iconDrawable != null) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            },
            modifier = modifier,
            update = { imageView ->
                imageView.setImageDrawable(iconDrawable)
            }
        )
    } else {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageResource(android.R.drawable.sym_def_app_icon)
                }
            },
            modifier = modifier
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MatteSurface
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val viewModel: GuardianViewModel = viewModel(
        factory = GuardianViewModelFactory(context.applicationContext)
    )

    // Oturum canlılığını sağla
    LaunchedEffect(Unit) {
        viewModel.ensureServiceAlive(context.applicationContext)
    }

    MainNavigationContent(viewModel = viewModel)
}

@Composable
fun MainNavigationContent(
    viewModel: GuardianViewModel,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val context = LocalContext.current
    var isOverlayEnabled by remember { mutableStateOf(viewModel.hasOverlayPermission(context)) }
    var isUsageEnabled by remember { mutableStateOf(viewModel.hasUsageStatsPermission(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(viewModel.isAccessibilityServiceEnabled(context)) }
    var isBatteryExempted by remember { mutableStateOf(viewModel.isBatteryOptimizationIgnored(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            isOverlayEnabled = viewModel.hasOverlayPermission(context)
            isUsageEnabled = viewModel.hasUsageStatsPermission(context)
            isAccessibilityEnabled = viewModel.isAccessibilityServiceEnabled(context)
            isBatteryExempted = viewModel.isBatteryOptimizationIgnored(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    val hasAllPermissions = isOverlayEnabled && isUsageEnabled && isAccessibilityEnabled && isBatteryExempted

    LaunchedEffect(hasAllPermissions) {
        if (!hasAllPermissions) {
            if (currentRoute != ROUTE_PERMISSIONS) {
                navController.navigate(ROUTE_PERMISSIONS) {
                    popUpTo(0) { inclusive = true }
                }
            }
        } else if (currentRoute == ROUTE_PERMISSIONS) {
            navController.navigate(ROUTE_DASHBOARD) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (hasAllPermissions && (currentRoute == ROUTE_DASHBOARD || currentRoute == ROUTE_SETTINGS)) {
                NavigationBar(
                    containerColor = DarkCharcoal,
                    contentColor = PureWhite
                ) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                        label = { Text("Ana Ekran", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                        selected = currentRoute == ROUTE_DASHBOARD,
                        onClick = {
                            if (currentRoute != ROUTE_DASHBOARD) {
                                navController.navigate(ROUTE_DASHBOARD) {
                                    popUpTo(ROUTE_DASHBOARD) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PureWhite,
                            selectedTextColor = PureWhite,
                            indicatorColor = SoftDangerRed,
                            unselectedTextColor = MutedGray,
                            unselectedIconColor = MutedGray
                        )
                    )

                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings/Profile") },
                        label = { Text("Profil & Ayarlar", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                        selected = currentRoute == ROUTE_SETTINGS,
                        onClick = {
                            if (currentRoute != ROUTE_SETTINGS) {
                                navController.navigate(ROUTE_SETTINGS) {
                                    popUpTo(ROUTE_DASHBOARD) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PureWhite,
                            selectedTextColor = PureWhite,
                            indicatorColor = SoftDangerRed,
                            unselectedTextColor = MutedGray,
                            unselectedIconColor = MutedGray
                        )
                    )
                }
            }
        },
        containerColor = MatteSurface
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (hasAllPermissions) ROUTE_DASHBOARD else ROUTE_PERMISSIONS,
            modifier = modifier.padding(innerPadding)
        ) {
            composable(ROUTE_PERMISSIONS) {
                PermissionsScreen(
                    viewModel = viewModel,
                    isOverlayEnabled = isOverlayEnabled,
                    isUsageEnabled = isUsageEnabled,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isBatteryExempted = isBatteryExempted,
                    onNavigateToDashboard = {
                        navController.navigate(ROUTE_DASHBOARD) {
                            popUpTo(ROUTE_PERMISSIONS) { inclusive = true }
                        }
                    }
                )
            }

            composable(ROUTE_DASHBOARD) {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToSetup = { navController.navigate(ROUTE_SETUP) }
                )
            }

            composable(ROUTE_SETUP) {
                SetupTargetScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onCompleted = {
                        navController.navigate(ROUTE_DASHBOARD) {
                            popUpTo(ROUTE_DASHBOARD) { inclusive = true }
                        }
                    }
                )
            }

            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel
                )
            }
        }
    }
}

// ==========================================
// SCREEN 1: PERMISSIONS
// ==========================================
@Composable
fun PermissionsScreen(
    viewModel: GuardianViewModel,
    isOverlayEnabled: Boolean,
    isUsageEnabled: Boolean,
    isAccessibilityEnabled: Boolean,
    isBatteryExempted: Boolean,
    onNavigateToDashboard: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MatteSurface)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(SoftDangerRed),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = DangerRed,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "YETKİLENDİRME GEREKLİ",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = PureBlack,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Gardiyan'ın hedef uygulamayı güvenilir şekilde tespit edebilmesi, blok ekranını gösterebilmesi ve OEM batarya yönetiminden korunabilmesi için aşağıdaki dört sistem iznine ihtiyacı vardır.",
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            color = MutedGray,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderGray, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PermissionRow(
                    title = "Kullanım İstatistikleri",
                    description = "Hangi uygulamanın açıldığını yedek olarak tespit etmek için.",
                    isGranted = isUsageEnabled,
                    onClick = { viewModel.openUsageStatsSettings(context) }
                )

                HorizontalDivider(color = BorderGray, thickness = 1.dp)

                PermissionRow(
                    title = "Erişilebilirlik Servisi",
                    description = "Birincil uygulama tespiti. Anlık ve güvenilir çalışır.",
                    isGranted = isAccessibilityEnabled,
                    onClick = { viewModel.openAccessibilitySettings(context) }
                )

                HorizontalDivider(color = BorderGray, thickness = 1.dp)

                PermissionRow(
                    title = "Diğer Uygulamalar Üzerinde Gösterim",
                    description = "Korunma arayüzünü gösterebilmek için.",
                    isGranted = isOverlayEnabled,
                    onClick = { viewModel.openOverlaySettings(context) }
                )

                HorizontalDivider(color = BorderGray, thickness = 1.dp)

                PermissionRow(
                    title = "Pil Optimizasyonu Muafiyeti",
                    description = "OEM'lerin servisimizi öldürmesini önler. KRİTİK!",
                    isGranted = isBatteryExempted,
                    onClick = { viewModel.requestBatteryOptimizationIgnore(context) }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onNavigateToDashboard() },
            enabled = isOverlayEnabled && isUsageEnabled && isAccessibilityEnabled && isBatteryExempted,
            colors = ButtonDefaults.buttonColors(
                containerColor = PureWhite,
                contentColor = Color.White,
                disabledContainerColor = Color(0xFFDFDFDF),
                disabledContentColor = Color(0xFFAFAFAF)
            ),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                text = "KORUMA PANELİNİ BAŞLAT",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PureBlack)
            Text(text = description, fontSize = 10.sp, color = MutedGray)
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isGranted) Color(0xFFE2F0D9) else SoftDangerRed)
                .then(
                    if (!isGranted) Modifier.clickable { onClick() } else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (isGranted) "AKTİF" else "YETKİ VER",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isGranted) SuccessGreen else DangerRed,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ==========================================
// SCREEN 2: DASHBOARD — Modern Kontrol Paneli
// ==========================================
@Composable
fun DashboardScreen(
    viewModel: GuardianViewModel,
    onNavigateToSetup: () -> Unit
) {
    val session by viewModel.userSession.collectAsState()
    val restrictedApps by viewModel.restrictedApps.collectAsState()
    val activeApps = remember(restrictedApps) { restrictedApps.filter { it.isActive } }
    val isMonitoring by viewModel.isMonitoringActive.collectAsState()

    Scaffold(
        containerColor = MatteSurface
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // — HEADER —
            item {
                Spacer(modifier = Modifier.height(8.dp))
                DashboardHeader(session = session, activeCount = activeApps.size, isMonitoring = isMonitoring)
            }

            // — CTA: Yeni Kısıtlama Başlat —
            item {
                NewRestrictionCTA(onNavigateToSetup = onNavigateToSetup)
            }

            // — Aktif Kısıtlamalar Listesi —
            if (activeApps.isEmpty()) {
                item {
                    EmptyDashboardState()
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AKTİF KISITLAMALAR",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MutedGray,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(PureWhite.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${activeApps.size}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = PureWhite
                            )
                        }
                    }
                }

                items(activeApps, key = { it.id }) { app ->
                    ModernRestrictionCard(app = app)
                }

                // 5 saniye basılı tut iptal butonu
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    FiveSecondHoldCancelButton(
                        onCancelConfirmed = { viewModel.cancelAllWithFiveSecondHold() }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// — Dashboard Header —
@Composable
private fun DashboardHeader(
    session: UserSessionEntity?,
    activeCount: Int,
    isMonitoring: Boolean
) {
    val levelName = when (session?.level ?: 1) {
        1 -> "Çaylak"
        2 -> "Disiplinli"
        3 -> "Usta"
        else -> "Çaylak"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "GARDİYAN",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureBlack,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Dijital Koruma Paneli",
                    fontSize = 11.sp,
                    color = MutedGray
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isMonitoring) PureWhite.copy(alpha = 0.1f) else BorderGray)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isMonitoring) SuccessGreen else MutedGray)
                    )
                    Text(
                        text = if (isMonitoring) "AKTİF" else "PASİF",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isMonitoring) SuccessGreen else MutedGray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stat chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatChip(
                label = "Rütbe",
                value = levelName,
                modifier = Modifier.weight(1f)
            )
            StatChip(
                label = "Kilit",
                value = "$activeCount",
                modifier = Modifier.weight(1f)
            )
            StatChip(
                label = "Seri",
                value = "${session?.consecutiveSuccessDays ?: 0} gün",
                modifier = Modifier.weight(1f),
                accent = session?.hasRedBadge == true
            )
        }
    }
}

@Composable
private fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false
) {
    Card(
        modifier = modifier.border(1.dp, if (accent) DangerRed.copy(alpha = 0.3f) else BorderGray, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MutedGray,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (accent) DangerRed else PureBlack
            )
        }
    }
}

// — CTA Kartı —
@Composable
private fun NewRestrictionCTA(onNavigateToSetup: () -> Unit) {
    Card(
        onClick = onNavigateToSetup,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PureWhite.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = PureWhite.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PureWhite.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Yeni Kısıtlama Başlat",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureBlack
                )
                Text(
                    text = "Uygulama seç, süre belirle, korumayı etkinleştir",
                    fontSize = 11.sp,
                    color = MutedGray,
                    lineHeight = 15.sp
                )
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = PureWhite,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// — Boş Dashboard —
@Composable
private fun EmptyDashboardState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderGray, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(SuccessGreen.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Serbest ve Güvenli Sörf",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = PureBlack
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Aktif kısıtlama bulunmuyor. Yukarıdaki karttan yeni bir koruma başlatabilirsin.",
                fontSize = 12.sp,
                color = MutedGray,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

// — Modern Kısıtlama Kartı (Toggle YOK) —
@Composable
private fun ModernRestrictionCard(app: RestrictedAppEntity) {
    val totalSecs = app.remainingSecondsToday.coerceAtLeast(0)
    val isLocked = totalSecs <= 0

    // Süre kalan ilerleme (toplam günlük)
    val dailyProgress = if (app.dailyLimitMinutes > 0) {
        (app.remainingSecondsToday.toFloat() / (app.dailyLimitMinutes * 60).toFloat()).coerceIn(0f, 1f)
    } else 0f

    val mm = totalSecs / 60
    val ss = totalSecs % 60

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderGray, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Üst kısım: İkon + Ad + Durum
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // App icon with status ring
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { dailyProgress },
                        modifier = Modifier.size(52.dp),
                        color = if (isLocked) DangerRed else PureWhite,
                        trackColor = if (isLocked) DangerRed.copy(alpha = 0.15f) else BorderGray,
                        strokeWidth = 3.dp
                    )
                    AppIconView(
                        packageName = app.packageName,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureBlack
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Günlük limit: ${app.dailyLimitMinutes} dk",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MutedGray
                    )
                    if (app.activeDays.isNotEmpty() && app.activeDays != "Pzt,Sal,Çar,Per,Cum,Cmt,Paz") {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Günler: ${app.activeDays}",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MutedGray.copy(alpha = 0.8f)
                        )
                    }
                }

                // Durum badge'i
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                isLocked -> SoftDangerRed
                                app.isFailed -> SoftDangerRed
                                else -> SuccessGreen.copy(alpha = 0.1f)
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = when {
                            isLocked -> "KİLİTLİ"
                            app.isFailed -> "BAŞARISIZ"
                            else -> "KULLANILABİLİR"
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            isLocked -> DangerRed
                            app.isFailed -> DangerRed
                            else -> SuccessGreen
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Alt kısım: günlük süre ilerleme çubuğu
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LinearProgressIndicator(
                    progress = { dailyProgress },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (isLocked) DangerRed else PureWhite,
                    trackColor = BorderGray
                )
                Text(
                    text = String.format("%02d:%02d", mm, ss),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isLocked) DangerRed else PureBlack
                )
            }
        }
    }
}

/**
 * 5 saniye kesintisiz basılı tutma gerektiren iptal butonu.
 * pointerInput ile ACTION_DOWN anında coroutine başlatılır, ACTION_UP
 * anında iptal edilir. 5 saniye dolarsa reset tetiklenir.
 *
 * Basılı tutarken dairesel ilerleme göstergesi görsel geri bildirim sağlar.
 */
@Composable
private fun FiveSecondHoldCancelButton(
    onCancelConfirmed: () -> Unit
) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // Tamamlandığında sadece bir kere tetikle
    LaunchedEffect(completed) {
        if (completed) {
            Toast.makeText(context, "Kilitler kaldırıldı. Tüm kısıtlamalar pasif.", Toast.LENGTH_LONG).show()
            onCancelConfirmed()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DangerRed.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = DangerRed,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "TEHLİKELİ BÖLGE",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = DangerRed,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tüm kilitleri kaldırmak için butonu 5 saniye basılı tut.",
                fontSize = 11.sp,
                color = MutedGray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Kırmızı utanç rozeti ve level düşüşü uygulanır.",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MutedGray.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 5 saniye basılı tutma butonu (Box + pointerInput)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isHolding) DangerRed.copy(alpha = 0.2f) else SoftDangerRed)
                    .border(
                        width = if (isHolding) 2.dp else 1.dp,
                        color = DangerRed.copy(alpha = if (isHolding) 1f else 0.3f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            isHolding = true
                            progress = 0f
                            val steps = 50
                            val stepDelay = CANCEL_HOLD_DURATION_MS / steps
                            val timerJob = coroutineScope.launch {
                                for (i in 1..steps) {
                                    delay(stepDelay)
                                    if (!isHolding) return@launch
                                    progress = i / steps.toFloat()
                                }
                                completed = true
                                isHolding = false
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.changes.all { !it.pressed }) {
                                        isHolding = false
                                        progress = 0f
                                        timerJob.cancel()
                                        return@awaitEachGesture
                                    }
                                }
                            } finally {
                                timerJob.cancel()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // İlerleme çubuğu (soldan dolma)
                if (isHolding) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(DangerRed.copy(alpha = 0.3f))
                            .align(Alignment.CenterStart)
                    )
                }

                Text(
                    text = when {
                        completed -> "✓ TAMAMLANDI"
                        isHolding -> "BASILI TUTULUYOR · ${(progress * 5).toInt() + 1}s"
                        else -> "KİLİTLERİ KALDIR (5sn BASILI TUT)"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isHolding) DangerRed else DangerRed.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ==========================================
// SCREEN 3: SETUP - çoklu uygulama ekleme
// ==========================================
@Composable
fun SetupTargetScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit,
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val availableApps = remember { viewModel.getInstalledApps(context) }
    var selectedApp by remember { mutableStateOf<Pair<String, String>?>(null) }
    
    var selectedDurationPreset by remember { mutableStateOf(60) }
    var customDurationText by remember { mutableStateOf("") }
    
    val daysOfWeek = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
    var selectedDays by remember { mutableStateOf(daysOfWeek.toSet()) }

    var isAppSheetVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    
    val restrictedApps by viewModel.restrictedApps.collectAsState()

    val presetChoices = listOf(
        Pair("Test (10sn)", 0),
        Pair("15 Dk", 15),
        Pair("30 Dk", 30),
        Pair("1 Saat", 60),
        Pair("2 Saat", 120)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MatteSurface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri", tint = PureBlack)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "KISITLAMA EKLE",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = PureBlack
                    )
                }
            }

            // Uygulama seçici kartı
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderGray, RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SoftDangerRed),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                text = "HANGİ UYGULAMAYI KISITLAMAK İSTERSİN?",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MutedGray,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MatteSurface)
                                .border(1.dp, BorderGray, RoundedCornerShape(12.dp))
                               .clickable { 
                                   searchQuery = ""
                                   isAppSheetVisible = true 
                               }
                                .padding(14.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (selectedApp != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        AppIconView(packageName = selectedApp!!.second, modifier = Modifier.size(32.dp))
                                        Column {
                                            Text(text = selectedApp!!.first, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PureBlack)
                                            Text(text = selectedApp!!.second, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MutedGray)
                                        }
                                    }
                                } else {
                                    Text("Bir uygulama seçmek için dokun...", fontSize = 13.sp, color = MutedGray)
                                }
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MutedGray
                                )
                            }
                        }
                    }
                }
            }

            // Süre seçici kartı
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderGray, RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SoftDangerRed),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.List, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                text = "GÜNLÜK SERBEST SÜRE SEÇİN",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MutedGray,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Yatay Kaydırılabilir ChoiceChip Listesi
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(presetChoices) { choice ->
                                val isSelected = selectedDurationPreset == choice.second && customDurationText.isEmpty()
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (isSelected) PureWhite else MatteSurface)
                                        .border(1.dp, if (isSelected) PureWhite else BorderGray, RoundedCornerShape(999.dp))
                                        .clickable {
                                            selectedDurationPreset = choice.second
                                            customDurationText = ""
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = choice.first,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else PureBlack
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Manuel Süre Girişi TextField
                        OutlinedTextField(
                            value = customDurationText,
                            onValueChange = { newValue ->
                                if (newValue.all { it.isDigit() }) {
                                    customDurationText = newValue
                                }
                            },
                            label = { Text("Özel süre gir (dakika)", color = MutedGray, fontSize = 12.sp) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PureWhite,
                                unfocusedBorderColor = BorderGray,
                                focusedContainerColor = MatteSurface,
                                unfocusedContainerColor = MatteSurface,
                                focusedTextColor = PureBlack,
                                unfocusedTextColor = PureBlack
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Gün seçici kartı (YENİ)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderGray, RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SoftDangerRed),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.DateRange, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                text = "KORUMA HANGİ GÜNLER AKTİF OLSUN?",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MutedGray,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Yuvarlak Gün Seçim Butonları
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            daysOfWeek.forEach { day ->
                                val isSelected = selectedDays.contains(day)
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) PureWhite else MatteSurface)
                                        .border(1.dp, if (isSelected) PureWhite else BorderGray, CircleShape)
                                        .clickable {
                                            selectedDays = if (isSelected) {
                                                selectedDays - day
                                            } else {
                                                selectedDays + day
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = day,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else PureBlack
                                    )
                                }
                            }
                        }
                    }
                }
            }



            // Ekleme ve bitirme butonları
            item {
                val finalDuration = if (customDurationText.isNotEmpty()) {
                    customDurationText.toIntOrNull() ?: selectedDurationPreset
                } else {
                    selectedDurationPreset
                }
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val sel = selectedApp
                            if (sel == null) {
                                Toast.makeText(context, "Önce bir uygulama seçin", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (selectedDays.isEmpty()) {
                                Toast.makeText(context, "Lütfen en az bir gün seçin", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val daysStr = selectedDays.joinToString(",")
                            if (finalDuration == 0) {
                                viewModel.startQuickTest(context, sel.second, sel.first, testSeconds = 10, activeDays = daysStr)
                                Toast.makeText(context, "${sel.first} için hızlı test başlatıldı!", Toast.LENGTH_SHORT).show()
                                onCompleted()
                            } else {
                                viewModel.addRestrictedApp(sel.second, sel.first, finalDuration, activeDays = daysStr)
                                Toast.makeText(context, "${sel.first} eklendi", Toast.LENGTH_SHORT).show()
                                selectedApp = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SoftDangerRed, contentColor = DangerRed),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (finalDuration == 0) "HIZLI TESTİ BAŞLAT (10sn)" else "KISITLAMAYI AKTİFLEŞTİR",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    }

                    if (restrictedApps.any { it.isActive }) {
                        Button(
                            onClick = onCompleted,
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text(
                                text = "BİTİR / PANELİ AÇ",
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

        // Custom Uygulama Arama/Seçim Bottom Sheet Overlay
        if (isAppSheetVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { isAppSheetVisible = false }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(DarkCharcoal)
                    .border(1.dp, BorderGray, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .clickable(enabled = false) {}
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Drag Indicator
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(BorderGray)
                            .align(Alignment.CenterHorizontally)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "UYGULAMA SEÇİN",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = PureBlack
                        )
                        IconButton(onClick = { isAppSheetVisible = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Kapat", tint = PureBlack)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Arama Kutusu
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Uygulama adı veya paket adı ara...", color = MutedGray, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MutedGray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PureWhite,
                            unfocusedBorderColor = BorderGray,
                            focusedContainerColor = MatteSurface,
                            unfocusedContainerColor = MatteSurface,
                            focusedTextColor = PureBlack,
                            unfocusedTextColor = PureBlack
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Filtrelenmiş Uygulamalar Listesi
                    val filteredApps = remember(searchQuery, availableApps) {
                        if (searchQuery.isBlank()) {
                            availableApps
                        } else {
                            availableApps.filter {
                                it.first.contains(searchQuery, ignoreCase = true) ||
                                it.second.contains(searchQuery, ignoreCase = true)
                            }
                        }
                    }
                    
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredApps) { app ->
                            val isSelected = selectedApp?.second == app.second
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) SoftDangerRed else Color.Transparent)
                                    .clickable {
                                        selectedApp = app
                                        isAppSheetVisible = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AppIconView(packageName = app.second, modifier = Modifier.size(36.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.first,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = PureBlack
                                    )
                                    Text(
                                        text = app.second,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MutedGray
                                    )
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 4: SETTINGS
// ==========================================
@Composable
fun SettingsScreen(
    viewModel: GuardianViewModel
) {
    val session by viewModel.userSession.collectAsState()
    val logs by viewModel.allLogs.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MatteSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "AYARLAR VE PROFİL",
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = PureBlack
            )
        }

        // Rozet ve level
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderGray, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val levelName = when (session?.level ?: 1) {
                        1 -> "ÇAYLAK"
                        2 -> "DİSİPLİNLİ"
                        3 -> "USTA"
                        else -> "ÇAYLAK"
                    }
                    Text(
                        text = "Rütbe: $levelName",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (session?.hasRedBadge == true) {
                        Text(
                            text = "🔴 Kırmızı Utanç Rozeti: AKTİF (${session?.activeRedemptionDaysLeft ?: 0} gün telafi kaldı)",
                            fontSize = 11.sp,
                            color = DangerRed,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            text = "✓ Rozet yok. Tertemizsin.",
                            fontSize = 11.sp,
                            color = SuccessGreen,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Ardışık başarı: ${session?.consecutiveSuccessDays ?: 0} gün",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MutedGray
                    )
                }
            }
        }

        // Loglar (son 10) - Zaman Tüneli
        if (logs.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderGray, RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ZAMAN TÜNELİ",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MutedGray,
                            letterSpacing = 0.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
                        val logsToShow = logs.take(10)
                        
                        logsToShow.forEachIndexed { index, log ->
                            val timeStr = timeFormat.format(java.util.Date(log.timestamp))
                            
                            val title = when (log.eventType) {
                                "RESTRICTION_ADDED" -> "${log.appName} kısıtlaması eklendi"
                                "RESTRICTION_REMOVED" -> "${log.appName} kısıtlaması kaldırıldı"
                                "QUICK_TEST_STARTED" -> "${log.appName} hızlı test başlatıldı"
                                "RESTRICTION_RESET" -> "${log.appName} sayacı sıfırlandı"
                                "RESET_HOLD_5S" -> "Tüm kilitler kaldırıldı"
                                "FAILURE" -> "${log.appName} limiti aşıldı!"
                                "SUCCESS" -> "Günlük başarı sağlandı!"
                                else -> log.eventType
                            }

                            val iconColor = when (log.eventType) {
                                "FAILURE", "RESET_HOLD_5S" -> DangerRed
                                "SUCCESS" -> SuccessGreen
                                "RESTRICTION_ADDED", "QUICK_TEST_STARTED" -> PureWhite
                                else -> MutedGray
                            }

                            val isLast = index == logsToShow.size - 1

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Sol Timeline Çizgisi ve Noktası
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(24.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(iconColor)
                                    )
                                    if (!isLast) {
                                        Box(
                                            modifier = Modifier
                                                .width(1.5.dp)
                                                .height(44.dp)
                                                .background(BorderGray)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                // Sağ İçerik
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PureBlack,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = timeStr,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MutedGray
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = log.details,
                                        fontSize = 11.sp,
                                        color = MutedGray,
                                        lineHeight = 15.sp
                                    )
                                    if (!isLast) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ==========================================
// Header (eski — artık DashboardHeader kullanılıyor, geriye uyumluluk)
// ==========================================
@Composable
fun HeaderSection(session: UserSessionEntity?) {
    // DashboardHeader artık ana header. Bu fonksiyon geriye uyumluluk için korunuyor.
}
