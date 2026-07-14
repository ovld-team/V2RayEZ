package com.v2rayez.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import com.v2rayez.app.R
import com.v2rayez.app.data.widget.QuickConnectWidgetProvider
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.SettingRow
import com.v2rayez.app.ui.components.SettingSwitchRow
import com.v2rayez.app.ui.components.SettingValueRow
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2TopBar
import com.v2rayez.app.ui.SupportedLanguages
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.BackupViewModel
import com.v2rayez.app.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onOpenAdvancedVpn: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenRouting: () -> Unit = {},
    onOpenDns: () -> Unit = {},
    onOpenWarp: () -> Unit = {},
    onOpenHotspot: () -> Unit = {},
    onOpenStatistics: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    backupViewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var picker by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupMessage by backupViewModel.message.collectAsState()
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            backupViewModel.clearMessage()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = backupViewModel.exportJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                backupViewModel.notify(context.getString(R.string.settings_backup_saved))
            }.onFailure { backupViewModel.notify(context.getString(R.string.settings_backup_failed)) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                backupViewModel.importJson(text)
            }.onFailure { backupViewModel.notify(context.getString(R.string.settings_restore_failed)) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2TopBar(title = stringResource(R.string.settings_title))

        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionHeader(title = stringResource(R.string.settings_section_appearance))
            SettingsGroup {
                SettingValueRow(Icons.Filled.DarkMode, stringResource(R.string.settings_theme), themeLabel(state.theme), onClick = { picker = "theme" })
                Divider()
                SettingValueRow(Icons.Filled.Language, stringResource(R.string.settings_language), state.language, onClick = { picker = "language" })
                Divider()
                SettingValueRow(Icons.Filled.ColorLens, stringResource(R.string.settings_color), accentLabel(state.accentColor), onClick = { picker = "accent" })
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.settings_section_connection))
            SettingsGroup {
                SettingRow(Icons.Filled.VpnKey, stringResource(R.string.settings_vpn), onClick = onOpenAdvancedVpn)
                Divider()
                SettingRow(Icons.Filled.Route, stringResource(R.string.settings_routing), onClick = onOpenRouting)
                Divider()
                SettingRow(Icons.Filled.Dns, stringResource(R.string.settings_dns), onClick = onOpenDns)
                Divider()
                SettingRow(Icons.Filled.Cloud, stringResource(R.string.settings_warp), subtitle = stringResource(R.string.settings_warp_sub), onClick = onOpenWarp)
                Divider()
                SettingRow(Icons.Filled.Wifi, stringResource(R.string.settings_hotspot), subtitle = stringResource(R.string.settings_hotspot_sub), onClick = onOpenHotspot)
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.settings_section_general))
            SettingsGroup {
                SettingSwitchRow(Icons.Filled.Notifications, stringResource(R.string.settings_notifications), state.notifications, viewModel::toggleNotifications)
                Divider()
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
                SettingSwitchRow(
                    Icons.Filled.BatteryChargingFull,
                    stringResource(R.string.settings_battery_saver),
                    state.batterySaver,
                    onCheckedChange = { enabled ->
                        viewModel.toggleBatterySaver(enabled)
                        // When enabling, offer a battery-optimization exemption so the tunnel
                        // survives Doze — the stats loop is also throttled while active.
                        if (enabled) requestBatteryExemption(context)
                    }
                )
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.settings_section_more))
            SettingsGroup {
                SettingRow(Icons.Filled.Widgets, stringResource(R.string.settings_add_widget), subtitle = stringResource(R.string.settings_add_widget_sub), onClick = {
                    requestPinQuickConnectWidget(context)
                })
                Divider()
                SettingRow(Icons.AutoMirrored.Filled.List, stringResource(R.string.settings_logs), onClick = onOpenLogs)
                Divider()
                SettingRow(Icons.Filled.BarChart, stringResource(R.string.settings_statistics), subtitle = stringResource(R.string.settings_statistics_sub), onClick = onOpenStatistics)
                Divider()
                SettingRow(Icons.Filled.Backup, stringResource(R.string.settings_backup), subtitle = stringResource(R.string.settings_backup_sub), onClick = {
                    exportLauncher.launch("v2rayez-backup.json")
                })
                Divider()
                SettingRow(Icons.Filled.Restore, stringResource(R.string.settings_restore), subtitle = stringResource(R.string.settings_restore_sub), onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/*"))
                })
                Divider()
                SettingRow(Icons.Filled.Info, stringResource(R.string.settings_about), onClick = onOpenAbout)
            }
            VSpacer(24)
        }
    }

    when (picker) {
        "theme" -> OptionPickerDialog(
            title = stringResource(R.string.settings_theme),
            // First element is the persisted value (compared elsewhere, e.g. MainActivity);
            // second is the localized label shown to the user.
            options = listOf("System", "Dark", "Light").map { it to themeLabel(it) },
            selected = state.theme,
            onSelect = { viewModel.setTheme(it); picker = null },
            onDismiss = { picker = null }
        )
        "language" -> OptionPickerDialog(
            title = stringResource(R.string.settings_language),
            options = SupportedLanguages.labels.map { it to it },
            selected = state.language,
            onSelect = { viewModel.setLanguage(it); picker = null },
            onDismiss = { picker = null }
        )
        "accent" -> OptionPickerDialog(
            title = stringResource(R.string.settings_accent_title),
            options = listOf("Purple", "Blue", "Green", "Orange", "Pink").map { it to accentLabel(it) },
            selected = state.accentColor,
            onSelect = { viewModel.setAccent(it); picker = null },
            onDismiss = { picker = null }
        )
    }
}

/**
 * Send the user to Android's battery-optimization list so they can exempt the app and keep the
 * tunnel alive under Doze. Uses the no-permission settings list (not the direct request dialog).
 */
private fun requestBatteryExemption(context: android.content.Context) {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
    val alreadyExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    if (alreadyExempt) return
    val opened = runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (opened) {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.battery_exemption_hint),
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

/** Ask the launcher to pin the 1×1 Quick Connect widget; fall back to a manual hint on stubborn OEMs. */
private fun requestPinQuickConnectWidget(context: android.content.Context) {
    val mgr = AppWidgetManager.getInstance(context)
    val provider = ComponentName(context, QuickConnectWidgetProvider::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr.isRequestPinAppWidgetSupported) {
        val ok = mgr.requestPinAppWidget(provider, null, null)
        android.widget.Toast.makeText(
            context,
            context.getString(if (ok) R.string.widget_pin_requested else R.string.widget_pin_unsupported),
            android.widget.Toast.LENGTH_LONG
        ).show()
    } else {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.widget_pin_unsupported),
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

/** Localized display label for a persisted theme value ("System"/"Dark"/"Light"). */
@Composable
private fun themeLabel(value: String): String = when (value) {
    "System" -> stringResource(R.string.theme_system)
    "Dark" -> stringResource(R.string.theme_dark)
    "Light" -> stringResource(R.string.theme_light)
    else -> value
}

/** Localized display label for a persisted accent color value ("Purple"/"Blue"/…). */
@Composable
private fun accentLabel(value: String): String = when (value) {
    "Purple" -> stringResource(R.string.accent_purple)
    "Blue" -> stringResource(R.string.accent_blue)
    "Green" -> stringResource(R.string.accent_green)
    "Orange" -> stringResource(R.string.accent_orange)
    "Pink" -> stringResource(R.string.accent_pink)
    else -> value
}

@Composable
private fun OptionPickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text(stringResource(R.string.action_cancel)) } },
        title = { androidx.compose.material3.Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    com.v2rayez.app.ui.components.V2Radio(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        label = label,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
            }
        }
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column { content() }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp, modifier = Modifier.padding(start = 52.dp))
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    V2RayEzTheme {
        SettingsScreen(onOpenAdvancedVpn = {}, onOpenAbout = {}, onOpenLogs = {}, viewModel = SettingsViewModel())
    }
}
