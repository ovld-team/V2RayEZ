package com.v2rayez.app.ui.screens.servers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.V2Switch
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.ServerEditorViewModel
import kotlinx.coroutines.launch

private val NETWORKS = listOf("tcp", "ws", "grpc", "h2", "xhttp", "httpupgrade", "quic")
private val SECURITIES = listOf("none", "tls", "reality")

@Composable
fun ServerEditorScreen(
    serverId: String?,
    onBack: () -> Unit,
    viewModel: ServerEditorViewModel = hiltViewModel()
) {
    // Key form state by serverId so navigating new↔edit (or between servers) clears stale fields.
    var existing by remember(serverId) { mutableStateOf<Server?>(null) }
    var name by remember(serverId) { mutableStateOf("") }
    var protocol by remember(serverId) { mutableStateOf(Protocol.VLESS) }
    var host by remember(serverId) { mutableStateOf("") }
    var port by remember(serverId) { mutableStateOf("443") }
    var secret by remember(serverId) { mutableStateOf("") }

    // Advanced fields
    var showAdvanced by remember(serverId) { mutableStateOf(false) }
    var network by remember(serverId) { mutableStateOf("tcp") }
    var security by remember(serverId) { mutableStateOf("none") }
    var sni by remember(serverId) { mutableStateOf("") }
    var alpn by remember(serverId) { mutableStateOf("") }
    var path by remember(serverId) { mutableStateOf("") }
    var requestHost by remember(serverId) { mutableStateOf("") }
    var flow by remember(serverId) { mutableStateOf("") }
    var alterId by remember(serverId) { mutableStateOf("0") }
    var cipher by remember(serverId) { mutableStateOf("") }
    var fingerprint by remember(serverId) { mutableStateOf("") }
    var allowInsecure by remember(serverId) { mutableStateOf(false) }
    var publicKey by remember(serverId) { mutableStateOf("") }
    var shortId by remember(serverId) { mutableStateOf("") }
    var frontProxyId by remember(serverId) { mutableStateOf<String?>(null) }
    var customGroup by remember(serverId) { mutableStateOf("") }
    var preferredCore by remember(serverId) {
        mutableStateOf(com.v2rayez.app.domain.model.CorePreference.SYSTEM)
    }

    val allServers by viewModel.servers.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val invalidMsg = stringResource(R.string.server_editor_invalid)
    val savedMsg = stringResource(R.string.server_editor_saved)

    LaunchedEffect(serverId) {
        if (serverId != null) {
            viewModel.load(serverId)?.let { s ->
                existing = s
                name = s.name
                protocol = s.protocol
                host = s.host.ifBlank { s.address.substringBeforeLast(':', s.address) }
                port = (if (s.port != 0) s.port else 443).toString()
                secret = if (s.password.isNotBlank()) s.password else s.uuid
                network = s.network.ifBlank { "tcp" }
                security = s.streamSecurity.ifBlank { "none" }
                sni = s.sni
                alpn = s.alpn
                path = s.path
                requestHost = s.requestHost
                flow = s.flow
                alterId = s.alterId.toString()
                cipher = s.method
                fingerprint = s.fingerprint
                allowInsecure = s.allowInsecure
                publicKey = s.publicKey
                shortId = s.shortId
                frontProxyId = s.frontProxyId
                customGroup = s.customGroup.orEmpty()
                preferredCore = s.preferredCore
            }
        }
    }

    val usesPassword = protocol == Protocol.TROJAN || protocol == Protocol.SHADOWSOCKS

    Column(Modifier.fillMaxSize()) {
        V2BackTopBar(
            title = stringResource(
                if (serverId == null) R.string.server_editor_new else R.string.server_editor_edit
            ),
            onBack = onBack
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.server_editor_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(10)
            OutlinedTextField(
                value = customGroup,
                onValueChange = { customGroup = it },
                label = { Text(stringResource(R.string.editor_group)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(16)

            SectionHeader(title = stringResource(R.string.server_editor_protocol))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // WireGuard/SSH/DNS-tunnel/Psiphon are import-only (paste a link/.conf); the
                // manual editor covers the share-URI protocols. Keep an imported server's own
                // protocol chip visible so opening it for edit doesn't silently retype it.
                val editable = listOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS)
                val chips = if (protocol in editable) editable else editable + protocol
                chips.forEach { p ->
                    V2FilterChip(p.label, protocol == p) { protocol = p }
                }
            }
            VSpacer(16)

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.server_editor_address)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(16)
            OutlinedTextField(
                value = port,
                onValueChange = { new -> port = new.filter { it.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.server_editor_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(16)
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = {
                    Text(
                        stringResource(
                            if (usesPassword) R.string.server_editor_password else R.string.server_editor_uuid
                        )
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(16)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.editor_advanced), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.editor_advanced_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                V2Switch(showAdvanced, { showAdvanced = it })
            }

            if (showAdvanced) {
                VSpacer(12)
                SectionHeader(title = stringResource(R.string.editor_transport))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NETWORKS.forEach { n -> V2FilterChip(n, network == n) { network = n } }
                }
                VSpacer(12)
                SectionHeader(title = stringResource(R.string.editor_security))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SECURITIES.forEach { s -> V2FilterChip(s, security == s) { security = s } }
                }
                VSpacer(8)

                EditorField(stringResource(R.string.editor_sni), sni) { sni = it }
                EditorField(stringResource(R.string.editor_alpn), alpn) { alpn = it }
                EditorField(
                    stringResource(if (network == "grpc") R.string.editor_grpc_service else R.string.editor_path),
                    path
                ) { path = it }
                EditorField(stringResource(R.string.editor_host_header), requestHost) { requestHost = it }
                EditorField(stringResource(R.string.editor_fingerprint), fingerprint) { fingerprint = it }

                if (protocol == Protocol.VLESS || protocol == Protocol.TROJAN) {
                    EditorField(stringResource(R.string.editor_flow), flow) { flow = it }
                }
                if (protocol == Protocol.VMESS) {
                    EditorField(stringResource(R.string.editor_alterid), alterId, KeyboardType.Number) { alterId = it.filter { c -> c.isDigit() }.take(4) }
                }
                if (protocol == Protocol.VMESS || protocol == Protocol.SHADOWSOCKS) {
                    EditorField(
                        stringResource(if (protocol == Protocol.SHADOWSOCKS) R.string.editor_cipher else R.string.editor_encryption),
                        cipher
                    ) { cipher = it }
                }
                if (security == "reality") {
                    EditorField(stringResource(R.string.editor_reality_pbk), publicKey) { publicKey = it }
                    EditorField(stringResource(R.string.editor_reality_sid), shortId) { shortId = it }
                }

                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.cert_allow_insecure), color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.editor_allow_insecure_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    V2Switch(allowInsecure, { allowInsecure = it })
                }

                VSpacer(8)
                SectionHeader(title = stringResource(R.string.server_preferred_core))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.v2rayez.app.domain.model.CorePreference.entries.forEach { pref ->
                        V2FilterChip(
                            label = pref.label,
                            selected = preferredCore == pref
                        ) { preferredCore = pref }
                    }
                }

                VSpacer(8)
                SectionHeader(title = stringResource(R.string.editor_chain))
                Text(
                    stringResource(R.string.editor_chain_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VSpacer(8)
                ChainPicker(
                    servers = allServers.filter { it.id != existing?.id },
                    selectedId = frontProxyId,
                    onSelect = { frontProxyId = it }
                )
            }
            VSpacer(20)

            PrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    if (name.isBlank() && host.isBlank()) {
                        scope.launch { snackbarHostState.showSnackbar(invalidMsg) }
                    } else {
                        viewModel.save(
                            existing = existing,
                            name = name,
                            protocol = protocol,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 443,
                            secret = secret.trim(),
                            network = network,
                            streamSecurity = security,
                            sni = sni.trim(),
                            alpn = alpn.trim(),
                            path = path.trim(),
                            requestHost = requestHost.trim(),
                            flow = flow.trim(),
                            alterId = alterId.toIntOrNull() ?: 0,
                            method = cipher.trim(),
                            fingerprint = fingerprint.trim(),
                            allowInsecure = allowInsecure,
                            publicKey = publicKey.trim(),
                            shortId = shortId.trim(),
                            frontProxyId = frontProxyId,
                            customGroup = customGroup.trim().takeIf { it.isNotBlank() },
                            preferredCore = preferredCore
                        )
                        scope.launch { snackbarHostState.showSnackbar(savedMsg) }
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(24)
        }
    }

    SnackbarHost(hostState = snackbarHostState)
}

@Composable
private fun ChainPicker(
    servers: List<Server>,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = servers.firstOrNull { it.id == selectedId }?.name ?: stringResource(R.string.editor_none_direct)
    CardSurface(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.editor_front_proxy), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(selectedName, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_none_direct)) },
                onClick = { onSelect(null); expanded = false }
            )
            servers.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.name.ifBlank { s.address }) },
                    onClick = { onSelect(s.id); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Preview
@Composable
private fun ServerEditorScreenPreview() {
    V2RayEzTheme { ServerEditorScreen(serverId = null, onBack = {}, viewModel = ServerEditorViewModel()) }
}
