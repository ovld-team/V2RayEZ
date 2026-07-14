package com.v2rayez.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.data.core.AddonPackId
import com.v2rayez.app.data.core.GeoDataState
import com.v2rayez.app.data.core.PackSource
import com.v2rayez.app.domain.model.CORE_VERSION_BUNDLED
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.viewmodel.CoreManagerViewModel

@Composable
fun CoreManagerScreen(
    onBack: () -> Unit,
    viewModel: CoreManagerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val remote by viewModel.remoteReleases.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val status by viewModel.statusMessage.collectAsState()
    LaunchedEffect(Unit) { viewModel.drainPendingAddons() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        V2BackTopBar(title = stringResource(R.string.core_manager_title), onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.core_device_abi, viewModel.deviceAbi()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            VSpacer(12)
            SectionHeader(title = stringResource(R.string.core_default_title))
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
                        modifier = Modifier.weight(1f),
                        enabled = !busy
                    ) { viewModel.setDefaultCore(type) }
                }
            }
            VSpacer(20)

            ProxyCoreType.entries.forEach { type ->
                val selected = state.selectedCoreVersions[type] ?: CORE_VERSION_BUNDLED
                val installed = remember(type, status) { viewModel.installed(type) }
                SectionHeader(title = type.label)
                CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // sing-box / mihomo are download-only since v0.9.50 — don't claim a
                        // bundled version that isn't packaged for this build.
                        if (type == ProxyCoreType.XRAY || viewModel.isBundledRunnable(type)) {
                            Text(
                                stringResource(
                                    R.string.core_bundled_label,
                                    viewModel.bundledLabel(type)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (type == ProxyCoreType.XRAY) {
                            Text(
                                stringResource(R.string.core_xray_bundled_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            stringResource(R.string.core_active_version, selected),
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            installed.forEach { ver ->
                                val label = if (ver == CORE_VERSION_BUNDLED) {
                                    stringResource(R.string.core_version_bundled)
                                } else ver
                                V2FilterChip(
                                    label = label,
                                    selected = selected == ver,
                                    enabled = !busy
                                ) { viewModel.selectVersion(type, ver) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.refreshRemotes(type) }, enabled = !busy) {
                                Text(stringResource(R.string.core_fetch_releases))
                            }
                            if (type != ProxyCoreType.XRAY) {
                                TextButton(onClick = { viewModel.downloadLatest(type) }, enabled = !busy) {
                                    Text(stringResource(R.string.core_update_latest))
                                }
                            }
                        }
                        if (type != ProxyCoreType.XRAY && !viewModel.isBundledRunnable(type)) {
                            Text(
                                stringResource(R.string.core_bundled_missing, viewModel.deviceAbi()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        for (rel in remote[type].orEmpty().take(8)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${rel.tag}\n${rel.abi} · ${rel.assetName}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TextButton(
                                    onClick = { viewModel.download(type, rel) },
                                    enabled = !busy
                                ) { Text(stringResource(R.string.core_download)) }
                                if (rel.tag in installed) {
                                    TextButton(
                                        onClick = { viewModel.deleteVersion(type, rel.tag) },
                                        enabled = !busy
                                    ) { Text(stringResource(R.string.core_delete)) }
                                }
                            }
                        }
                    }
                }
                VSpacer(16)
            }

            // ---- Add-on packs: Tor / pluggable transports / ByeDPI (download on demand) ----
            SectionHeader(title = stringResource(R.string.core_addons_title))
            Text(
                stringResource(R.string.core_addons_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(8)
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    viewModel.addonPackIds.forEach { packId ->
                        val source = remember(packId, status) { viewModel.addonSource(packId) }
                        val version = remember(packId, status) { viewModel.addonVersion(packId) }
                        val queued = viewModel.isAddonQueued(state.pendingAddonInstall, packId)
                        AddonPackRow(
                            label = packId.label,
                            source = source,
                            version = version,
                            queued = queued,
                            busy = busy,
                            onInstall = { viewModel.installAddon(packId) },
                            onCancel = { viewModel.cancelAddon(packId) },
                            onDelete = { viewModel.deleteAddon(packId) }
                        )
                    }
                }
            }
            VSpacer(20)

            // ---- Geo routing data: full geoip/geosite dats (download on demand) ----
            SectionHeader(title = stringResource(R.string.core_geo_title))
            Text(
                stringResource(R.string.core_geo_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(8)
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val geoState = remember(status) { viewModel.geoState() }
                    val geoLabel = remember(status) { viewModel.geoInstalledLabel() }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.core_geo_pack_label),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                when (geoState) {
                                    GeoDataState.DOWNLOADED ->
                                        stringResource(R.string.core_geo_state_full, geoLabel ?: "")
                                    GeoDataState.BUILT_IN_MINI ->
                                        stringResource(R.string.core_geo_state_mini)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when (geoState) {
                                    GeoDataState.DOWNLOADED -> MaterialTheme.colorScheme.primary
                                    GeoDataState.BUILT_IN_MINI -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        when (geoState) {
                            GeoDataState.DOWNLOADED ->
                                TextButton(onClick = { viewModel.deleteGeo() }, enabled = !busy) {
                                    Text(stringResource(R.string.core_addon_delete))
                                }
                            GeoDataState.BUILT_IN_MINI ->
                                TextButton(onClick = { viewModel.downloadGeo() }, enabled = !busy) {
                                    Text(stringResource(R.string.core_addon_install))
                                }
                        }
                    }
                }
            }
            VSpacer(20)

            if (status.isNotBlank()) {
                Text(status, color = MaterialTheme.colorScheme.primary)
                VSpacer(12)
            }
            if (busy) {
                PrimaryButton(
                    stringResource(R.string.core_busy),
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false
                )
                VSpacer(24)
            }
        }
    }
}

/** One add-on pack row: label + resolved source badge + install/queue/delete CTAs. */
@Composable
private fun AddonPackRow(
    label: String,
    source: PackSource,
    version: String?,
    queued: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val (badge, badgeColor) = when (source) {
        PackSource.DOWNLOADED -> stringResource(R.string.core_addon_source_downloaded, version ?: "") to
            MaterialTheme.colorScheme.primary
        PackSource.BUNDLED -> stringResource(R.string.core_addon_source_bundled) to
            MaterialTheme.colorScheme.onSurfaceVariant
        PackSource.MISSING -> stringResource(R.string.core_addon_source_missing) to
            MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                badge,
                style = MaterialTheme.typography.bodySmall,
                color = badgeColor
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (source) {
                PackSource.DOWNLOADED ->
                    TextButton(onClick = onDelete, enabled = !busy) {
                        Text(stringResource(R.string.core_addon_delete))
                    }
                PackSource.BUNDLED -> { /* bundled in APK — no action */ }
                PackSource.MISSING ->
                    if (queued) {
                        TextButton(onClick = onCancel, enabled = !busy) {
                            Text(stringResource(R.string.core_addon_cancel))
                        }
                    } else {
                        TextButton(onClick = onInstall, enabled = !busy) {
                            Text(stringResource(R.string.core_addon_install))
                        }
                    }
            }
        }
    }
    if (queued && source == PackSource.MISSING) {
        Text(
            stringResource(R.string.core_addon_queued),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
