package com.v2rayez.app.ui.screens.mitm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.OutlinedActionButton
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.SettingSwitchRow
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.components.V2Checkbox
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.Info
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.Warning
import com.v2rayez.app.ui.viewmodel.MitmDomainFrontingViewModel

private data class DohPreset(val key: String, val labelRes: Int, val ip: String, val frontSni: String, val host: String)

private val DOH_PRESETS = listOf(
    DohPreset("cloudflare", R.string.mitm_doh_preset_cloudflare, "1.1.1.1", "www.microsoft.com", "cloudflare-dns.com"),
    DohPreset("google", R.string.mitm_doh_preset_google, "8.8.8.8", "www.gstatic.com", "dns.google")
)

@Composable
fun MitmDomainFrontingScreen(
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit = {},
    onOpenCertificates: () -> Unit = {},
    viewModel: MitmDomainFrontingViewModel = hiltViewModel()
) {
    MitmDomainFrontingContent(
        onBack = onBack,
        onOpenBrowser = onOpenBrowser,
        onOpenCertificates = onOpenCertificates,
        viewModel = viewModel
    )
}

@Composable
internal fun MitmDomainFrontingContent(
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenCertificates: () -> Unit,
    viewModel: MitmDomainFrontingViewModel
) {
    val context = LocalContext.current
    val vpnPermission = com.v2rayez.app.ui.LocalVpnPermission.current
    val mitm by viewModel.mitm.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val proxyRunning by viewModel.proxyRunning.collectAsState()
    val deviceTunnelRunning by viewModel.deviceTunnelRunning.collectAsState()
    val caPresent by viewModel.caPresent.collectAsState()
    val fingerprint by viewModel.fingerprint.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onCaInstallReturned() }

    var pcExpanded by rememberSaveable { mutableStateOf(false) }
    var importCrt by rememberSaveable { mutableStateOf("") }
    var importKey by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2BackTopBar(title = stringResource(R.string.mitm_title), onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.mitm_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                HSpacer(8)
                NewBadge()
            }
            VSpacer(16)

            // ---------------------------------------------------------- How it works
            SectionHeader(title = stringResource(R.string.mitm_how_title))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.mitm_how_1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VSpacer(6)
                    Text(stringResource(R.string.mitm_how_2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VSpacer(6)
                    Text(stringResource(R.string.mitm_how_3), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            VSpacer(20)

            // ---------------------------------------------------------- CA wizard
            SectionHeader(title = stringResource(R.string.mitm_ca_section))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(
                    Modifier
                        .padding(14.dp)
                        // Locale-independent marker for device-lab adb/Maestro flows — the visible
                        // status text is trilingual (EN/FA/RU) so scripts key off this instead.
                        .semantics { contentDescription = "mitm_ca_status:${if (caPresent) "present" else "missing"}" }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = if (caPresent) Connected else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        Text(
                            stringResource(if (caPresent) R.string.mitm_ca_status_present else R.string.mitm_ca_status_missing),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (caPresent && fingerprint != null) {
                        VSpacer(6)
                        Text(
                            stringResource(R.string.mitm_ca_fingerprint, fingerprint ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    VSpacer(12)
                    PrimaryButton(
                        stringResource(if (caPresent) R.string.mitm_ca_regenerate else R.string.mitm_ca_generate),
                        onClick = viewModel::generateCa,
                        modifier = Modifier.fillMaxWidth()
                    )
                    VSpacer(8)
                    OutlinedActionButton(
                        stringResource(R.string.mitm_ca_install),
                        onClick = {
                            val plan = viewModel.prepareCaInstall()
                            if (plan?.intent != null) {
                                runCatching { installLauncher.launch(plan.intent) }
                                    .onFailure {
                                        Toast.makeText(context, context.getString(R.string.mitm_ca_install_failed), Toast.LENGTH_LONG).show()
                                        viewModel.openSecuritySettings()
                                    }
                            } else {
                                Toast.makeText(
                                    context,
                                    if (caPresent) context.getString(R.string.mitm_ca_install_failed)
                                    else context.getString(R.string.mitm_ca_status_missing),
                                    Toast.LENGTH_LONG
                                ).show()
                                if (caPresent) viewModel.openSecuritySettings()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    VSpacer(4)
                    Text(
                        stringResource(
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                                R.string.mitm_ca_install_android11_note
                            else
                                R.string.mitm_ca_install_sub
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    VSpacer(8)
                    OutlinedActionButton(
                        stringResource(R.string.mitm_ca_save_downloads),
                        onClick = {
                            if (!caPresent) {
                                Toast.makeText(context, context.getString(R.string.mitm_ca_status_missing), Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.saveCaToDownloads()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    VSpacer(8)
                    OutlinedActionButton(
                        stringResource(R.string.mitm_ca_open_settings),
                        onClick = viewModel::openSecuritySettings,
                        modifier = Modifier.fillMaxWidth()
                    )
                    VSpacer(10)
                    var guideExpanded by rememberSaveable { mutableStateOf(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { guideExpanded = !guideExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.mitm_ca_guide_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (guideExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(visible = guideExpanded) {
                        Column(Modifier.padding(top = 8.dp)) {
                            val steps = remember {
                                com.v2rayez.app.data.cert.MitmCaInstallHelper.installGuideSteps(context)
                            }
                            steps.forEach { step ->
                                Text(step.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                VSpacer(2)
                                Text(step.body, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                VSpacer(8)
                            }
                        }
                    }
                    VSpacer(10)
                    V2Checkbox(
                        checked = mitm.caInstallAcknowledged,
                        onCheckedChange = viewModel::acknowledgeCaInstall,
                        label = stringResource(R.string.mitm_ca_installed_check)
                    )
                    VSpacer(10)
                    Text(
                        stringResource(R.string.mitm_ca_firefox_tip),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    VSpacer(10)
                    OutlinedActionButton(
                        stringResource(R.string.mitm_ca_export_crt),
                        onClick = {
                            val uri = viewModel.crtShareUri()
                            if (uri != null) shareUri(context, uri, "application/x-x509-ca-cert") else Toast.makeText(context, context.getString(R.string.mitm_ca_status_missing), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            VSpacer(20)

            // ---------------------------------------------------------- Use with PC
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { pcExpanded = !pcExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.mitm_pc_section),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (pcExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(visible = pcExpanded) {
                        Column {
                            VSpacer(10)
                            Text(
                                stringResource(R.string.mitm_pc_intro),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            VSpacer(12)
                            OutlinedActionButton(
                                stringResource(R.string.mitm_pc_share_pack),
                                onClick = {
                                    val uri = viewModel.pcPackShareUri()
                                    if (uri != null) shareUri(context, uri, "application/zip") else Toast.makeText(context, context.getString(R.string.mitm_ca_status_missing), Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            VSpacer(4)
                            Text(
                                stringResource(R.string.mitm_pc_share_pack_warn),
                                style = MaterialTheme.typography.labelSmall,
                                color = Warning
                            )
                            VSpacer(10)
                            OutlinedActionButton(
                                stringResource(R.string.mitm_pc_export_json),
                                onClick = { shareText(context, viewModel.desktopJsonText()) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            VSpacer(18)
                            Text(
                                stringResource(R.string.mitm_pc_import_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            VSpacer(8)
                            OutlinedTextField(
                                value = importCrt,
                                onValueChange = { importCrt = it },
                                label = { Text(stringResource(R.string.mitm_pc_import_crt)) },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                            OutlinedTextField(
                                value = importKey,
                                onValueChange = { importKey = it },
                                label = { Text(stringResource(R.string.mitm_pc_import_key)) },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                            VSpacer(4)
                            Text(
                                stringResource(R.string.mitm_pc_import_warn),
                                style = MaterialTheme.typography.labelSmall,
                                color = Warning
                            )
                            VSpacer(10)
                            PrimaryButton(
                                stringResource(R.string.mitm_pc_import_button),
                                onClick = {
                                    viewModel.importPair(importCrt, importKey)
                                    importCrt = ""
                                    importKey = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            VSpacer(20)

            // ---------------------------------------------------------- Enable + standalone run + capture
            CardSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                SettingSwitchRow(
                    Icons.Filled.Shield,
                    stringResource(R.string.mitm_enable),
                    mitm.enabled,
                    viewModel::toggleEnabled,
                    subtitle = stringResource(R.string.mitm_enable_sub)
                )
            }
            VSpacer(10)
            CardSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                SettingSwitchRow(
                    Icons.Filled.Shield,
                    stringResource(R.string.mitm_standalone_run),
                    proxyRunning,
                    viewModel::setStandaloneRunning,
                    subtitle = stringResource(R.string.mitm_standalone_run_sub)
                )
            }
            VSpacer(10)
            CardSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                SettingSwitchRow(
                    Icons.Filled.Public,
                    stringResource(R.string.mitm_capture_all),
                    // Show ON while capture is requested or the VPN MITM session is live.
                    mitm.captureAllApps || deviceTunnelRunning,
                    onCheckedChange = { enable ->
                        if (enable) {
                            vpnPermission.request { viewModel.setCaptureAllApps(true) }
                        } else {
                            viewModel.setCaptureAllApps(false)
                        }
                    },
                    subtitle = stringResource(R.string.mitm_capture_all_sub)
                )
            }
            VSpacer(8)
            Text(
                stringResource(R.string.mitm_standalone_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(8)
            Text(
                stringResource(R.string.mitm_quic_pinned_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(20)

            // ---------------------------------------------------------- Rules
            SectionHeader(title = stringResource(R.string.mitm_rules_title))
            Text(
                stringResource(R.string.mitm_rules_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(8)
            OutlinedTextField(
                value = mitm.rulesText,
                onValueChange = viewModel::setRulesText,
                textStyle = TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                maxLines = Int.MAX_VALUE,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )
            VSpacer(8)
            OutlinedActionButton(
                stringResource(R.string.mitm_reset_rules),
                onClick = viewModel::resetRules,
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(20)

            // ---------------------------------------------------------- Default front + port
            MitmField(stringResource(R.string.mitm_default_front), mitm.defaultFront) {
                viewModel.setDefaultFront(it)
            }
            Text(
                stringResource(R.string.mitm_default_front_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MitmField(
                stringResource(R.string.mitm_proxy_port),
                mitm.proxyPort.toString(),
                KeyboardType.Number
            ) { it.toIntOrNull()?.let(viewModel::setProxyPort) }
            MitmField(
                stringResource(R.string.mitm_http_port),
                mitm.httpPort.toString(),
                KeyboardType.Number
            ) { it.toIntOrNull()?.let(viewModel::setHttpPort) }
            VSpacer(12)

            // ---------------------------------------------------------- DoH
            SectionHeader(title = stringResource(R.string.mitm_doh_title))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DOH_PRESETS.forEach { preset ->
                    V2FilterChip(
                        stringResource(preset.labelRes),
                        mitm.dohPreset == preset.key,
                        modifier = Modifier.weight(1f)
                    ) { viewModel.setDohPreset(preset.key, preset.ip, preset.frontSni, preset.host) }
                }
                V2FilterChip(
                    stringResource(R.string.mitm_doh_preset_custom),
                    mitm.dohPreset !in DOH_PRESETS.map { it.key },
                    modifier = Modifier.weight(1f)
                ) { viewModel.setDohPreset("custom", mitm.dohIp, mitm.dohFrontSni, mitm.dohHost) }
            }
            VSpacer(8)
            MitmField(stringResource(R.string.mitm_doh_ip), mitm.dohIp) {
                viewModel.setDohPreset("custom", it, mitm.dohFrontSni, mitm.dohHost)
            }
            MitmField(stringResource(R.string.mitm_doh_front_sni), mitm.dohFrontSni) {
                viewModel.setDohPreset("custom", mitm.dohIp, it, mitm.dohHost)
            }
            MitmField(stringResource(R.string.mitm_doh_host), mitm.dohHost) {
                viewModel.setDohPreset("custom", mitm.dohIp, mitm.dohFrontSni, it)
            }
            VSpacer(20)

            // ---------------------------------------------------------- Shortcuts
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedActionButton(stringResource(R.string.mitm_open_browser), onClick = onOpenBrowser, modifier = Modifier.weight(1f))
                OutlinedActionButton(stringResource(R.string.mitm_open_certificates), onClick = onOpenCertificates, modifier = Modifier.weight(1f))
            }
            VSpacer(24)
        }
    }
}

@Composable
private fun NewBadge() {
    CardSurface(color = Info.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
        Text(
            stringResource(R.string.mitm_new_badge),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Info,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MitmField(
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

private fun shareUri(context: Context, uri: Uri, mimeType: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun shareText(context: Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Preview
@Composable
private fun MitmDomainFrontingScreenPreview() {
    V2RayEzTheme {
        MitmDomainFrontingScreen(
            onBack = {},
            viewModel = MitmDomainFrontingViewModel()
        )
    }
}
