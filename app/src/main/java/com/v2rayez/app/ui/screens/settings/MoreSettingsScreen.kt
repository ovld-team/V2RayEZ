package com.v2rayez.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.SettingSwitchRow
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.SettingsViewModel

/**
 * "More Settings" — surfaces the advanced connection knobs that already persist in
 * [com.v2rayez.app.domain.model.AppSettings] but previously had no UI (local proxy ports,
 * MTU, multiplexing, local DNS, LAN sharing, battery saver).
 */
@Composable
fun MoreSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2BackTopBar(title = stringResource(R.string.vpn_more_settings), onBack = onBack)

        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionHeader(title = stringResource(R.string.more_section_local_proxy))
            NumberField(stringResource(R.string.more_socks_port), s.socksPort) { viewModel.setSocksPort(it) }
            NumberField(stringResource(R.string.more_http_port), s.httpPort) { viewModel.setHttpPort(it) }
            VSpacer(16)

            SectionHeader(title = stringResource(R.string.more_section_tunnel))
            NumberField("MTU", s.mtu) { viewModel.setMtu(it) }
            VSpacer(8)
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column {
                    SettingSwitchRow(
                        Icons.Filled.CallMerge,
                        stringResource(R.string.vpn_mux),
                        s.enableMux,
                        viewModel::toggleMux,
                        subtitle = stringResource(R.string.vpn_mux_sub)
                    )
                }
            }
            if (s.enableMux) {
                VSpacer(8)
                NumberField(stringResource(R.string.more_mux_concurrency), s.muxConcurrency) { viewModel.setMuxConcurrency(it) }
            }
            VSpacer(16)

            SectionHeader(title = stringResource(R.string.tools_advanced))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column {
                    SettingSwitchRow(
                        Icons.Filled.Dns,
                        stringResource(R.string.more_local_dns),
                        s.enableLocalDns,
                        viewModel::toggleLocalDns,
                        subtitle = stringResource(R.string.more_local_dns_sub)
                    )
                    SettingSwitchRow(
                        Icons.Filled.Wifi,
                        stringResource(R.string.more_lan_sharing),
                        s.enableLanSharing || s.allowLan,
                        viewModel::setHotspotSharing,
                        subtitle = stringResource(R.string.more_lan_sharing_sub)
                    )
                    SettingSwitchRow(
                        Icons.Filled.BatterySaver,
                        stringResource(R.string.settings_battery_saver),
                        s.batterySaver,
                        viewModel::toggleBatterySaver
                    )
                }
            }
            VSpacer(24)
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { new -> new.filter { it.isDigit() }.take(6).toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Preview
@Composable
private fun MoreSettingsScreenPreview() {
    V2RayEzTheme { MoreSettingsScreen(onBack = {}, viewModel = SettingsViewModel()) }
}
