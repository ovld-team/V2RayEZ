package com.v2rayez.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import com.v2rayez.app.R

/** All navigation destinations as type-safe route strings. */
object Routes {
    const val ONBOARDING = "onboarding"

    const val HOME = "home"
    const val SERVERS = "servers"
    const val TOOLS = "tools"
    const val STATISTICS = "statistics"
    const val BROWSER = "browser"
    const val SETTINGS = "settings"

    const val LOGS = "logs"
    const val ABOUT = "about"
    const val DONATE = "donate"
    const val NOTIFICATIONS = "notifications"
    const val WARP = "warp"
    const val HOTSPOT = "hotspot"
    const val ADVANCED_VPN = "advanced_vpn"
    const val MORE_SETTINGS = "more_settings"
    const val CORE_MANAGER = "core_manager"

    /** Server editor. Pass an optional serverId to edit an existing entry. */
    const val FREE_SERVERS = "free_servers"

    const val SERVER_EDITOR = "server_editor"
    const val SERVER_EDITOR_ARG = "serverId"
    const val SERVER_EDITOR_ROUTE = "server_editor?serverId={serverId}"
    fun serverEditor(serverId: String? = null): String =
        if (serverId == null) SERVER_EDITOR else "$SERVER_EDITOR?serverId=$serverId"

    // Tools & advanced feature screens
    const val ROUTING = "routing"
    const val DNS = "dns"
    const val HOSTS = "hosts"
    const val APP_PROXY = "app_proxy"
    const val SNI_TUNNEL = "sni_tunnel"
    /** MITM Domain Fronting (serverless). */
    const val DOMAIN_FRONTING = "domain_fronting"
    /** VPN fake-SNI dialer (legacy Domain Fronting engine). */
    const val SNI_FRONT_DIALER = "sni_front_dialer"
    const val TOR = "tor"
    const val BPB_PANEL = "bpb_panel"
    const val CERTIFICATES = "certificates"
    const val DIAGNOSTICS = "diagnostics"
    const val SPEED_TEST = "speed_test"
}

/** The five bottom-bar tabs. */
enum class BottomDestination(
    val route: String,
    val label: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    HOME(Routes.HOME, "Home", R.string.nav_home, Icons.Filled.Home),
    SERVERS(Routes.SERVERS, "Servers", R.string.nav_servers, Icons.Filled.Storage),
    TOOLS(Routes.TOOLS, "Tools", R.string.nav_tools, Icons.Filled.Build),
    BROWSER(Routes.BROWSER, "Browser", R.string.nav_browser, Icons.Filled.Language),
    SETTINGS(Routes.SETTINGS, "Settings", R.string.nav_settings, Icons.Filled.Settings);

    companion object {
        fun fromRoute(route: String?): BottomDestination? = entries.firstOrNull { it.route == route }
    }
}
