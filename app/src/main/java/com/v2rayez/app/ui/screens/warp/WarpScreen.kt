package com.v2rayez.app.ui.screens.warp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.WarpMode
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.SettingSwitchRow
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.WarpViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power

@Composable
fun WarpScreen(
    onBack: () -> Unit,
    viewModel: WarpViewModel = hiltViewModel()
) {
    val settings by viewModel.state.collectAsState()
    val registering by viewModel.registering.collectAsState()
    val message by viewModel.message.collectAsState()
    val warp = settings.warp

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Column(Modifier.fillMaxSize()) {
        V2BackTopBar(title = "Cloudflare WARP", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                stringResource(R.string.warp_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(8)
            Text(
                stringResource(R.string.settings_reconnect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(16)

            CardSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (warp.configured) stringResource(R.string.warp_configured) else stringResource(R.string.warp_not_registered),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (warp.configured) {
                        VSpacer(6)
                        Text(stringResource(R.string.warp_endpoint, warp.endpoint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.warp_addresses, warp.addresses.joinToString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (warp.deviceId.isNotBlank()) {
                            Text(stringResource(R.string.warp_device, warp.deviceId.take(12)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            VSpacer(16)

            PrimaryButton(
                text = if (registering) stringResource(R.string.warp_registering) else stringResource(R.string.warp_register_auto),
                onClick = { viewModel.register() },
                enabled = !registering,
                modifier = Modifier.fillMaxWidth()
            )
            if (registering) {
                VSpacer(12)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
                }
            }
            VSpacer(16)

            CardSurface(Modifier.fillMaxWidth()) {
                SettingSwitchRow(
                    icon = Icons.Filled.Power,
                    title = stringResource(R.string.warp_use),
                    subtitle = if (warp.configured) stringResource(R.string.warp_use_sub) else stringResource(R.string.warp_register_first),
                    checked = warp.enabled,
                    onCheckedChange = { viewModel.setEnabled(it) }
                )
            }
            VSpacer(16)

            SectionHeader(title = stringResource(R.string.routing_mode))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WarpMode.entries.forEach { m ->
                    V2FilterChip(warpModeLabel(m), warp.mode == m) { viewModel.setMode(m) }
                }
            }
            VSpacer(24)

            SectionHeader(title = stringResource(R.string.warp_manual_title))
            Text(
                stringResource(R.string.warp_manual_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(8)
            ManualWarpEditor(
                privateKey = warp.privateKey,
                addresses = warp.addresses.joinToString(", "),
                endpoint = warp.endpoint,
                reserved = warp.reserved.joinToString(", "),
                onApply = { pk, addrs, ep, res ->
                    viewModel.setManual(
                        warp.copy(
                            privateKey = pk.trim(),
                            addresses = addrs.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                .ifEmpty { listOf("172.16.0.2/32") },
                            endpoint = ep.trim().ifBlank { "engage.cloudflareclient.com:2408" },
                            reserved = res.split(",").mapNotNull { it.trim().toIntOrNull() }
                        )
                    )
                }
            )
            VSpacer(24)
        }
    }
    SnackbarHost(hostState = snackbarHostState)
}

@Composable
private fun ManualWarpEditor(
    privateKey: String,
    addresses: String,
    endpoint: String,
    reserved: String,
    onApply: (String, String, String, String) -> Unit
) {
    var pk by remember { mutableStateOf(privateKey) }
    var addr by remember { mutableStateOf(addresses) }
    var ep by remember { mutableStateOf(endpoint) }
    var res by remember { mutableStateOf(reserved) }
    Column {
        OutlinedTextField(pk, { pk = it }, label = { Text(stringResource(R.string.warp_private_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        VSpacer(8)
        OutlinedTextField(addr, { addr = it }, label = { Text(stringResource(R.string.warp_addresses_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        VSpacer(8)
        OutlinedTextField(ep, { ep = it }, label = { Text(stringResource(R.string.warp_endpoint_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        VSpacer(8)
        OutlinedTextField(res, { res = it }, label = { Text(stringResource(R.string.warp_reserved_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        VSpacer(12)
        PrimaryButton(text = stringResource(R.string.warp_apply_manual), onClick = { onApply(pk, addr, ep, res) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun warpModeLabel(mode: WarpMode): String = when (mode) {
    WarpMode.OUTBOUND -> stringResource(R.string.warp_mode_outbound)
    WarpMode.FRONT -> stringResource(R.string.warp_mode_chain)
}

@Preview
@Composable
private fun WarpScreenPreview() {
    V2RayEzTheme { WarpScreen(onBack = {}, viewModel = WarpViewModel()) }
}
