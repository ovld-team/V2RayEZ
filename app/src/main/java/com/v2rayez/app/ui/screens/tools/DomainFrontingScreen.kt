package com.v2rayez.app.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.data.fronting.DomainFrontTuning
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.ReconnectBanner
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.SettingSwitchRow
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.Warning
import com.v2rayez.app.ui.viewmodel.DomainFrontingViewModel

@Composable
fun DomainFrontingScreen(
    onBack: () -> Unit,
    viewModel: DomainFrontingViewModel = hiltViewModel()
) {
    val s by viewModel.state.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val engineStatus by viewModel.engineStatus.collectAsState()
    val front = s.domainFront
    val mode = front.tuningMode.lowercase()
    // Local draft for fake SNI so the field shows raw value, not effectiveFakeSni fallback.
    var fakeSniDraft by remember(front.fakeSni) { mutableStateOf(front.fakeSni) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2BackTopBar(title = stringResource(R.string.sni_front_title), onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.fronting_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(12)

            if (connected) {
                ReconnectBanner(
                    hint = stringResource(R.string.fronting_reconnect_hint),
                    actionLabel = stringResource(R.string.fronting_reconnect_now),
                    onReconnect = viewModel::reconnect
                )
                VSpacer(12)
            }

            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                SettingSwitchRow(
                    Icons.Filled.Cloud,
                    stringResource(R.string.fronting_enable),
                    front.enabled,
                    viewModel::toggleEnabled,
                    subtitle = stringResource(R.string.fronting_enable_sub)
                )
            }
            VSpacer(8)

            if (engineStatus.isRunning || engineStatus.activeTargetLabel.isNotBlank()) {
                Text(
                    if (engineStatus.isRunning) {
                        stringResource(
                            R.string.fronting_status_running,
                            engineStatus.activeTargetLabel.ifBlank { "—" }
                        )
                    } else {
                        stringResource(R.string.fronting_status_idle)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (engineStatus.isRunning) Connected else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (engineStatus.lastError.isNotBlank()) {
                    VSpacer(4)
                    Text(
                        engineStatus.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = Warning
                    )
                }
                VSpacer(12)
            }

            SectionHeader(title = stringResource(R.string.fronting_tuning_mode))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V2FilterChip(
                    stringResource(R.string.fronting_mode_fast),
                    mode == DomainFrontTuning.MODE_FAST,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setTuningMode(DomainFrontTuning.MODE_FAST) }
                V2FilterChip(
                    stringResource(R.string.fronting_mode_balanced),
                    mode == DomainFrontTuning.MODE_BALANCED || mode == DomainFrontTuning.MODE_CUSTOM,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setTuningMode(DomainFrontTuning.MODE_BALANCED) }
                V2FilterChip(
                    stringResource(R.string.fronting_mode_stealth),
                    mode == DomainFrontTuning.MODE_STEALTH,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setTuningMode(DomainFrontTuning.MODE_STEALTH) }
            }
            VSpacer(16)

            SectionHeader(title = stringResource(R.string.fronting_endpoints))
            Text(
                stringResource(R.string.fronting_front_address_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(8)
            FrontField(stringResource(R.string.fronting_front_address), front.frontAddress) {
                viewModel.setFrontAddress(it)
            }
            FrontField(stringResource(R.string.fronting_fallback_address), front.fallbackAddress) {
                viewModel.setFallbackAddress(it)
            }
            FrontField(
                stringResource(R.string.fronting_front_port),
                front.frontPort.toString(),
                KeyboardType.Number
            ) { it.toIntOrNull()?.let(viewModel::setFrontPort) }
            FrontField(stringResource(R.string.fronting_fake_sni), fakeSniDraft) {
                fakeSniDraft = it
                viewModel.setFakeSni(it)
            }
            FrontField(
                stringResource(R.string.fronting_listen_port),
                front.listenPort.toString(),
                KeyboardType.Number
            ) { it.toIntOrNull()?.let(viewModel::setListenPort) }
            VSpacer(16)

            PrimaryButton(
                stringResource(R.string.fronting_clear_cache),
                onClick = viewModel::clearStrategyCache,
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(8)
            Text(
                stringResource(R.string.fronting_clear_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(24)
        }
    }
}

@Composable
private fun FrontField(
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Preview
@Composable
private fun DomainFrontingScreenPreview() {
    V2RayEzTheme {
        DomainFrontingScreen(onBack = {}, viewModel = DomainFrontingViewModel())
    }
}
