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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.data.local.entity.StatusLogEntity
import com.example.data.local.entity.UserSessionEntity
import com.example.ui.theme.*
import com.example.viewmodel.GuardianViewModel
import com.example.viewmodel.GuardianViewModelFactory
import com.example.service.BlockOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val ROUTE_PERMISSIONS = "permissions"
const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_SETUP = "setup"
const val ROUTE_SETTINGS = "settings"

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
                val factory = GuardianViewModelFactory(applicationContext)
                val viewModel: GuardianViewModel = viewModel(factory = factory)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MatteSurface
                ) {
                    MainNavigationContent(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!BlockOverlayService.isServiceRunning) {
            val viewModelFactory = GuardianViewModelFactory(applicationContext)
            val vm = androidx.lifecycle.ViewModelProvider(this, viewModelFactory)[GuardianViewModel::class.java]
            vm.restartServiceIfNeeded(applicationContext)
        }
    }
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

    LaunchedEffect(Unit) {
        while (true) {
            isOverlayEnabled = viewModel.hasOverlayPermission(context)
            isUsageEnabled = viewModel.hasUsageStatsPermission(context)
            delay(1000)
        }
    }

    val hasAllPermissions = isOverlayEnabled && isUsageEnabled

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
                    tonalElevation = 6.dp,
                    modifier = Modifier.border(width = 1.dp, color = BorderGray)
                ) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                        label = { Text("Ana Ekran", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
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
                        label = { Text("Profil & Ayarlar", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
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
// SCREEN 1: SYSTEM PERMISSIONS BLOCKING SCREEN
// ==========================================
@Composable
fun PermissionsScreen(
    viewModel: GuardianViewModel,
    isOverlayEnabled: Boolean,
    isUsageEnabled: Boolean,
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
            text = "Gardiyan'ın kilitleri algılayıp çalıştırabilmesi için aşağıdaki sistem düzeyindeki iki izne ihtiyacı vardır.",
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            color = MutedGray,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Kullanım İstatistikleri",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureBlack
                        )
                        Text(
                            text = "Hangi uygulamanın açıldığını tespit etmek için.",
                            fontSize = 10.sp,
                            color = MutedGray
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isUsageEnabled) Color(0xFFE2F0D9) else SoftDangerRed)
                            .then(
                                if (!isUsageEnabled) {
                                    Modifier.clickable { viewModel.openUsageStatsSettings(context) }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isUsageEnabled) "AKTİF" else "YETKİ VER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isUsageEnabled) SuccessGreen else DangerRed,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                HorizontalDivider(color = BorderGray, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Diğer Uygulamalar Üzerinde Gösterim",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureBlack
                        )
                        Text(
                            text = "Korunma arayüzünü gösterebilmek için.",
                            fontSize = 10.sp,
                            color = MutedGray
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isOverlayEnabled) Color(0xFFE2F0D9) else SoftDangerRed)
                            .then(
                                if (!isOverlayEnabled) {
                                    Modifier.clickable { viewModel.openOverlaySettings(context) }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isOverlayEnabled) "AKTİF" else "YETKİ VER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isOverlayEnabled) SuccessGreen else DangerRed,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNavigateToDashboard,
            enabled = isOverlayEnabled && isUsageEnabled,
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

// ==========================================
// SCREEN 2: MINIMALIST DASHBOARD SCREEN (RE-DESIGNED)
// ==========================================
@Composable
fun DashboardScreen(
    viewModel: GuardianViewModel,
    onNavigateToSetup: () -> Unit
) {
    val session by viewModel.userSession.collectAsState()
    val allAppRestrictions by viewModel.allAppRestrictions.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MatteSurface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HeaderSection(session = session)
        }

        // Geniş modern CTA Butonu (Eski "+" FAB butonunun yerine) - HER ZAMAN GÖRÜNÜR
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderGray, RoundedCornerShape(20.dp))
                    .clickable { onNavigateToSetup() },
                colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Yeni Kısıtlama Başlat",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureBlack
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "İradeni güçlendirmek için yeni bir kısıtlama sözleşmesi kur.",
                            fontSize = 11.sp,
                            color = MutedGray
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SoftDangerRed),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = DangerRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        if (allAppRestrictions.isNotEmpty()) {
            items(allAppRestrictions) { restriction ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderGray, RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppIconView(packageName = restriction.packageName, modifier = Modifier.size(24.dp))
                                Text(
                                    text = restriction.appName.uppercase(),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MutedGray,
                                    letterSpacing = 1.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Switch(
                                checked = restriction.isActive,
                                onCheckedChange = { viewModel.toggleAppRestriction(restriction) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = PureWhite,
                                    checkedTrackColor = SuccessGreen,
                                    uncheckedThumbColor = MutedGray,
                                    uncheckedTrackColor = BorderGray
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(
                            modifier = Modifier.size(140.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(130.dp)
                                    .border(2.dp, BorderGray, CircleShape)
                            )

                            val progressVal = if (restriction.dailyLimitMinutes > 0) {
                                (restriction.remainingSecondsToday.toFloat() / (restriction.dailyLimitMinutes * 60).toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }

                            CircularProgressIndicator(
                                progress = { progressVal },
                                modifier = Modifier
                                    .size(130.dp)
                                    .graphicsLayer(rotationZ = -90f),
                                color = if (restriction.isActive) SoftDangerRed else BorderGray,
                                strokeWidth = 4.dp,
                                trackColor = Color.Transparent
                            )

                            val totalSecs = restriction.remainingSecondsToday.coerceAtLeast(0)
                            val mm = totalSecs / 60
                            val ss = totalSecs % 60

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = String.format("%02d:%02d", mm, ss),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraLight,
                                    color = if (!restriction.isActive) MutedGray else if (totalSecs <= 0) DangerRed else PureBlack
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        
                        TextButton(onClick = { viewModel.removeAppRestriction(restriction) }) {
                            Text("SİL", color = DangerRed, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        } else {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(BorderGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "SERBEST VE GÜVENLİ SÖRF",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = PureBlack
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Korumaya alınmış aktif bir kilit bulunmamaktadır. Yeni bir irade sözleşmesi başlatın.",
                        fontSize = 11.sp,
                        color = MutedGray,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        }
    }
}

// ==========================================
// SCREEN 3: HEDEF BELİRLEME EKRANI (SETUP - BOTTOM SHEET & PERFORMANCE UPDATED)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupTargetScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit,
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val targetApp by viewModel.targetAppName.collectAsState()
    val targetPkg by viewModel.targetAppPackage.collectAsState()
    val durationMin by viewModel.dailyLimitMinutes.collectAsState()

    val availableApps = remember { viewModel.getInstalledApps(context) }
    var searchAppQuery by remember { mutableStateOf("") }
    
    // Bottom Sheet State
    var showAppPickerSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ChoiceChips values
    val timeChoices = listOf(
        Pair("1 Dk", 1),
        Pair("15 Dk", 15),
        Pair("30 Dk", 30),
        Pair("60 Dk", 60)
    )

    // Slider range for custom time
    var customDuration by remember { mutableFloatStateOf(durationMin.toFloat()) }

    // Haftalık Gün Seçici (Takvim)
    val weekdays = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
    var selectedDays by remember { mutableStateOf(setOf(0, 1, 2, 3, 4, 5, 6)) } // default: all days

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
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = PureBlack)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "YENİ İRADE SÖZLEŞMESİ",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = PureBlack
                )
            }
        }

        // BÖLÜM 1: Uygulama Seçimi (Kayıcı BottomSheet)
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
                            text = "KISITLANACAK UYGULAMA",
                            fontSize = 11.sp,
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
                            .clickable { showAppPickerSheet = true }
                            .padding(14.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppIconView(packageName = targetPkg, modifier = Modifier.size(32.dp))
                                Column {
                                    Text(text = if(targetApp.isEmpty()) "Uygulama Seçin" else targetApp, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PureBlack)
                                    Text(text = if(targetPkg.isEmpty()) "Henüz bir paket seçilmedi" else targetPkg, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MutedGray)
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MutedGray
                            )
                        }
                    }
                }
            }
        }

        // BÖLÜM 2: Süre Seçimi (ChoiceChips & Slider)
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
                            text = "SERBEST SÜRE SINIRI",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MutedGray,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Choice Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        timeChoices.forEach { choice ->
                            val isSelected = durationMin == choice.second
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    viewModel.updateDailyLimit(choice.second)
                                    customDuration = choice.second.toFloat()
                                },
                                label = { Text(choice.first, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PureWhite,
                                    selectedLabelColor = Color.White,
                                    containerColor = MatteSurface,
                                    labelColor = PureBlack
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Özel Süre Belirle: ${customDuration.toInt()} Dakika",
                        fontSize = 12.sp,
                        color = PureBlack,
                        fontWeight = FontWeight.Bold
                    )

                    Slider(
                        value = customDuration,
                        onValueChange = {
                            customDuration = it
                            viewModel.updateDailyLimit(it.toInt())
                        },
                        valueRange = 1f..120f,
                        colors = SliderDefaults.colors(
                            thumbColor = PureWhite,
                            activeTrackColor = PureWhite,
                            inactiveTrackColor = BorderGray
                        )
                    )
                }
            }
        }

        // BÖLÜM 3: Gün ve Döngü Yönetimi (Haftalık Takvim Seçici)
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
                            text = "KISITLAMA GÜNLERİ",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MutedGray,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Hızlı Gün Şablonları
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = { selectedDays = setOf(0, 1, 2, 3, 4, 5, 6) },
                            label = { Text("Her Gün", fontSize = 11.sp) }
                        )
                        AssistChip(
                            onClick = { selectedDays = setOf(0, 1, 2, 3, 4) },
                            label = { Text("Hafta İçi", fontSize = 11.sp) }
                        )
                        AssistChip(
                            onClick = { selectedDays = setOf(5, 6) },
                            label = { Text("Hafta Sonu", fontSize = 11.sp) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Haftanın Günleri Butonları
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        weekdays.forEachIndexed { index, day ->
                            val isSelected = selectedDays.contains(index)
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) PureWhite else MatteSurface)
                                    .border(1.dp, if (isSelected) PureWhite else BorderGray, CircleShape)
                                    .clickable {
                                        selectedDays = if (isSelected) {
                                            selectedDays - index
                                        } else {
                                            selectedDays + index
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = day.take(1),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else PureBlack
                                )
                            }
                        }
                    }
                }
            }
        }

        // Kaydet Butonu
        item {
            Button(
                onClick = {
                    if (targetPkg.isEmpty()) {
                        Toast.makeText(context, "Lütfen önce bir uygulama seçin!", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.startNewTarget(context)
                        Toast.makeText(context, "Sözleşmeli Kilit Başlatıldı!", Toast.LENGTH_SHORT).show()
                        onCompleted()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "İRADE SÖZLEŞMESİNİ AKTİF ET",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }

    // Uygulama Seçim BottomSheet (Yüksek Performanslı LazyColumn & Search)
    if (showAppPickerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAppPickerSheet = false },
            sheetState = sheetState,
            containerColor = DarkCharcoal
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Uygulama Seçin",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureBlack,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Arama Çubuğu
                OutlinedTextField(
                    value = searchAppQuery,
                    onValueChange = { searchAppQuery = it },
                    placeholder = { Text("Uygulama ara...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PureWhite,
                        unfocusedBorderColor = BorderGray
                    )
                )

                // Filtrelenmiş Liste (Asenkron)
                val filteredApps = remember(searchAppQuery, availableApps) {
                    if (searchAppQuery.isEmpty()) {
                        availableApps
                    } else {
                        availableApps.filter {
                            it.first.contains(searchAppQuery, ignoreCase = true) ||
                            it.second.contains(searchAppQuery, ignoreCase = true)
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredApps, key = { it.second }) { app ->
                        val isSelected = targetPkg == app.second
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) SoftDangerRed else Color.Transparent)
                                .border(1.dp, if (isSelected) DangerRed else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable {
                                    viewModel.updateTargetApp(app.first, app.second)
                                    showAppPickerSheet = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AppIconView(packageName = app.second, modifier = Modifier.size(40.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.first,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = PureBlack
                                )
                                Text(
                                    text = app.second,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MutedGray
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = DangerRed)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 4: DETAILED PROFILE & SETTINGS (TIMELINE LOG INTEGRATED)
// ==========================================
@Composable
fun SettingsScreen(
    viewModel: GuardianViewModel
) {
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
                text = "AYARLAR VE GEÇMİŞ",
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = PureBlack
            )
        }

        // Sözleşmeyi Sıfırla Kartı
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
                        text = "Aktif Kilit Yönetimi",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureBlack
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Geçerli tüm engellemeleri kaldırıp yeni bir sözleşme oluşturmak için sıfırlayabilirsiniz.",
                        fontSize = 11.sp,
                        color = MutedGray
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.resetTargetSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = SoftDangerRed, contentColor = DangerRed),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("KİLİT ANLAŞMASINI FESHET / SIFIRLA", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Timeline Log Görünümü (İnsancıl loglar)
        item {
            Text(
                text = "SON İRADELİ HAREKETLER",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MutedGray,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        if (logs.isEmpty()) {
            item {
                Text(
                    text = "Henüz kaydedilmiş bir hareket bulunmuyor.",
                    fontSize = 12.sp,
                    color = MutedGray,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            items(logs) { log ->
                TimelineLogItem(log = log)
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// Dikey Timeline Log Tasarımı
@Composable
fun TimelineLogItem(log: StatusLogEntity) {
    val displayMessage = when (log.eventType) {
        "BLOCKED" -> "Uygulama engellendi ve ana ekrana yönlendirildi."
        "SESSION_RESET" -> "Kilit sözleşmesi sıfırlandı."
        "Sözleşmeli Kilit Başlatıldı" -> "İrade kilidi aktif edildi."
        else -> log.details
    }

    val displayTime = remember(log.timestamp) {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(log.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Zaman Sütunu
        Text(
            text = displayTime,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MutedGray,
            modifier = Modifier.width(48.dp)
        )

        // Dikey Çizgi ve Nokta
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (log.eventType == "BLOCKED") DangerRed else SuccessGreen)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(40.dp)
                    .background(BorderGray)
            )
        }

        // İçerik Kartı
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = log.appName.ifEmpty { "Sistem" },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = PureBlack
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = displayMessage,
                fontSize = 11.sp,
                color = MutedGray,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun HeaderSection(session: UserSessionEntity?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DİJİTAL GARDİYAN",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = SoftDangerRed,
                letterSpacing = 1.sp
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BorderGray)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "v0.8",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MutedGray
                )
            }
        }
    }
}
