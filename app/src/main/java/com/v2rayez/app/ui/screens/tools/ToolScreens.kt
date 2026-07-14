package com.v2rayez.app.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.data.sni.SniScanResult
import com.v2rayez.app.data.tor.TorState
import com.v2rayez.app.data.tor.TorStatus
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.DesyncMode
import com.v2rayez.app.domain.model.RoutingMode
import com.v2rayez.app.domain.model.RoutingRule
import com.v2rayez.app.domain.model.RuleOutbound
import com.v2rayez.app.domain.model.RuleProvider
import com.v2rayez.app.domain.model.TorConfig
import com.v2rayez.app.domain.model.TorEngineType
import com.v2rayez.app.domain.model.TorTransport
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.ReconnectBanner
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.SettingSwitchRow
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.V2Switch
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.ErrorRed
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.Warning
import com.v2rayez.app.ui.theme.accentGradient
import com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel
import com.v2rayez.app.ui.viewmodel.SettingsViewModel
import com.v2rayez.app.ui.viewmodel.SpeedTestViewModel
import com.v2rayez.app.ui.viewmodel.ToolsViewModel

@Composable
private fun ToolScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2BackTopBar(title = title, onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            content()
            VSpacer(24)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier
    )
}

// ---------------------------------------------------------------- Routing
@Composable
fun RoutingScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsState()
    val busy by viewModel.providerBusy.collectAsState()
    val message by viewModel.providerMessage.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearProviderMessage()
        }
    }
    LaunchedEffect(Unit) { viewModel.refreshStaleProviders() }

    var editRule by remember { mutableStateOf<RoutingRule?>(null) }
    var showAddRule by remember { mutableStateOf(false) }
    var showAddProvider by remember { mutableStateOf(false) }

    val providerIds = s.routing.providers.map { it.id }.toSet()
    val manualRules = s.routing.rules.filter { it.id !in providerIds }

    ToolScaffold(stringResource(R.string.routing_title), onBack) {
        SectionHeader(title = stringResource(R.string.routing_mode))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutingMode.entries.forEach { m ->
                V2FilterChip(m.label, s.routing.mode == m) { viewModel.setRoutingMode(m) }
            }
        }
        VSpacer(16)
        SectionHeader(title = stringResource(R.string.routing_quick_rules))
        CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column {
                SettingSwitchRow(Icons.Filled.Lan, stringResource(R.string.routing_bypass_lan), s.routing.bypassLan, viewModel::toggleBypassLan)
                SettingSwitchRow(Icons.Filled.Public, stringResource(R.string.routing_bypass_mainland), s.routing.bypassMainland, viewModel::toggleBypassMainland)
                SettingSwitchRow(
                    Icons.Filled.Public,
                    stringResource(R.string.routing_bypass_iran),
                    s.routing.bypassIran,
                    viewModel::toggleBypassIran,
                    subtitle = stringResource(R.string.routing_bypass_iran_sub)
                )
                SettingSwitchRow(Icons.Filled.Block, stringResource(R.string.routing_block_ads), s.routing.blockAds, viewModel::toggleBlockAds)
            }
        }
        VSpacer(16)
        SectionHeader(title = stringResource(R.string.routing_domain_strategy))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("AsIs", "IPIfNonMatch", "IPOnDemand").forEach { strat ->
                V2FilterChip(strat, s.routing.domainStrategy == strat) { viewModel.setDomainStrategy(strat) }
            }
        }

        VSpacer(20)
        SectionHeader(title = stringResource(R.string.routing_custom_rules))
        if (manualRules.isEmpty()) {
            Text(stringResource(R.string.routing_no_custom_rules), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            manualRules.forEach { rule ->
                val realIndex = s.routing.rules.indexOfFirst { it.id == rule.id }
                RuleRow(
                    rule = rule,
                    onToggle = { viewModel.toggleRule(rule.id, it) },
                    onEdit = { editRule = rule },
                    onDelete = { viewModel.removeRule(rule.id) },
                    onUp = { if (realIndex > 0) viewModel.moveRule(realIndex, realIndex - 1) },
                    onDown = { if (realIndex < s.routing.rules.lastIndex) viewModel.moveRule(realIndex, realIndex + 1) }
                )
                VSpacer(8)
            }
        }
        VSpacer(8)
        PrimaryButton(text = stringResource(R.string.routing_add_rule), onClick = { showAddRule = true }, modifier = Modifier.fillMaxWidth())
        VSpacer(12)
        SectionHeader(title = stringResource(R.string.routing_presets))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RULE_PRESETS.forEach { preset ->
                CardSurface(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            viewModel.addRule(preset.rule.copy(id = java.util.UUID.randomUUID().toString()))
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, color = MaterialTheme.colorScheme.onSurface)
                            Text(outboundLabel(preset.rule.outbound), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        VSpacer(20)
        SectionHeader(title = stringResource(R.string.routing_providers))
        if (busy) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("  " + stringResource(R.string.routing_working), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VSpacer(8)
        }
        s.routing.providers.forEach { provider ->
            ProviderRow(
                provider = provider,
                onToggle = { viewModel.toggleProvider(provider.id, it) },
                onRefresh = { viewModel.refreshProvider(provider.id) },
                onDelete = { viewModel.removeProvider(provider.id) }
            )
            VSpacer(8)
        }
        PrimaryButton(text = stringResource(R.string.routing_add_provider), onClick = { showAddProvider = true }, modifier = Modifier.fillMaxWidth())
    }

    if (showAddRule) {
        RuleEditDialog(
            initial = null,
            onDismiss = { showAddRule = false },
            onSave = { viewModel.addRule(it); showAddRule = false }
        )
    }
    editRule?.let { rule ->
        RuleEditDialog(
            initial = rule,
            onDismiss = { editRule = null },
            onSave = { viewModel.updateRule(it); editRule = null }
        )
    }
    if (showAddProvider) {
        ProviderAddDialog(
            onDismiss = { showAddProvider = false },
            onAdd = { name, url, outbound, hours ->
                viewModel.addProvider(name, url, outbound, hours)
                showAddProvider = false
            }
        )
    }
}

private data class RulePreset(val name: String, val rule: RoutingRule)

private val RULE_PRESETS = listOf(
    RulePreset("Block ads (geosite)", RoutingRule(id = "", remark = "Block ads", outbound = RuleOutbound.BLOCK, domains = listOf("geosite:category-ads-all"))),
    RulePreset("Proxy Google", RoutingRule(id = "", remark = "Google", outbound = RuleOutbound.PROXY, domains = listOf("geosite:google"))),
    RulePreset("Proxy Telegram", RoutingRule(id = "", remark = "Telegram", outbound = RuleOutbound.PROXY, ips = listOf("geoip:telegram"))),
    RulePreset("Direct private IPs", RoutingRule(id = "", remark = "Private", outbound = RuleOutbound.DIRECT, ips = listOf("geoip:private"))),
    RulePreset("Block BitTorrent", RoutingRule(id = "", remark = "BitTorrent", outbound = RuleOutbound.BLOCK, protocol = listOf("bittorrent")))
)

private fun outboundLabel(o: RuleOutbound): String = o.label

@Composable
private fun RuleRow(
    rule: RoutingRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    CardSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.remark.ifBlank { stringResource(R.string.routing_rule_default) }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    val summary = buildList {
                        if (rule.domains.isNotEmpty()) add(stringResource(R.string.routing_summary_domains, rule.domains.size))
                        if (rule.ips.isNotEmpty()) add(stringResource(R.string.routing_summary_ips, rule.ips.size))
                        if (rule.port.isNotBlank()) add(stringResource(R.string.routing_summary_port, rule.port))
                        if (rule.protocol.isNotEmpty()) add(rule.protocol.joinToString())
                    }.joinToString(" · ").ifBlank { stringResource(R.string.routing_summary_empty) }
                    Text("${outboundLabel(rule.outbound)} · $summary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                V2Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Row {
                IconButton(onClick = onUp) { Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.common_up), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = onDown) { Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.common_down), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit), tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = ErrorRed) }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: RuleProvider,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    CardSurface(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(provider.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.routing_provider_entries, outboundLabel(provider.outbound), provider.entryCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            V2Switch(checked = provider.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh), tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = ErrorRed) }
        }
    }
}

@Composable
private fun RuleEditDialog(
    initial: RoutingRule?,
    onDismiss: () -> Unit,
    onSave: (RoutingRule) -> Unit
) {
    var remark by remember { mutableStateOf(initial?.remark ?: "") }
    var outbound by remember { mutableStateOf(initial?.outbound ?: RuleOutbound.PROXY) }
    var domains by remember { mutableStateOf(initial?.domains?.joinToString(", ") ?: "") }
    var ips by remember { mutableStateOf(initial?.ips?.joinToString(", ") ?: "") }
    var port by remember { mutableStateOf(initial?.port ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val rule = (initial ?: RoutingRule(id = java.util.UUID.randomUUID().toString())).copy(
                    remark = remark.trim(),
                    outbound = outbound,
                    domains = domains.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    ips = ips.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    port = port.trim()
                )
                onSave(rule)
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(if (initial == null) R.string.routing_add_rule else R.string.routing_edit_rule)) },
        text = {
            Column {
                OutlinedTextField(remark, { remark = it }, label = { Text(stringResource(R.string.routing_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                VSpacer(8)
                Text(stringResource(R.string.routing_outbound), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleOutbound.entries.forEach { o ->
                        V2FilterChip(o.label, outbound == o) { outbound = o }
                    }
                }
                VSpacer(8)
                OutlinedTextField(domains, { domains = it }, label = { Text(stringResource(R.string.routing_domains_hint)) }, modifier = Modifier.fillMaxWidth())
                VSpacer(8)
                OutlinedTextField(ips, { ips = it }, label = { Text(stringResource(R.string.routing_ips_hint)) }, modifier = Modifier.fillMaxWidth())
                VSpacer(8)
                OutlinedTextField(port, { port = it }, label = { Text(stringResource(R.string.routing_port_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    )
}

@Composable
private fun ProviderAddDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, RuleOutbound, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var outbound by remember { mutableStateOf(RuleOutbound.BLOCK) }
    var hours by remember { mutableStateOf("24") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onAdd(name, url, outbound, hours.toIntOrNull() ?: 24)
            }) { Text(stringResource(R.string.action_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.routing_add_provider_title)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.routing_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                VSpacer(8)
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.routing_ruleset_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                VSpacer(8)
                Text(stringResource(R.string.routing_apply_as), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleOutbound.entries.forEach { o ->
                        V2FilterChip(o.label, outbound == o) { outbound = o }
                    }
                }
                VSpacer(8)
                OutlinedTextField(
                    hours, { new -> hours = new.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.routing_update_interval)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ---------------------------------------------------------------- DNS
@Composable
fun DnsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsState()
    var remoteDraft by remember(s.dns.remoteDns) { mutableStateOf(s.dns.remoteDns) }
    var domesticDraft by remember(s.dns.domesticDns) { mutableStateOf(s.dns.domesticDns) }
    ToolScaffold(stringResource(R.string.dns_title), onBack) {
        Text(
            stringResource(R.string.settings_reconnect_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VSpacer(8)
        SectionHeader(title = stringResource(R.string.dns_servers))
        Field(stringResource(R.string.dns_remote), remoteDraft) {
            remoteDraft = it
            viewModel.setRemoteDns(it)
        }
        Field(stringResource(R.string.dns_domestic), domesticDraft) {
            domesticDraft = it
            viewModel.setDomesticDns(it)
        }
        VSpacer(8)
        CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            SettingSwitchRow(Icons.Filled.Router, stringResource(R.string.dns_enable_fakedns), s.dns.enableFakeDns, viewModel::toggleFakeDns)
        }
    }
}

// ---------------------------------------------------------------- Hosts
@Composable
fun HostsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsState()
    var domain by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    ToolScaffold(stringResource(R.string.hosts_title), onBack) {
        SectionHeader(title = stringResource(R.string.hosts_static))
        s.dns.hosts.forEachIndexed { index, h ->
            CardSurface(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.domain, color = MaterialTheme.colorScheme.onSurface)
                        Text(h.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.removeHost(index) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.common_remove), tint = ErrorRed)
                    }
                }
            }
        }
        VSpacer(12)
        Field(stringResource(R.string.hosts_domain), domain) { domain = it }
        Field(stringResource(R.string.hosts_maps_to), value) { value = it }
        VSpacer(8)
        PrimaryButton(stringResource(R.string.hosts_add_mapping), onClick = {
            if (domain.isNotBlank() && value.isNotBlank()) {
                viewModel.addHost(domain.trim(), value.trim()); domain = ""; value = ""
            }
        }, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- App Proxy
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppProxyScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    toolsViewModel: ToolsViewModel = hiltViewModel()
) {
    val s by viewModel.state.collectAsState()
    val conn by viewModel.connectionState.collectAsState()
    val apps by toolsViewModel.apps.collectAsState()
    val loading by toolsViewModel.appsLoading.collectAsState()
    LaunchedEffect(Unit) { toolsViewModel.loadApps() }

    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isBlank()) apps
        else apps.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
    }
    val vpnUp = conn.status == ConnectionStatus.CONNECTED || conn.status == ConnectionStatus.CONNECTING

    Column(Modifier.fillMaxSize()) {
        V2BackTopBar(title = stringResource(R.string.appproxy_title), onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column {
                    SettingSwitchRow(Icons.Filled.Lan, stringResource(R.string.appproxy_enable), s.appProxy.enabled, viewModel::setAppProxyEnabled)
                    SettingSwitchRow(
                        Icons.Filled.Block,
                        stringResource(if (s.appProxy.bypassMode) R.string.appproxy_mode_bypass else R.string.appproxy_mode_only),
                        s.appProxy.bypassMode,
                        viewModel::setAppProxyBypassMode
                    )
                    val conflict = remember(s.fullDeviceTunnel, s.appProxy) {
                        com.v2rayez.app.data.vpn.PerAppTunnelPolicy.conflictMessage(s)
                    }
                    if (conflict != null) {
                        Text(
                            text = if (s.fullDeviceTunnel && s.appProxy.enabled) {
                                stringResource(R.string.appproxy_conflict_full_tunnel)
                            } else {
                                stringResource(R.string.appproxy_empty_allow_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Warning,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            if (vpnUp && s.appProxy.enabled) {
                VSpacer(8)
                ReconnectBanner(
                    hint = stringResource(R.string.appproxy_reconnect_hint),
                    actionLabel = stringResource(R.string.settings_reconnect_now),
                    onReconnect = viewModel::reconnectVpn
                )
            }
            VSpacer(8)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.appproxy_search_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(8)
            Text(
                stringResource(R.string.appproxy_selected, s.appProxy.packages.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(4)
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { toolsViewModel.loadApps(force = true) },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (filtered.isEmpty() && !loading) {
                    item {
                        Text(
                            stringResource(R.string.appproxy_no_apps),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
                items(filtered, key = { it.packageName }) { app ->
                    AppProxyRow(
                        app = app,
                        checked = app.packageName in s.appProxy.packages,
                        onToggle = { viewModel.toggleAppPackage(app.packageName) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

/** Split-tunnel app row: real launcher icon (falls back to a glyph), label, package, switch. */
@Composable
private fun AppProxyRow(
    app: com.v2rayez.app.ui.viewmodel.InstalledApp,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (app.icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Icon(
                    if (app.isSystem) Icons.Filled.Router else Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        HSpacer(12)
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        HSpacer(8)
        V2Switch(checked = checked, onCheckedChange = onToggle)
    }
}

// ---------------------------------------------------------------- SNI Tunnel
@Composable
fun SniTunnelScreen(onBack: () -> Unit, viewModel: com.v2rayez.app.ui.viewmodel.SniViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsState()
    val results by viewModel.results.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val candidates by viewModel.candidates.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val sni = s.sni
    val desync = s.desync
    val byedpiAvailable = remember { viewModel.byedpiAvailable() }
    var showAdvanced by remember { mutableStateOf(false) }

    ToolScaffold(stringResource(R.string.sni_title), onBack) {
        Text(
            stringResource(R.string.sni_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VSpacer(12)

        // Reconnect prompt: SNI/desync changes only take effect on the next connection.
        if (connected) {
            ReconnectBanner(
                hint = stringResource(R.string.sni_reconnect_hint),
                actionLabel = stringResource(R.string.sni_reconnect_now),
                onReconnect = viewModel::reconnect
            )
            VSpacer(12)
        }

        // SNI Lab (UAC-style): Scan / Stop / Saved + live result cards.
        SectionHeader(title = stringResource(R.string.sni_lab_title), trailing = {
            Text(
                stringResource(R.string.sni_domains_count, candidates.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        })
        val showSaved by viewModel.showSaved.collectAsState()
        val saved by viewModel.saved.collectAsState()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (scanning) {
                PrimaryButton(
                    stringResource(R.string.sni_stop),
                    onClick = viewModel::stopScan,
                    modifier = Modifier.weight(1f)
                )
            } else {
                PrimaryButton(
                    stringResource(R.string.sni_scan_best),
                    onClick = { viewModel.scan() },
                    modifier = Modifier.weight(1f)
                )
            }
            androidx.compose.material3.OutlinedButton(
                onClick = { viewModel.setShowSaved(!showSaved) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(if (showSaved) R.string.sni_results_tab else R.string.sni_saved_tab))
            }
        }
        VSpacer(8)
        val (done, total, okCount) = progress
        if (scanning || total > 0) {
            Text(
                stringResource(R.string.sni_lab_progress, done, total, okCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (scanning) {
                VSpacer(6)
                val fraction = if (total > 0) done.toFloat() / total else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            VSpacer(8)
        }
        val displayList = if (showSaved) saved else results
        if (displayList.isNotEmpty()) {
            Text(
                stringResource(R.string.sni_tap_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(4)
            displayList.take(40).forEachIndexed { index, r ->
                SniLabResultCard(
                    index = index + 1,
                    result = r,
                    isActive = s.domainFront.fakeSni.equals(r.domain, ignoreCase = true) ||
                        s.domainFront.frontDomain.equals(r.domain, ignoreCase = true),
                    onApply = { viewModel.applyFronting(r) },
                    onSave = { viewModel.saveResult(r) },
                    showSave = !showSaved
                )
                VSpacer(8)
            }
        }
        VSpacer(16)

        // Desync / manipulation kept under Advanced so Lab stays primary.
        SectionHeader(title = stringResource(R.string.sni_tuning_mode))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.v2rayez.app.domain.model.SniTuningMode.entries.forEach { m ->
                V2FilterChip(m.label, sni.mode == m) { viewModel.setMode(m) }
            }
        }
        VSpacer(12)

        SectionHeader(
            title = stringResource(R.string.sni_advanced),
            trailing = {
                Icon(
                    if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.sni_advanced),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).clickable { showAdvanced = !showAdvanced }
                )
            }
        )
        if (showAdvanced) {
            SectionHeader(title = stringResource(R.string.sni_desync_engine), trailing = {
                Text(
                    stringResource(if (byedpiAvailable) R.string.sni_native_available else R.string.sni_native_unavailable),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (byedpiAvailable) Connected else Warning
                )
            })
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                SettingSwitchRow(
                    Icons.Filled.Shield, stringResource(R.string.sni_enable_desync), desync.enabled, viewModel::toggleDesync,
                    subtitle = stringResource(R.string.sni_enable_desync_sub)
                )
            }
            VSpacer(8)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DesyncMode.entries.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m ->
                            V2FilterChip(m.label, desync.mode == m, modifier = Modifier.weight(1f)) {
                                viewModel.setDesyncMode(m)
                            }
                        }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
            if (desync.mode != DesyncMode.NONE) {
                Field(stringResource(R.string.sni_split_pos), desync.splitPos.toString(), KeyboardType.Number) {
                    it.toIntOrNull()?.let(viewModel::setSplitPos)
                }
            }
            if (desync.mode == DesyncMode.FAKE) {
                Field(stringResource(R.string.sni_fake_ttl), desync.fakeTtl.toString(), KeyboardType.Number) {
                    it.toIntOrNull()?.let(viewModel::setFakeTtl)
                }
            }
            VSpacer(12)
            SectionHeader(title = stringResource(R.string.sni_manipulation))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column {
                    SettingSwitchRow(Icons.Filled.Router, stringResource(R.string.sni_split), sni.splitEnabled, viewModel::toggleSplit)
                    SettingSwitchRow(Icons.Filled.VisibilityOff, stringResource(R.string.sni_omit), sni.omitEnabled, viewModel::toggleOmit)
                    SettingSwitchRow(Icons.Filled.SwapHoriz, stringResource(R.string.sni_spoof), sni.spoofEnabled, viewModel::toggleSpoof)
                }
            }
            if (sni.spoofEnabled) {
                VSpacer(8)
                Field(stringResource(R.string.sni_spoof_domain), sni.spoofDomain) { viewModel.setSpoofDomain(it) }
            }
            if (sni.mode == com.v2rayez.app.domain.model.SniTuningMode.CUSTOM) {
                SectionHeader(title = stringResource(R.string.sni_fragmentation))
                Field(stringResource(R.string.sni_length_range), s.fragment.length) { viewModel.setFragmentLength(it) }
                Field(stringResource(R.string.sni_interval_range), s.fragment.interval) { viewModel.setFragmentInterval(it) }
            }
            SectionHeader(title = stringResource(R.string.sni_candidate_domains))
            Text(
                if (sni.candidateDomains.isEmpty()) stringResource(R.string.sni_using_bundled, candidates.size)
                else stringResource(R.string.sni_custom_domains, sni.candidateDomains.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(6)
            OutlinedTextField(
                value = if (sni.candidateDomains.isEmpty()) "" else sni.candidateDomains.joinToString("\n"),
                onValueChange = { viewModel.setCandidates(it) },
                placeholder = { Text(stringResource(R.string.sni_one_per_line)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }
    }
}

/** UAC-style scan result card with Apply + SAVE. */
@Composable
private fun SniLabResultCard(
    index: Int,
    result: SniScanResult,
    isActive: Boolean,
    onApply: () -> Unit,
    onSave: () -> Unit,
    showSave: Boolean
) {
    CardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onApply),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(java.util.Locale.US, "%02d", index),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HSpacer(8)
                Text(
                    result.domain,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Text(
                    stringResource(R.string.sni_ok),
                    color = Connected,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                if (showSave) {
                    HSpacer(8)
                    TextButton(onClick = onSave) {
                        Text(stringResource(R.string.sni_save))
                    }
                }
            }
            VSpacer(6)
            Text(
                stringResource(
                    R.string.sni_lab_ip_line,
                    result.resolvedIp.ifBlank { "N/A" },
                    result.cfIp.ifBlank { "—" }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(
                    R.string.sni_lab_meta_line,
                    result.pingMs,
                    result.stability,
                    listOfNotNull(result.colo.takeIf { it.isNotBlank() }, result.country.takeIf { it.isNotBlank() })
                        .joinToString(" ").ifBlank { "—" }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One tappable SNI scan result (legacy helper kept for previews). */
@Composable
private fun SniResultRow(r: SniScanResult, isBest: Boolean, isActive: Boolean, onApply: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onApply)
            .background(if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.sni_active), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    HSpacer(4)
                } else if (isBest) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.sni_best), tint = Connected, modifier = Modifier.size(14.dp))
                    HSpacer(4)
                }
                Text(
                    r.domain,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isBest || isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
            val meta = listOfNotNull(
                r.colo.takeIf { it.isNotBlank() },
                r.country.takeIf { it.isNotBlank() },
                r.cfIp.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.sni_ms, r.pingMs), color = if (r.success) Connected else ErrorRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.sni_stable, r.stability), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Tor
@Composable
fun TorScreen(onBack: () -> Unit, viewModel: com.v2rayez.app.ui.viewmodel.TorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsState()
    val status by viewModel.status.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val probeLog by viewModel.probeLog.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val vpnPermission = com.v2rayez.app.ui.LocalVpnPermission.current
    // Recompute when the Tor state changes: a PT binary may become usable after a
    // start attempt, so don't freeze this list for the lifetime of the screen.
    val availableTransports = remember(status.state) { viewModel.availableTransports() }

    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    TorContent(
        tor = s.tor,
        status = status,
        busy = busy,
        probeLog = probeLog,
        availableTransports = availableTransports,
        onBack = onBack,
        onToggleEnabled = viewModel::setEnabled,
        onSetRouteAllDevice = { enable ->
            if (enable) {
                vpnPermission.request { viewModel.setRouteAllDevice(true) }
            } else {
                viewModel.setRouteAllDevice(false)
            }
        },
        // setTransport already restarts Tor when it's live — don't double-restart.
        onSetTransport = { viewModel.setTransport(it) },
        onSetBridges = viewModel::setBridges,
        onGetNewBridges = {
            viewModel.getNewBridges { count ->
                android.widget.Toast.makeText(context, context.getString(R.string.tor_fetched_bridges, count), android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onAutoSetup = {
            viewModel.autoSetup { ok ->
                val msg = if (ok) context.getString(R.string.tor_autosetup_ok) else context.getString(R.string.tor_autosetup_failed)
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onTestConnection = {
            viewModel.testConnection { ok ->
                val msg = if (ok) context.getString(R.string.tor_test_ok) else context.getString(R.string.tor_test_failed)
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onToggleAutoRotate = viewModel::toggleAutoRotate,
        onSetSocksPort = viewModel::setSocksPort
    )
}

/** Stateless Tor UI, styled to match the Home screen's card language. */
@Composable
private fun TorContent(
    tor: TorConfig,
    status: TorStatus,
    busy: Boolean,
    probeLog: List<String> = emptyList(),
    availableTransports: List<TorTransport>,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSetRouteAllDevice: (Boolean) -> Unit = {},
    onSetTransport: (TorTransport) -> Unit,
    onSetBridges: (String) -> Unit,
    onGetNewBridges: () -> Unit,
    onAutoSetup: () -> Unit,
    onTestConnection: () -> Unit,
    onToggleAutoRotate: (Boolean) -> Unit,
    onSetSocksPort: (Int) -> Unit
) {
    var bridgesText by remember(tor.bridges) { mutableStateOf(tor.bridges.joinToString("\n")) }

    ToolScaffold(stringResource(R.string.tor_title), onBack) {
        TorHeroCard(tor = tor, status = status, onToggle = { onToggleEnabled(!tor.enabled) })
        VSpacer(16)

        CardSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            SettingSwitchRow(
                Icons.Filled.Public,
                stringResource(R.string.tor_route_all),
                tor.routeAllDevice,
                onSetRouteAllDevice,
                subtitle = stringResource(R.string.tor_route_all_sub)
            )
        }
        VSpacer(16)

        // One-tap: auto-probe transports and bridges.
        PrimaryButton(
            stringResource(if (busy) R.string.tor_autosetup_running else R.string.tor_autosetup),
            onClick = onAutoSetup,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )
        VSpacer(8)
        TextButton(
            onClick = onTestConnection,
            enabled = !busy && tor.enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.tor_test_connection))
        }
        if (probeLog.isNotEmpty()) {
            VSpacer(12)
            IconSectionHeader(Icons.Filled.Article, stringResource(R.string.tor_probe_log))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    probeLog.takeLast(12).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        VSpacer(20)

        // Engine (single embedded native C tor core; no picker).
        IconSectionHeader(Icons.Filled.Hub, stringResource(R.string.tor_engine))
        CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                HSpacer(12)
                Column(Modifier.weight(1f)) {
                    Text(tor.engine.label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.tor_engine_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        VSpacer(20)

        // Transport / bypass picker.
        IconSectionHeader(Icons.Filled.Shield, stringResource(R.string.tor_bypass_transport))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TorTransport.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        val available = t in availableTransports
                        val label = if (available) t.label else stringResource(R.string.tor_transport_na, t.label)
                        V2FilterChip(
                            label = label,
                            selected = tor.transport == t,
                            modifier = Modifier.weight(1f),
                            enabled = available
                        ) {
                            if (available) onSetTransport(t)
                        }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
            if (TorTransport.entries.any { it !in availableTransports }) {
                Text(
                    stringResource(R.string.tor_pt_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        VSpacer(20)

        // Bridges editor.
        IconSectionHeader(Icons.Filled.Lan, stringResource(R.string.tor_bridges))
        CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp)) {
                OutlinedTextField(
                    value = bridgesText,
                    onValueChange = { bridgesText = it; onSetBridges(it) },
                    label = { Text(stringResource(R.string.tor_bridge_lines)) },
                    modifier = Modifier.fillMaxWidth()
                )
                VSpacer(10)
                PrimaryButton(stringResource(R.string.tor_get_bridges), onClick = onGetNewBridges, modifier = Modifier.fillMaxWidth())
            }
        }
        VSpacer(20)

        // Bootstrap options + SOCKS port.
        IconSectionHeader(Icons.Filled.Router, stringResource(R.string.tor_connection))
        CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column {
                SettingSwitchRow(Icons.Filled.Refresh, stringResource(R.string.tor_auto_rotate), tor.autoRotateBridges, onToggleAutoRotate)
                Field(
                    stringResource(R.string.tor_socks_port),
                    tor.socksPort.toString(),
                    KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                ) { it.toIntOrNull()?.let(onSetSocksPort) }
                VSpacer(8)
            }
        }
    }
}

/** Home-style gradient hero: state, engine, message + power toggle. */
@Composable
private fun TorHeroCard(tor: TorConfig, status: TorStatus, onToggle: () -> Unit) {
    val (accent, title) = when (status.state) {
        TorState.CONNECTED -> Connected to stringResource(R.string.home_connected)
        TorState.BOOTSTRAPPING -> Warning to stringResource(R.string.tor_bootstrapping, status.bootstrapPercent)
        TorState.STARTING -> Warning to stringResource(R.string.tor_starting)
        TorState.ERROR -> ErrorRed to stringResource(R.string.home_connection_failed)
        TorState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(if (tor.enabled) R.string.tor_idle else R.string.home_disconnected)
    }
    CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
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
                        Text(stringResource(R.string.tor_hero_engine, tor.engine.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TorPowerButton(enabled = tor.enabled, accent = accent, onToggle = onToggle)
                }
                VSpacer(12)
                Text(
                    status.message.ifBlank {
                        stringResource(if (tor.enabled) R.string.tor_msg_on else R.string.tor_msg_off)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (status.state == TorState.BOOTSTRAPPING) {
                    VSpacer(10)
                    LinearProgressIndicator(
                        progress = { status.bootstrapPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TorPowerButton(enabled: Boolean, accent: Color, onToggle: () -> Unit) {
    val colors = if (enabled) listOf(accent, androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.24f))
    else accentGradient(MaterialTheme.colorScheme.primary)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.PowerSettingsNew, contentDescription = stringResource(R.string.tor_toggle), tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** Section header with a small tinted icon badge, matching the app's card rhythm. */
@Composable
private fun IconSectionHeader(icon: ImageVector, title: String, accent: Color = MaterialTheme.colorScheme.primary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        HSpacer(10)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview
@Composable
private fun TorScreenPreview() {
    V2RayEzTheme {
        TorContent(
            tor = TorConfig(enabled = true, engine = TorEngineType.NATIVE_C, transport = TorTransport.VANILLA),
            status = TorStatus(TorState.BOOTSTRAPPING, 43, "Bootstrapped 43%: Loading relay descriptors", TorEngineType.NATIVE_C),
            busy = false,
            availableTransports = listOf(TorTransport.DIRECT, TorTransport.VANILLA),
            onBack = {}, onToggleEnabled = {}, onSetTransport = {},
            onSetBridges = {}, onGetNewBridges = {}, onAutoSetup = {}, onTestConnection = {},
            onToggleAutoRotate = {}, onSetSocksPort = {}
        )
    }
}

// ---------------------------------------------------------------- Certificates
@Composable
fun CertificatesScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsState()
    var sha by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val certLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val intent = com.v2rayez.app.data.cert.CertInstaller.installIntentFromUri(context, uri)
            if (intent != null) {
                runCatching { context.startActivity(intent) }
                    .onFailure { android.widget.Toast.makeText(context, context.getString(R.string.cert_unable), android.widget.Toast.LENGTH_SHORT).show() }
            } else {
                android.widget.Toast.makeText(context, context.getString(R.string.cert_invalid), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    ToolScaffold(stringResource(R.string.cert_title), onBack) {
        SectionHeader(title = stringResource(R.string.cert_install))
        Text(
            stringResource(R.string.cert_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VSpacer(8)
        PrimaryButton(stringResource(R.string.cert_install_button), onClick = {
            certLauncher.launch("*/*")
        }, modifier = Modifier.fillMaxWidth())
        VSpacer(16)

        CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            SettingSwitchRow(
                Icons.Filled.Block, stringResource(R.string.cert_allow_insecure), s.tls.allowInsecure, viewModel::toggleAllowInsecure,
                subtitle = stringResource(R.string.cert_allow_insecure_sub)
            )
        }
        VSpacer(16)
        SectionHeader(title = stringResource(R.string.cert_pinned))
        s.tls.pinnedSha256.forEachIndexed { index, fp ->
            CardSurface(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(fp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.removePinnedCert(index) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.common_remove), tint = ErrorRed)
                    }
                }
            }
        }
        VSpacer(8)
        Field(stringResource(R.string.cert_add_fingerprint), sha) { sha = it }
        VSpacer(8)
        PrimaryButton(stringResource(R.string.cert_add_pin), onClick = {
            if (sha.isNotBlank()) { viewModel.addPinnedCert(sha.trim()); sha = "" }
        }, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- Diagnostics
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    ToolScaffold(stringResource(R.string.diag_title), onBack) {
        PrimaryButton(
            stringResource(if (state.running) R.string.diag_running else R.string.diag_run),
            onClick = viewModel::run,
            modifier = Modifier.fillMaxWidth()
        )
        VSpacer(16)
        if (state.sections.isEmpty()) {
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Text(
                    stringResource(R.string.diag_no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        state.sections.forEach { section ->
            SectionHeader(title = diagSectionTitle(section.id))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    section.checks.forEach { check -> DiagnosticCheckRow(check) }
                }
            }
            VSpacer(14)
        }
        if (state.sections.isNotEmpty() && !state.running) {
            PrimaryButton(
                stringResource(R.string.diag_copy_report),
                onClick = {
                    val report = buildDiagnosticsReport(context, state)
                    val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", report))
                    android.widget.Toast.makeText(context, context.getString(R.string.diag_copied), android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DiagnosticCheckRow(check: com.v2rayez.app.ui.viewmodel.DiagnosticCheck) {
    var expanded by remember(check.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            // Locale-independent status marker (device-lab adb/Maestro flows key off this
            // instead of the translated PASS/WARN/FAIL copy, which only exists as color+icon).
            .semantics { contentDescription = "diag_status:${check.id}:${check.status.name}" }
            .let { if (check.detail != null) it.clickable { expanded = !expanded } else it }
            .padding(vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            when (check.status) {
                com.v2rayez.app.ui.viewmodel.CheckStatus.RUNNING ->
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                com.v2rayez.app.ui.viewmodel.CheckStatus.PASS ->
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Connected, modifier = Modifier.size(18.dp))
                com.v2rayez.app.ui.viewmodel.CheckStatus.WARN ->
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                com.v2rayez.app.ui.viewmodel.CheckStatus.FAIL ->
                    Icon(Icons.Filled.Close, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(18.dp))
                com.v2rayez.app.ui.viewmodel.CheckStatus.SKIPPED ->
                    Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            HSpacer(12)
            Column(Modifier.weight(1f)) {
                Text(diagCheckLabel(check.id), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (check.durationMs >= 0) {
                    Text("${check.durationMs} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                check.result,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = when (check.status) {
                    com.v2rayez.app.ui.viewmodel.CheckStatus.PASS -> Connected
                    com.v2rayez.app.ui.viewmodel.CheckStatus.WARN -> Warning
                    com.v2rayez.app.ui.viewmodel.CheckStatus.FAIL -> ErrorRed
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (expanded && check.detail != null) {
            VSpacer(4)
            Text(
                check.detail!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp)
            )
        }
    }
}

@Composable
private fun diagSectionTitle(id: String): String = stringResource(
    when (id) {
        com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.SEC_CONNECTIVITY -> R.string.diag_sec_connectivity
        com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.SEC_TUNNEL -> R.string.diag_sec_tunnel
        com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.SEC_SERVER -> R.string.diag_sec_server
        com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.SEC_TOR -> R.string.diag_sec_tor
        com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.SEC_SNI -> R.string.diag_sec_sni
        com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.SEC_SYSTEM -> R.string.diag_sec_system
        else -> R.string.diag_sec_core
    }
)

@Composable
private fun diagCheckLabel(id: String): String = stringResource(diagCheckLabelRes(id))

private fun diagCheckLabelRes(id: String): Int = when (id) {
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_INTERNET -> R.string.diag_check_internet
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_DNS -> R.string.diag_check_dns
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_PUBLIC_IP -> R.string.diag_check_public_ip
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_TUNNEL_LATENCY -> R.string.diag_check_tunnel_latency
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_TUNNEL_IP -> R.string.diag_check_tunnel_ip
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_SESSION_TRAFFIC -> R.string.diag_check_session_traffic
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_SERVER_RTT -> R.string.diag_check_server_rtt
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_TOR_BOOTSTRAP -> R.string.diag_check_tor_bootstrap
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_TOR_SOCKS -> R.string.diag_check_tor_socks
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_TOR_EXIT_IP -> R.string.diag_check_tor_exit
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_SNI_PROBE -> R.string.diag_check_sni
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_TOR_PACKS -> R.string.diag_check_tor_packs
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_HOTSPOT -> R.string.diag_check_hotspot
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_ALWAYS_ON -> R.string.diag_check_always_on
    com.v2rayez.app.ui.viewmodel.DiagnosticsViewModel.ID_PENDING_ADDONS -> R.string.diag_check_pending_addons
    else -> R.string.diag_check_core_version
}

private fun buildDiagnosticsReport(context: android.content.Context, state: com.v2rayez.app.ui.viewmodel.DiagnosticsUiState): String =
    buildString {
        appendLine("V2RayEz diagnostics report")
        state.sections.forEach { section ->
            appendLine()
            section.checks.forEach { c ->
                val label = context.getString(diagCheckLabelRes(c.id))
                appendLine("[${c.status}] $label: ${c.result}" + (c.detail?.let { " — $it" } ?: ""))
            }
        }
    }

// ---------------------------------------------------------------- Speed Test
@Composable
fun SpeedTestScreen(onBack: () -> Unit, viewModel: SpeedTestViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ToolScaffold(stringResource(R.string.speed_title), onBack) {
        // Direct / Through-VPN mode toggle
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            V2FilterChip(stringResource(R.string.speed_direct), !state.useTunnel) { viewModel.setUseTunnel(false) }
            if (state.tunnelAvailable) {
                V2FilterChip(stringResource(R.string.speed_via_vpn), state.useTunnel) { viewModel.setUseTunnel(true) }
            }
        }
        if (state.useTunnel && state.serverName != null) {
            VSpacer(6)
            Text(
                "${stringResource(R.string.speed_server)}: ${state.serverName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        VSpacer(12)

        CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val gaugeValue = when {
                    state.running -> state.liveMbps
                    state.downloadMbps >= 0 -> state.downloadMbps
                    else -> 0.0
                }
                SpeedGauge(mbps = gaugeValue)
                VSpacer(8)
                val phaseLabel = when (state.phase) {
                    com.v2rayez.app.ui.viewmodel.SpeedTestPhase.LATENCY -> stringResource(R.string.speed_phase_latency)
                    com.v2rayez.app.ui.viewmodel.SpeedTestPhase.DOWNLOAD -> stringResource(R.string.speed_phase_download)
                    com.v2rayez.app.ui.viewmodel.SpeedTestPhase.UPLOAD -> stringResource(R.string.speed_phase_upload)
                    com.v2rayez.app.ui.viewmodel.SpeedTestPhase.DONE -> stringResource(R.string.speed_done)
                    com.v2rayez.app.ui.viewmodel.SpeedTestPhase.ERROR -> state.error ?: stringResource(R.string.speed_done)
                    else -> stringResource(R.string.speed_idle)
                }
                Text(
                    phaseLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.phase == com.v2rayez.app.ui.viewmodel.SpeedTestPhase.ERROR) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.running) {
                    VSpacer(10)
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (state.samples.size >= 2) {
                    VSpacer(12)
                    SpeedSamplesGraph(samples = state.samples)
                }
            }
        }
        VSpacer(16)

        // Results
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedResultTile(stringResource(R.string.speed_result_ping), if (state.pingMs >= 0) "${state.pingMs} ms" else "—", Modifier.weight(1f))
            SpeedResultTile(stringResource(R.string.speed_result_jitter), if (state.jitterMs >= 0) "${state.jitterMs} ms" else "—", Modifier.weight(1f))
        }
        VSpacer(12)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedResultTile(
                stringResource(R.string.speed_result_download),
                if (state.downloadMbps >= 0) formatMbps(state.downloadMbps) else "—",
                Modifier.weight(1f)
            )
            SpeedResultTile(
                stringResource(R.string.speed_result_upload),
                if (state.uploadMbps >= 0) formatMbps(state.uploadMbps) else "—",
                Modifier.weight(1f)
            )
        }
        VSpacer(16)

        PrimaryButton(
            stringResource(if (state.running) R.string.speed_stop else R.string.speed_start),
            onClick = { if (state.running) viewModel.stop() else viewModel.start() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatMbps(mbps: Double): String = String.format(java.util.Locale.US, "%.1f Mbps", mbps)

/** Semicircular gauge with a log-scaled needleless arc + big center number. */
@Composable
private fun SpeedGauge(mbps: Double) {
    val fraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = (kotlin.math.log10(1.0 + mbps) / kotlin.math.log10(1.0 + 300.0)).toFloat().coerceIn(0f, 1f),
        label = "gauge"
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    Box(Modifier.size(210.dp, 150.dp), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.width - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
            drawArc(
                color = track,
                startAngle = 160f, sweepAngle = 220f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            drawArc(
                color = active,
                startAngle = 160f, sweepAngle = 220f * fraction, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 18.dp)) {
            Text(
                String.format(java.util.Locale.US, "%.1f", mbps),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(stringResource(R.string.speed_mbps), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Small live bar graph of throughput samples while a phase runs. */
@Composable
private fun SpeedSamplesGraph(samples: List<Float>) {
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(44.dp)
    ) {
        val max = (samples.maxOrNull() ?: 1f).coerceAtLeast(0.1f)
        val slot = size.width / samples.size
        samples.forEachIndexed { i, v ->
            val h = (v / max) * size.height
            drawRoundRect(
                color = color.copy(alpha = 0.75f),
                topLeft = androidx.compose.ui.geometry.Offset(i * slot + slot * 0.15f, size.height - h),
                size = androidx.compose.ui.geometry.Size(slot * 0.7f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )
        }
    }
}

@Composable
private fun SpeedResultTile(label: String, value: String, modifier: Modifier = Modifier) {
    CardSurface(modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            VSpacer(6)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
