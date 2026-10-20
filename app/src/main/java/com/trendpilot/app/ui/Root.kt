package com.trendpilot.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trendpilot.app.R
import com.trendpilot.app.ui.screens.DashboardScreen
import com.trendpilot.app.ui.screens.HistoryScreen
import com.trendpilot.app.ui.screens.PaperScreen
import com.trendpilot.app.ui.screens.PositionsScreen
import com.trendpilot.app.ui.screens.SecurityScreen
import com.trendpilot.app.ui.screens.SettingsScreen
import com.trendpilot.app.ui.screens.StatsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Green = Color(0xFF2EE59D)
val Red = Color(0xFFFF5C6C)
val Amber = Color(0xFFFFC14D)

private val colors = darkColorScheme(
    primary = Green,
    secondary = Color(0xFF26C6DA),
    background = Color(0xFF020A12),
    surface = Color(0xFF0B1620),
    surfaceVariant = Color(0xFF13212D),
    error = Red,
)

enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Dashboard),
    POSITIONS("positions", "Posiciones", Icons.Filled.ShowChart),
    HISTORY("history", "Historial y auditoría", Icons.Filled.History),
    STATS("stats", "Estadísticas", Icons.Filled.QueryStats),
    PAPER("paper", "Paper trading", Icons.Filled.AccountBalanceWallet),
    SETTINGS("settings", "Configuración", Icons.Filled.Settings),
    SECURITY("security", "Seguridad y emergencia", Icons.Filled.Security),
}

typealias Authenticate = (reason: String, onSuccess: () -> Unit) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendPilotRoot(authenticate: Authenticate) {
    MaterialTheme(colorScheme = colors) {
        var showPresplash by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { delay(1_400); showPresplash = false }
        if (showPresplash) Presplash() else MainContent(authenticate)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(authenticate: Authenticate) {
    run {
        val vm: MainViewModel = hiltViewModel()
        val nav = rememberNavController()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val message by vm.message.collectAsState()
        val settings by vm.settings.collectAsState()
        val state by vm.engineState.collectAsState()
        val current = nav.currentBackStackEntryAsState().value?.destination?.route
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.toast(null) } }

        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet {
                    Spacer(Modifier.height(16.dp))
                    Text("TrendPilot AI", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Dest.entries.forEach { d ->
                        NavigationDrawerItem(
                            label = { Text(d.label) },
                            icon = { Icon(d.icon, null) },
                            selected = current == d.route,
                            onClick = {
                                nav.navigate(d.route) { launchSingleTop = true; popUpTo(Dest.DASHBOARD.route) }
                                scope.launch { drawer.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            },
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = {
                            val label = Dest.entries.firstOrNull { it.route == current }?.label ?: "TrendPilot AI"
                            Text(label)
                        },
                        navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menú") } },
                        actions = {
                            val real = settings.mode == "REAL"
                            val tag = if (real) "REAL · ${settings.binanceEnvironment}" else settings.mode
                            Text(
                                tag,
                                color = if (real && settings.binanceEnvironment == "PRODUCTION") Red else if (real) Amber else Green,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                },
            ) { padding ->
                NavHost(nav, startDestination = Dest.DASHBOARD.route, modifier = Modifier.padding(padding)) {
                    composable(Dest.DASHBOARD.route) { DashboardScreen(vm) }
                    composable(Dest.POSITIONS.route) { PositionsScreen(vm, authenticate) }
                    composable(Dest.HISTORY.route) { HistoryScreen(vm) }
                    composable(Dest.STATS.route) { StatsScreen(vm) }
                    composable(Dest.PAPER.route) { PaperScreen(vm, authenticate) }
                    composable(Dest.SETTINGS.route) { SettingsScreen(vm, authenticate) }
                    composable(Dest.SECURITY.route) { SecurityScreen(vm, authenticate) }
                }
            }
        }
        if (state?.emergency == true && current != Dest.SECURITY.route) {
            LaunchedEffect(state?.emergencyReason) { snackbar.showSnackbar("EMERGENCIA: ${state?.emergencyReason}. Ver Seguridad.") }
        }
    }
}

@Composable
private fun Presplash() {
    Box(Modifier.fillMaxSize().background(Color(0xFF020A12)), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.presplash),
            contentDescription = "TrendPilot AI",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun AppLogo(size: Int = 56) {
    Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(size.dp))
}
