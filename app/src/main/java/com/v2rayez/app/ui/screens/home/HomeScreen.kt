package com.v2rayez.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.util.Formatters
import com.v2rayez.app.domain.model.ActivityType
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.TrafficPoint
import com.v2rayez.app.ui.components.ActivityRow
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.LiveThroughputChart
import com.v2rayez.app.ui.components.QuickActionButton
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.SettingRow
import com.v2rayez.app.ui.components.SettingSwitchRow
import com.v2rayez.app.ui.components.StatTile
import com.v2rayez.app.ui.components.TrafficBarChart
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2TopBar
import com.v2rayez.app.ui.theme.ChartUpload
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.ErrorRed
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.Warning
import com.v2rayez.app.ui.theme.accentGradient
import com.v2rayez.app.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    onSeeServers: () -> Unit,
    onOpenNotifications: () -> Unit = {},
    onOpenPremium: () -> Unit = {},
    onOpenAssets: () -> Unit = {},
    onOpenSpeedTest: () -> Unit = {},
    onOpenTor: () -> Unit = {},
    onOpenSniTunnel: () -> Unit = {},
    onOpenAdvancedVpn: () -> Unit = {},
    onOpenDomainFronting: () -> Unit = {},
    onOpenFreeServers: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val liveThroughput by viewModel.liveThroughput.collectAsState()
    val dailyTraffic by viewModel.dailyTraffic.collectAsState()
    val recentActivity by viewModel.recentActivity.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()
    val vpnPermission = com.v2rayez.app.ui.LocalVpnPermission.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2TopBar(
            title = "",
            titleContent = { HomeWordmark() },
            actions = {
                TopIcon(Icons.Filled.Storage, stringResource(R.string.home_assets), MaterialTheme.colorScheme.onSurfaceVariant, onClick = onOpenAssets)
                TopIcon(Icons.Filled.WorkspacePremium, stringResource(R.string.home_premium), Warning, onClick = onOpenPremium)
                TopIcon(Icons.Filled.Notifications, stringResource(R.string.home_notifications), MaterialTheme.colorScheme.onSurfaceVariant, onClick = onOpenNotifications)
            }
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            VSpacer(16)

            IranGeoCta(onOpenAssets = onOpenAssets)

            val status = connectionState.status
            ConnectionStatusCard(
                status = status,
                server = connectionState.server,
                errorMessage = connectionState.errorMessage,
                onOpenCoreManager = onOpenAssets,
                onToggle = {
                    if (status == ConnectionStatus.DISCONNECTED) {
                        vpnPermission.request { viewModel.connectAutoOrToggle() }
                    } else {
                        viewModel.connectAutoOrToggle()
                    }
                }
            )
            VSpacer(12)

            SessionStrip(
                uptimeSeconds = connectionState.uptimeSeconds,
                sessionDownLabel = connectionState.sessionDownLabel,
                sessionUpLabel = connectionState.sessionUpLabel
            )
            VSpacer(16)

            StatGrid(
                downloadLabel = connectionState.downloadLabel,
                uploadLabel = connectionState.uploadLabel,
                speedLabel = connectionState.speedLabel,
                pingMs = connectionState.pingMs
            )
            VSpacer(16)

            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column {
                    SettingSwitchRow(
                        icon = Icons.Filled.Bolt,
                        title = stringResource(R.string.home_auto_mode),
                        checked = autoConnect,
                        onCheckedChange = viewModel::setAutoConnect
                    )
                    SettingRow(
                        icon = Icons.Filled.Tune,
                        title = stringResource(R.string.home_advanced_vpn),
                        subtitle = stringResource(R.string.home_advanced_vpn_sub),
                        onClick = onOpenAdvancedVpn
                    )
                    SettingRow(
                        icon = Icons.Filled.Cloud,
                        title = stringResource(R.string.mitm_title),
                        subtitle = stringResource(R.string.mitm_tool_sub),
                        onClick = onOpenDomainFronting
                    )
                }
            }
            VSpacer(20)

            val isConnected = connectionState.status == ConnectionStatus.CONNECTED
            SectionHeader(
                title = stringResource(R.string.home_traffic),
                trailing = { if (!isConnected) WeekPill() }
            )
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    if (isConnected) {
                        LiveThroughputChart(samples = liveThroughput)
                    } else {
                        TrafficLegend(dailyTraffic)
                        VSpacer(12)
                        TrafficBarChart(points = dailyTraffic)
                    }
                }
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.home_quick_actions))
            QuickActions(
                onServers = onSeeServers,
                onSpeedTest = onOpenSpeedTest,
                onTor = onOpenTor,
                onSniTunnel = onOpenSniTunnel,
                onFreeServers = onOpenFreeServers
            )
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.home_recent_activity))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (recentActivity.isEmpty()) {
                        Text(stringResource(R.string.home_no_recent_activity), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    recentActivity.forEach { item ->
                        val (icon, accent) = when (item.type) {
                            ActivityType.CONNECTED -> Icons.Filled.CheckCircle to Connected
                            ActivityType.DURATION -> Icons.Filled.Speed to MaterialTheme.colorScheme.primary
                            ActivityType.DOWNLOAD -> Icons.Filled.ArrowDownward to ChartUpload
                            ActivityType.UPLOAD -> Icons.Filled.ArrowUpward to Warning
                        }
                        ActivityRow(icon = icon, title = item.title, time = item.time, accent = accent)
                    }
                }
            }
            VSpacer(24)
        }
    }
}

/**
 * Iran geo-routing CTA: shown only when the device is detected in Iran and the full geo pack
 * (which backs `geosite:ir` / `geoip:ir`) is not installed. Tapping opens the Core manager
 * (Assets) where the geo databases download. Skipped in @Preview (no Hilt graph).
 */
@Composable
private fun IranGeoCta(onOpenAssets: () -> Unit) {
    if (androidx.compose.ui.platform.LocalInspectionMode.current) return
    val vm: com.v2rayez.app.ui.viewmodel.GeoStatusViewModel = hiltViewModel()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val show by vm.showIranGeoCta.collectAsState()
    if (!show) return
    CardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAssets)
                .background(Brush.verticalGradient(listOf(Warning.copy(alpha = 0.14f), Color.Transparent)))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Warning.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = Warning)
            }
            HSpacer(12)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_iran_geo_cta_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.home_iran_geo_cta_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HomeWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(accentGradient(MaterialTheme.colorScheme.primary))),
            contentAlignment = Alignment.Center
        ) {
            Text("V", color = Color.White, fontWeight = FontWeight.Black)
        }
        Text("V2RayEz", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TopIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    cd: String,
    tint: Color,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = cd, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ConnectionStatusCard(
    status: ConnectionStatus,
    server: Server?,
    errorMessage: String?,
    onToggle: () -> Unit,
    onOpenCoreManager: () -> Unit = {}
) {
    val hasError = status == ConnectionStatus.DISCONNECTED && errorMessage != null
    val needsCore = hasError && errorMessage.orEmpty().contains("sing-box", ignoreCase = true)
    val (accent, title) = when {
        hasError -> ErrorRed to stringResource(R.string.home_connection_failed)
        status == ConnectionStatus.CONNECTED -> Connected to stringResource(R.string.home_connected)
        status == ConnectionStatus.CONNECTING -> Warning to stringResource(R.string.home_connecting)
        else -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.home_disconnected)
    }
    CardSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)))
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(accent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = accent)
                    }
                    HSpacer(12)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
                        Text(stringResource(R.string.home_vpn), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PowerButton(status = status, onToggle = onToggle)
                }
                VSpacer(12)
                Text(server?.name ?: stringResource(R.string.home_not_connected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (hasError) {
                    Text(
                        errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed
                    )
                    if (needsCore) {
                        TextButton(onClick = onOpenCoreManager) {
                            Text(stringResource(R.string.home_open_core_manager))
                        }
                    }
                } else {
                    val protocolLabel = server?.protocol?.label.orEmpty()
                    val transportLabel = server?.transport.orEmpty()
                    val securityLabel = server?.security.orEmpty()
                    val subtitle = if (server != null) {
                        listOf(protocolLabel, transportLabel, securityLabel)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ") { it.lowercase() }
                    } else {
                        stringResource(R.string.home_select_server)
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Compact uptime + session totals strip, Tor-style density. */
@Composable
private fun SessionStrip(
    uptimeSeconds: Long,
    sessionDownLabel: String,
    sessionUpLabel: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            Formatters.uptime(uptimeSeconds),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            stringResource(R.string.home_session_strip, sessionDownLabel, sessionUpLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PowerButton(status: ConnectionStatus, onToggle: () -> Unit) {
    val colors = when (status) {
        ConnectionStatus.CONNECTED -> listOf(Connected, Color(0xFF15803D))
        ConnectionStatus.CONNECTING -> listOf(Warning, Color(0xFFB45309))
        ConnectionStatus.DISCONNECTED -> accentGradient(MaterialTheme.colorScheme.primary)
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.PowerSettingsNew, contentDescription = stringResource(R.string.home_toggle_connection), tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun StatGrid(downloadLabel: String, uploadLabel: String, speedLabel: String, pingMs: Int) {
    // IntrinsicSize.Min forces both tiles in a row to share the taller sibling's height, so a
    // longer localized label (FA/RU) never leaves the other tile in the row looking undersized.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            StatTile(Icons.Filled.ArrowDownward, stringResource(R.string.home_download), downloadLabel, Modifier.weight(1f), ChartUpload)
            StatTile(Icons.Filled.ArrowUpward, stringResource(R.string.home_upload), uploadLabel, Modifier.weight(1f), MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            val pingLabel = if (pingMs >= 0) String.format(java.util.Locale.US, "%d ms", pingMs) else "—"
            StatTile(Icons.Filled.NetworkCheck, stringResource(R.string.home_ping), pingLabel, Modifier.weight(1f), Warning)
            StatTile(Icons.Filled.Speed, stringResource(R.string.home_speed), speedLabel, Modifier.weight(1f), Connected)
        }
    }
}

@Composable
private fun WeekPill() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(stringResource(R.string.home_this_week), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrafficLegend(points: List<TrafficPoint>) {
    val downloadMb = points.sumOf { it.download.toDouble() }.toFloat()
    val uploadMb = points.sumOf { it.upload.toDouble() }.toFloat()
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendDot(MaterialTheme.colorScheme.primary, formatTrafficMb(downloadMb))
        LegendDot(ChartUpload, formatTrafficMb(uploadMb))
    }
}

private fun formatTrafficMb(mb: Float): String =
    if (mb >= 1024f) String.format(java.util.Locale.US, "%.2f GB", mb / 1024f)
    else String.format(java.util.Locale.US, "%.0f MB", mb)

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuickActions(
    onServers: () -> Unit,
    onSpeedTest: () -> Unit,
    onTor: () -> Unit,
    onSniTunnel: () -> Unit,
    onFreeServers: () -> Unit
) {
    // Equal weights (instead of unconstrained SpaceBetween) keep 5 buttons evenly spaced on
    // narrow/compact-width phones, where fixed-size icons + SpaceBetween can crowd the edges.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        QuickActionButton(Icons.Filled.Storage, stringResource(R.string.home_action_servers), onServers, Modifier.weight(1f))
        QuickActionButton(Icons.Filled.Public, stringResource(R.string.home_action_free), onFreeServers, Modifier.weight(1f))
        QuickActionButton(Icons.Filled.Bolt, stringResource(R.string.home_action_speed_test), onSpeedTest, Modifier.weight(1f))
        QuickActionButton(Icons.Filled.VpnKey, stringResource(R.string.home_action_tor), onTor, Modifier.weight(1f))
        QuickActionButton(Icons.Filled.Dns, stringResource(R.string.home_action_sni), onSniTunnel, Modifier.weight(1f))
    }
}


@Preview
@Composable
private fun HomeScreenPreview() {
    V2RayEzTheme { HomeScreen(onSeeServers = {}, viewModel = HomeViewModel()) }
}
