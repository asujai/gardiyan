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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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

// Multi-Screen Routes Definitions
import kotlinx.coroutines.Dispatchers
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
        // Servis çöktüyse ve aktif oturum varsa otomatik yeniden başlat
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

    // Periodically recheck permissions when app is active
    LaunchedEffect(Unit) {
        while (true) {
            isOverlayEnabled = viewModel.hasOverlayPermission(context)
            isUsageEnabled = viewModel.hasUsageStatsPermission(context)
            kotlinx.coroutines.delay(1000)
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
                    containerColor = DarkCharcoal, // White surface card background in light theme
                    tonalElevation = 6.dp,
                    modifier = Modifier.border(width = 1.dp, color = BorderGray)
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
        // Aesthetic Top Header Icon representing security shield
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
            text = "Gardiyan'ın kilitleri interceptor ile algılayıp çalıştırabilmesi için aşağıdaki sistem düzeyindeki iki izne ihtiyacı vardır.",
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            color = MutedGray,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Card containing individual settings details
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
                // ROW 1: Usage Stats
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

                // ROW 2: Overlay Draw Overlays
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

        // Redirection / Enter Button
        Button(
            onClick = {
                onNavigateToDashboard()
            },
            enabled = isOverlayEnabled && isUsageEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = PureWhite, // Mint Green / Primary Accent
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
// SCREEN 2: MINIMALIST DASHBOARD SCREEN
// ==========================================
@Composable
fun DashboardScreen(
    viewModel: GuardianViewModel,
    onNavigateToSetup: () -> Unit
) {
    val session by viewModel.userSession.collectAsState()

    Scaffold(
        floatingActionButton = {
            if (session?.isActive != true) {
                FloatingActionButton(
                    onClick = onNavigateToSetup,
                    containerColor = PureWhite, // Mint Teal
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Hedef Kilit Ekle Setup")
                }
            }
        },
        containerColor = MatteSurface
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Branding Header View
            item {
                HeaderSection(session = session)
            }

            if (session?.isActive == true) {
                item {
                    val activeSession = session!!
                    // Circular countdown and target name layout
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
                            Text(
                                text = "GÜNLÜK KALAN KULLANIM SÜRESİ",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MutedGray,
                                letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            // Circular visualizer progress container
                            Box(
                                modifier = Modifier.size(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // Outer light circle decoration
                                Box(
                                    modifier = Modifier
                                        .size(190.dp)
                                        .border(2.dp, BorderGray, CircleShape)
                                )

                                val progressVal = if (activeSession.dailyLimitMinutes > 0) {
                                    (activeSession.remainingSecondsToday.toFloat() / (activeSession.dailyLimitMinutes * 60).toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }

                                CircularProgressIndicator(
                                    progress = { progressVal },
                                    modifier = Modifier
                                        .size(190.dp)
                                        .graphicsLayer(rotationZ = -90f),
                                    color = PureWhite,
                                    strokeWidth = 4.dp,
                                    trackColor = Color.Transparent
                                )

                                val totalSecs = activeSession.remainingSecondsToday.coerceAtLeast(0)
                                val mm = totalSecs / 60
                                val ss = totalSecs % 60

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = String.format("%02d:%02d", mm, ss),
                                        fontSize = 44.sp,
                                        fontWeight = FontWeight.ExtraLight,
                                        color = if (totalSecs <= 0) DangerRed else PureBlack
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "DAKİKA : SANİYE",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MutedGray,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // Highlight active target application in custom style
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SoftDangerRed.copy(alpha = 0.5f))
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${activeSession.targetAppName.uppercase()} ENGELİ AKTİF",
                                    fontSize = 10.sp,
                                    color = DangerRed,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }

                // Kaldırılan gözetmen ve rozet UI kısımları
            } else {
                // Empty Rest State view
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(
                            onClick = onNavigateToSetup,
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape)
                                .background(BorderGray)
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
                            text = "SERBEST VE GÜVENLİ SÖRİF",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = PureBlack
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Korumaya alınmış aktif bir kilit bulunmamaktadır. Sağ aşağıdaki '+' butonuna tıklayarak yeni bir irade sözleşmesi başlatın.",
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
}

// ==========================================
// SCREEN 3: HEDEF BELİRLEME EKRANI (SETUP)
// ==========================================
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
    val isObserver by viewModel.isObserverMode.collectAsState()
    val shameMsg by viewModel.shameMessage.collectAsState()
    val observerName by viewModel.observerContactName.collectAsState()

    // Load available package matches dynamically from the ViewModel
    val availableApps = remember { viewModel.getInstalledApps(context) }
    var isAppDropdownExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MatteSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Simple elegant display header
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

        // CARD SECTION 1: App Picker Selector Representation containing Iconic components
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderGray, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkCharcoal),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Headline header
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

                    // Selectable trigger field
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MatteSurface)
                            .border(1.dp, BorderGray, RoundedCornerShape(12.dp))
                            .clickable { isAppDropdownExpanded = !isAppDropdownExpanded }
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
                                    Text(text = targetApp, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PureBlack)
                                    Text(text = targetPkg, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MutedGray)
                                }
                            }
                            Icon(
                                imageVector = if (isAppDropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MutedGray
                            )
                        }
                    }

                    AnimatedVisibility(visible = isAppDropdownExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .heightIn(max = 200.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableApps.forEach { app ->
                                val isSelected = targetPkg == app.second
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) SoftDangerRed else Color.Transparent)
                                        .clickable {
                                            viewModel.updateTargetApp(app.first, app.second)
                                            isAppDropdownExpanded = false
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AppIconView(packageName = app.second, modifier = Modifier.size(32.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.first,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = PureBlack
                                        )
                                        Text(
                                            text = app.second,
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MutedGray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // CARD SECTION 2: Duration Limits Setup
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
                            text = "GÜNLÜK SERBEST SÜRE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MutedGray,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val timeChoices = listOf(
                        Pair("Test Sörfü (1 Dk)", 1),
                        Pair("Denge Sörfü (15 Dk)", 15),
                        Pair("İdeal Limit (30 Dk)", 30),
                        Pair("Standard (60 Dk)", 60)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        timeChoices.forEach { t ->
                            val isSelected = durationMin == t.second
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) MatteSurface else Color.Transparent)
                                    .border(1.dp, if (isSelected) PureWhite else BorderGray, RoundedCornerShape(10.dp))
                                    .clickable { viewModel.updateDailyLimit(t.second) }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = t.first,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = PureBlack
                                )
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Gözetmen ayarları tamamen kaldırıldı

        // Commit starting operation trigger button
        item {
            Button(
                onClick = {
                    viewModel.startNewTarget(context)
                    Toast.makeText(context, "Sözleşmeli Kilit Başlatıldı!", Toast.LENGTH_SHORT).show()
                    onCompleted()
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
}

// ==========================================
// SCREEN 4: DETAILED PROFILE & SETTINGS SCREEN
// ==========================================
@Composable
fun SettingsScreen(
    viewModel: GuardianViewModel
) {
    val session by viewModel.userSession.collectAsState()
    val logs by viewModel.allLogs.collectAsState()
    var isJsonPanelExpanded by remember { mutableStateOf(false) }

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

        // Karmaşık JSON ve Log UI'ları minimalist yaklaşım sebebiyle kaldırıldı

        // Section 4: Safe Reset Current config
        item {
            Button(
                onClick = { viewModel.resetTargetSession() },
                colors = ButtonDefaults.buttonColors(containerColor = SoftDangerRed, contentColor = DangerRed),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("KİLİT ANLAŞMASINI FESHET / SIFIRLA", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ==========================================
// STATIC/SHARED COMPOSE PARTS & HELPERS
// ==========================================
@Composable
fun HeaderSection(session: UserSessionEntity?) {
    val levelName = when (session?.level ?: 1) {
        1 -> "ÇAYLAK"
        2 -> "DİSİPLİNLİ"
        3 -> "USTA"
        else -> "ÇAYLAK"
    }

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
                    text = "v0.7",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MutedGray
                )
            }
        }
        // Minimalist görünüm için rütbe ve utanç rozeti alanları temizlendi.
    }
}

// HistoryLogs ve JSON Schema görüntüleyicisi UI bileşenleri silindi

