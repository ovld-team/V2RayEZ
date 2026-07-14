package com.v2rayez.app.ui.screens.hotspot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.SettingSwitchRow
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.SettingsViewModel
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun HotspotShareScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.state.collectAsState()
    var ip by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        ip = withContext(Dispatchers.IO) { localIpAddress() } ?: "0.0.0.0"
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize()) {
        V2BackTopBar(title = stringResource(R.string.settings_hotspot), onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                stringResource(R.string.hotspot_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(16)

            val reconnectHint = stringResource(R.string.hotspot_reconnect_hint)
            CardSurface(Modifier.fillMaxWidth()) {
                // allowLan and enableLanSharing both feed ConfigBuilder's 0.0.0.0 bind — keep them
                // in lockstep so the toggle never lies about being half-on.
                SettingSwitchRow(
                    icon = Icons.Filled.Wifi,
                    title = stringResource(R.string.hotspot_enable),
                    subtitle = stringResource(R.string.hotspot_enable_sub),
                    checked = settings.enableLanSharing || settings.allowLan,
                    onCheckedChange = {
                        viewModel.setHotspotSharing(it)
                        Toast.makeText(context, reconnectHint, Toast.LENGTH_SHORT).show()
                    }
                )
            }
            if (settings.defaultCore != ProxyCoreType.XRAY) {
                VSpacer(8)
                CardSurface(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.hotspot_xray_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            VSpacer(20)

            val copiedMessage = stringResource(R.string.common_copied)
            SectionHeader(title = stringResource(R.string.hotspot_section_proxy_address))
            ProxyRow("SOCKS5", "$ip:${settings.socksPort}") {
                clipboard.setText(AnnotatedString(it))
                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
            }
            VSpacer(8)
            ProxyRow("HTTP", "$ip:${settings.httpPort}") {
                clipboard.setText(AnnotatedString(it))
                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
            }
            VSpacer(20)

            CardSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.hotspot_how_to), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    VSpacer(8)
                    Step(1, stringResource(R.string.hotspot_step1))
                    Step(2, stringResource(R.string.hotspot_step2))
                    Step(3, stringResource(R.string.hotspot_step3))
                    Step(4, stringResource(R.string.hotspot_step4))
                    VSpacer(8)
                    Text(
                        stringResource(R.string.hotspot_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            VSpacer(24)
        }
    }
}

@Composable
private fun ProxyRow(label: String, value: String, onCopy: (String) -> Unit) {
    CardSurface(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCopy(value) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
            }
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$n.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Best-effort local IPv4 for Wi-Fi / hotspot, preferring wlan/ap interfaces. */
private fun localIpAddress(): String? {
    return runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            .sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("ap") }
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return@runCatching addr.hostAddress
                }
            }
        }
        null
    }.getOrNull()
}

@Preview
@Composable
private fun HotspotShareScreenPreview() {
    V2RayEzTheme { HotspotShareScreen(onBack = {}, viewModel = SettingsViewModel()) }
}
