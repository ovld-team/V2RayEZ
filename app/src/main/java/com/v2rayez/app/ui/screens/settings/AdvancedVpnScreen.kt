package com.v2rayez.app.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.SettingRow
import com.v2rayez.app.ui.components.SettingSwitchRow
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.SettingsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth

@Composable
fun AdvancedVpnScreen(
    onBack: () -> Unit,
    onOpenMore: () -> Unit = {},
    onOpenCoreManager: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val conn by viewModel.connectionState.collectAsState()
    val context = LocalContext.current
    val alwaysOnHint = stringResource(R.string.vpn_always_on_hint)

    fun openSystemVpnSettings() {
        val opened = runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!opened) runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Toast.makeText(context, alwaysOnHint, Toast.LENGTH_LONG).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2BackTopBar(title = stringResource(R.string.settings_vpn), onBack = onBack)

        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionHeader(title = stringResource(R.string.core_default_title))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.core_default_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    VSpacer(8)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProxyCoreType.entries.forEach { type ->
                            V2FilterChip(
                                label = type.label,
                                selected = state.defaultCore == type,
                                modifier = Modifier.weight(1f)
                            ) {
                                viewModel.setDefaultCore(type)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.core_reconnect_hint),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    if (state.defaultCore != ProxyCoreType.XRAY) {
                        VSpacer(8)
                        Text(
                            stringResource(R.string.vpn_warn_xray_only, "Fronting / ByeDPI / Fragment / WARP"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    VSpacer(8)
                    SettingRow(
                        Icons.Filled.Memory,
                        stringResource(R.string.core_manager_title),
                        onClick = onOpenCoreManager,
                        subtitle = stringResource(R.string.core_manager_sub)
                    )
                }
            }
            VSpacer(16)

            SectionHeader(title = stringResource(R.string.settings_section_connection))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column {
                    SettingSwitchRow(
                        Icons.Filled.Bolt,
                        stringResource(R.string.settings_auto_connect),
                        state.autoConnect,
                        viewModel::toggleAutoConnect,
                        subtitle = stringResource(R.string.settings_auto_connect_sub)
                    )
                    Divider()
                    SettingSwitchRow(
                        Icons.Filled.RestartAlt,
                        stringResource(R.string.settings_boot_auto_connect),
                        state.bootAutoConnect,
                        viewModel::toggleBootAutoConnect,
                        subtitle = stringResource(R.string.settings_boot_auto_connect_sub)
                    )
                    Divider()
                    // Honesty: Always-on is an OS-level setting. This row deep-links to Android VPN
                    // settings and reflects the real OS state when the tunnel is running.
                    SettingRow(
                        icon = Icons.Filled.Shield,
                        title = stringResource(R.string.vpn_always_on),
                        subtitle = stringResource(R.string.vpn_always_on_desc),
                        onClick = { openSystemVpnSettings() },
                        trailing = { OsStatusTrailing(active = conn.alwaysOn) }
                    )
                    Divider()
                    SettingRow(
                        icon = Icons.Filled.Block,
                        title = stringResource(R.string.vpn_block_without_vpn),
                        subtitle = stringResource(R.string.vpn_block_without_vpn_desc),
                        onClick = { openSystemVpnSettings() },
                        trailing = { OsStatusTrailing(active = conn.lockdown) }
                    )
                    Divider()
                    SettingSwitchRow(
                        Icons.Filled.AllInclusive,
                        stringResource(R.string.vpn_full_tunnel),
                        state.fullDeviceTunnel,
                        viewModel::toggleFullDeviceTunnel,
                        subtitle = stringResource(R.string.vpn_full_tunnel_sub)
                    )
                }
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.vpn_section_network))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column {
                    SettingSwitchRow(Icons.Filled.Lan, stringResource(R.string.vpn_allow_lan), state.allowLan, viewModel::toggleAllowLan)
                    Divider()
                    SettingSwitchRow(Icons.Filled.Language, "IPv6", state.enableIpv6, viewModel::toggleIpv6)
                    Divider()
                    SettingSwitchRow(Icons.Filled.DataUsage, stringResource(R.string.vpn_reduce_data), state.reduceData, viewModel::toggleReduceData)
                }
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.vpn_section_performance))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column {
                    SettingSwitchRow(
                        Icons.Filled.CallMerge,
                        stringResource(R.string.vpn_mux),
                        state.enableMux,
                        viewModel::toggleMux,
                        subtitle = stringResource(R.string.vpn_mux_sub)
                    )
                    Divider()
                    SettingSwitchRow(
                        Icons.Filled.Traffic,
                        stringResource(R.string.vpn_sniffing),
                        state.enableSniffing,
                        viewModel::toggleSniffing,
                        subtitle = stringResource(R.string.vpn_sniffing_sub)
                    )
                }
            }
            VSpacer(20)

            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column {
                    SettingRow(Icons.Filled.Tune, stringResource(R.string.vpn_more_settings), onClick = onOpenMore)
                }
            }
            VSpacer(24)
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp, modifier = Modifier.padding(start = 52.dp))
}

/** Trailing status for an OS-managed setting: shows the real state when known, else a deep-link cue. */
@Composable
private fun OsStatusTrailing(active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(if (active) R.string.vpn_os_status_on else R.string.vpn_os_status_configure),
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview
@Composable
private fun AdvancedVpnScreenPreview() {
    V2RayEzTheme { AdvancedVpnScreen(onBack = {}, viewModel = SettingsViewModel()) }
}
