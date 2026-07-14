package com.v2rayez.app.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.v2rayez.app.R
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.SettingRow
import com.v2rayez.app.ui.components.ToolCard
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2TopBar
import com.v2rayez.app.ui.navigation.Routes
import com.v2rayez.app.ui.theme.Info
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.Violet
import com.v2rayez.app.ui.theme.Warning

// Purpose-based grouping so features are findable at a glance. IDs map to routes via [routeFor].
// Unblock = get through censorship; Routing & DNS = shape traffic; Manage & Diagnose = the rest.
private val UNBLOCK_IDS = listOf("sni", "fronting", "tor", "snifront")
private val ROUTING_IDS = listOf("routing", "dns", "hosts", "appproxy")
private val MANAGE_IDS = listOf("bp8", "cert", "coremgr", "speed", "diag", "logs")

@Composable
fun ToolsScreen(onNavigate: (String) -> Unit, onOpenLogs: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2TopBar(title = stringResource(R.string.tools_title))

        Column(Modifier.padding(horizontal = 16.dp)) {
            // Anti-censorship / unblock tools as a card grid (the primary reason to open Tools).
            SectionHeader(title = stringResource(R.string.tools_group_unblock))
            UNBLOCK_IDS.chunked(2).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowItems.forEach { id ->
                        val (title, subtitle) = toolTitleSub(id)
                        val (icon, accent) = toolVisual(id)
                        ToolCard(
                            icon = icon,
                            title = title,
                            subtitle = subtitle,
                            onClick = { onNavigate(routeFor(id)) },
                            modifier = Modifier.weight(1f),
                            accent = accent
                        )
                    }
                    if (rowItems.size == 1) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
                VSpacer(12)
            }

            VSpacer(12)
            SectionHeader(title = stringResource(R.string.tools_group_routing))
            ToolList(ids = ROUTING_IDS, onNavigate = onNavigate, onOpenLogs = onOpenLogs)

            VSpacer(20)
            SectionHeader(title = stringResource(R.string.tools_group_manage))
            ToolList(ids = MANAGE_IDS, onNavigate = onNavigate, onOpenLogs = onOpenLogs)

            VSpacer(24)
        }
    }
}

/** A grouped card of tappable rows (used for the two list-style sections). */
@Composable
private fun ToolList(ids: List<String>, onNavigate: (String) -> Unit, onOpenLogs: () -> Unit) {
    CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column {
            ids.forEachIndexed { index, id ->
                val (title, subtitle) = toolTitleSub(id)
                SettingRow(
                    icon = listIcon(id),
                    title = title,
                    subtitle = subtitle,
                    onClick = { if (id == "logs") onOpenLogs() else onNavigate(routeFor(id)) }
                )
                if (index < ids.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp, modifier = Modifier.padding(start = 52.dp))
                }
            }
        }
    }
}

/** Localized title + subtitle for a tool id, kept in one place so grid and list stay consistent. */
@Composable
private fun toolTitleSub(id: String): Pair<String, String> = when (id) {
    "sni" -> stringResource(R.string.sni_title) to stringResource(R.string.diag_sec_sni)
    "fronting" -> stringResource(R.string.mitm_title) to stringResource(R.string.mitm_tool_sub)
    "tor" -> stringResource(R.string.tor_title) to stringResource(R.string.tor_engine_desc)
    "snifront" -> stringResource(R.string.sni_front_title) to stringResource(R.string.sni_front_tool_sub)
    "routing" -> stringResource(R.string.routing_title) to stringResource(R.string.routing_custom_rules)
    "dns" -> stringResource(R.string.dns_title) to stringResource(R.string.dns_servers)
    "hosts" -> stringResource(R.string.hosts_title) to stringResource(R.string.hosts_static)
    "appproxy" -> stringResource(R.string.appproxy_title) to stringResource(R.string.appproxy_enable)
    "bp8" -> stringResource(R.string.servers_subscriptions) to stringResource(R.string.bpb_import_subscription)
    "cert" -> stringResource(R.string.cert_title) to stringResource(R.string.cert_install)
    "coremgr" -> stringResource(R.string.core_manager_title) to stringResource(R.string.core_manager_sub)
    "speed" -> stringResource(R.string.tools_speed_test) to stringResource(R.string.tools_speed_test_sub)
    "diag" -> stringResource(R.string.diag_title) to stringResource(R.string.diag_sec_connectivity)
    "logs" -> stringResource(R.string.tools_logs) to stringResource(R.string.tools_logs_sub)
    else -> id to ""
}

private fun routeFor(id: String): String = when (id) {
    "sni" -> Routes.SNI_TUNNEL
    "fronting" -> Routes.DOMAIN_FRONTING
    "snifront" -> Routes.SNI_FRONT_DIALER
    "tor" -> Routes.TOR
    "bp8" -> Routes.BPB_PANEL
    "cert" -> Routes.CERTIFICATES
    "dns" -> Routes.DNS
    "diag" -> Routes.DIAGNOSTICS
    "routing" -> Routes.ROUTING
    "hosts" -> Routes.HOSTS
    "appproxy" -> Routes.APP_PROXY
    "speed" -> Routes.SPEED_TEST
    "coremgr" -> Routes.CORE_MANAGER
    else -> Routes.DIAGNOSTICS
}

private fun toolVisual(id: String): Pair<ImageVector, androidx.compose.ui.graphics.Color> = when (id) {
    "sni" -> Icons.Filled.VpnLock to Violet
    "fronting" -> Icons.Filled.Cloud to Violet
    "tor" -> Icons.Filled.Public to Info
    "snifront" -> Icons.Filled.SettingsEthernet to Warning
    else -> Icons.Filled.Tune to Violet
}

private fun listIcon(id: String): ImageVector = when (id) {
    "routing" -> Icons.Filled.Route
    "dns" -> Icons.Filled.Dns
    "hosts" -> Icons.Filled.Storage
    "appproxy" -> Icons.Filled.Layers
    "bp8" -> Icons.Filled.CloudSync
    "cert" -> Icons.Filled.Shield
    "coremgr" -> Icons.Filled.Memory
    "speed" -> Icons.Filled.Speed
    "diag" -> Icons.Filled.MonitorHeart
    "logs" -> Icons.Filled.Article
    else -> Icons.Filled.Tune
}

@Preview
@Composable
private fun ToolsScreenPreview() {
    V2RayEzTheme { ToolsScreen(onNavigate = {}, onOpenLogs = {}) }
}
