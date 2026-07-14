package com.v2rayez.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedVisibility
import com.v2rayez.app.ui.theme.MotionTokens
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.v2rayez.app.ui.components.V2BottomBar
import com.v2rayez.app.ui.navigation.BottomDestination
import com.v2rayez.app.ui.navigation.Routes
import com.v2rayez.app.ui.screens.about.AboutScreen
import com.v2rayez.app.ui.screens.donate.DonateScreen
import com.v2rayez.app.ui.screens.home.HomeScreen
import com.v2rayez.app.ui.screens.logs.LogsScreen
import com.v2rayez.app.ui.screens.notifications.NotificationsScreen
import com.v2rayez.app.ui.screens.onboarding.WelcomeWizardScreen
import com.v2rayez.app.ui.screens.hotspot.HotspotShareScreen
import com.v2rayez.app.ui.screens.warp.WarpScreen
import com.v2rayez.app.ui.screens.servers.FreeServersScreen
import com.v2rayez.app.ui.screens.servers.ServerEditorScreen
import com.v2rayez.app.ui.screens.servers.ServersScreen
import com.v2rayez.app.ui.screens.settings.AdvancedVpnScreen
import com.v2rayez.app.ui.screens.settings.CoreManagerScreen
import com.v2rayez.app.ui.screens.settings.MoreSettingsScreen
import com.v2rayez.app.ui.screens.settings.SettingsScreen
import com.v2rayez.app.ui.screens.statistics.StatisticsScreen
import com.v2rayez.app.ui.screens.tools.AppProxyScreen
import com.v2rayez.app.ui.screens.tools.BpbPanelScreen
import com.v2rayez.app.ui.screens.tools.CertificatesScreen
import com.v2rayez.app.ui.screens.tools.DiagnosticsScreen
import com.v2rayez.app.ui.screens.tools.DnsScreen
import com.v2rayez.app.ui.screens.browser.BrowserScreen
import com.v2rayez.app.ui.screens.mitm.MitmDomainFrontingScreen
import com.v2rayez.app.ui.screens.tools.DomainFrontingScreen
import com.v2rayez.app.ui.screens.tools.HostsScreen
import com.v2rayez.app.ui.screens.tools.RoutingScreen
import com.v2rayez.app.ui.screens.tools.SniTunnelScreen
import com.v2rayez.app.ui.screens.tools.SpeedTestScreen
import com.v2rayez.app.ui.screens.tools.ToolsScreen
import com.v2rayez.app.ui.screens.tools.TorScreen

@Composable
fun V2RayApp(
    initialRoute: String? = null,
    onInitialRouteConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val bottomDestination = BottomDestination.fromRoute(currentRoute)

    // Single-top navigation for all detail screens: guards against rapid double-taps
    // pushing duplicate copies of the same destination onto the back stack.
    val go: (String) -> Unit = { route ->
        navController.navigate(route) { launchSingleTop = true }
    }

    // Deep-link entry from app shortcuts / external navigation.
    LaunchedEffect(initialRoute) {
        val target = initialRoute ?: return@LaunchedEffect
        if (target != Routes.HOME) {
            navController.navigate(target) { launchSingleTop = true }
        }
        onInitialRouteConsumed()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Safe drawing insets (status + nav + cutout). When the bottom bar is visible it
        // consumes the nav-bar inset itself; when hidden (detail screens), content still pads.
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            AnimatedVisibility(
                visible = bottomDestination != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                // Surface paints under the system nav / home indicator; V2BottomBar pads content up.
                Surface(color = MaterialTheme.colorScheme.surface) {
                    V2BottomBar(
                        current = bottomDestination ?: BottomDestination.HOME,
                        onSelect = { dest ->
                            if (dest.route != currentRoute) {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    // Restoring the start destination's saved back stack would
                                    // re-show whichever tab was on top of Home, so skip restore
                                    // when returning to Home (the start destination).
                                    restoreState = dest.route != Routes.HOME
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        // Center and cap content width so the UI stays comfortable on tablets and
        // large/foldable displays instead of stretching edge-to-edge.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            enterTransition = {
                fadeIn(animationSpec = MotionTokens.normal()) +
                    slideInHorizontally(
                        animationSpec = MotionTokens.normal(),
                        initialOffsetX = { it / 16 }
                    )
            },
            exitTransition = {
                fadeOut(animationSpec = MotionTokens.fast())
            },
            popEnterTransition = {
                fadeIn(animationSpec = MotionTokens.normal())
            },
            popExitTransition = {
                fadeOut(animationSpec = MotionTokens.fast()) +
                    slideOutHorizontally(
                        animationSpec = MotionTokens.fast(),
                        targetOffsetX = { it / 16 }
                    )
            }
        ) {
            composable(Routes.ONBOARDING) { WelcomeWizardScreen() }
            composable(Routes.HOME) {
                HomeScreen(
                    onSeeServers = { go(Routes.SERVERS) },
                    onOpenNotifications = { go(Routes.NOTIFICATIONS) },
                    onOpenPremium = { go(Routes.DONATE) },
                    onOpenAssets = { go(Routes.CORE_MANAGER) },
                    onOpenSpeedTest = { go(Routes.SPEED_TEST) },
                    onOpenTor = { go(Routes.TOR) },
                    onOpenSniTunnel = { go(Routes.SNI_TUNNEL) },
                    onOpenAdvancedVpn = { go(Routes.ADVANCED_VPN) },
                    onOpenDomainFronting = { go(Routes.DOMAIN_FRONTING) },
                    onOpenFreeServers = { go(Routes.FREE_SERVERS) }
                )
            }
            composable(Routes.SERVERS) {
                ServersScreen(
                    onAddManual = { go(Routes.serverEditor()) },
                    onEditServer = { id -> go(Routes.serverEditor(id)) },
                    onBrowseFreeServers = { go(Routes.FREE_SERVERS) },
                    onConnected = {
                        // Return to Home so the user watches the connection status card update.
                        navController.navigate(Routes.HOME) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Routes.FREE_SERVERS) { FreeServersScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.TOOLS) {
                ToolsScreen(
                    onNavigate = { route -> go(route) },
                    onOpenLogs = { go(Routes.LOGS) }
                )
            }
            composable(Routes.BROWSER) {
                BrowserScreen(onOpenMitmSetup = { go(Routes.DOMAIN_FRONTING) })
            }
            composable(Routes.STATISTICS) { StatisticsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenAdvancedVpn = { go(Routes.ADVANCED_VPN) },
                    onOpenAbout = { go(Routes.ABOUT) },
                    onOpenLogs = { go(Routes.LOGS) },
                    onOpenRouting = { go(Routes.ROUTING) },
                    onOpenDns = { go(Routes.DNS) },
                    onOpenWarp = { go(Routes.WARP) },
                    onOpenHotspot = { go(Routes.HOTSPOT) },
                    onOpenStatistics = { go(Routes.STATISTICS) }
                )
            }
            composable(Routes.LOGS) { LogsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.ABOUT) {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    onDonate = { go(Routes.DONATE) }
                )
            }
            composable(Routes.DONATE) { DonateScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.NOTIFICATIONS) { NotificationsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.WARP) { WarpScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.HOTSPOT) { HotspotShareScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.ADVANCED_VPN) {
                AdvancedVpnScreen(
                    onBack = { navController.popBackStack() },
                    onOpenMore = { go(Routes.MORE_SETTINGS) },
                    onOpenCoreManager = { go(Routes.CORE_MANAGER) }
                )
            }
            composable(Routes.MORE_SETTINGS) { MoreSettingsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.CORE_MANAGER) { CoreManagerScreen(onBack = { navController.popBackStack() }) }

            composable(Routes.ROUTING) { RoutingScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.DNS) { DnsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.HOSTS) { HostsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.APP_PROXY) { AppProxyScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SNI_TUNNEL) { SniTunnelScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.DOMAIN_FRONTING) {
                MitmDomainFrontingScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBrowser = {
                        navController.navigate(Routes.BROWSER) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenCertificates = { go(Routes.CERTIFICATES) }
                )
            }
            composable(Routes.SNI_FRONT_DIALER) {
                DomainFrontingScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.TOR) { TorScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.BPB_PANEL) { BpbPanelScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.CERTIFICATES) { CertificatesScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SPEED_TEST) { SpeedTestScreen(onBack = { navController.popBackStack() }) }

            composable(
                route = Routes.SERVER_EDITOR_ROUTE,
                arguments = listOf(
                    navArgument(Routes.SERVER_EDITOR_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                ServerEditorScreen(
                    serverId = entry.arguments?.getString(Routes.SERVER_EDITOR_ARG),
                    onBack = { navController.popBackStack() }
                )
            }
        }
        }
    }
}
