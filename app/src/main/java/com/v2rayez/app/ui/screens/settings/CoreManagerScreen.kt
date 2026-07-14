package com.v2rayez.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.data.core.GeoDataState
import com.v2rayez.app.data.core.PackSource
import com.v2rayez.app.domain.model.CORE_VERSION_BUNDLED
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.ErrorRed
import com.v2rayez.app.ui.theme.Warning
import com.v2rayez.app.data.core.DownloadQueueItem
import com.v2rayez.app.data.core.QueueItemState
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
    val queue by viewModel.queue.collectAsState()
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

            DownloadQueueSection(
                queue = queue,
                onCancel = viewModel::cancelQueueItem,
                onDismiss = viewModel::dismissQueueItem,
                onClearFinished = viewModel::clearFinishedQueueItems
            )
            VSpacer(16)

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
                val installed = remember(type, status, queue) { viewModel.installed(type) }
                SectionHeader(title = type.label)
                CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                TextButton(onClick = { viewModel.downloadLatest(type) }, enabled = true) {
                                    Text(stringResource(R.string.core_update_latest))
                                }
                            }
                        }
                        if (type != ProxyCoreType.XRAY && !viewModel.isBundledRunnable(type)) {
                            Text(
                                stringResource(R.string.core_bundled_missing, viewModel.deviceAbi()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.core_download_only_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        for (rel in remote[type].orEmpty().take(8)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${rel.tag}\n${rel.abi} · ${rel.assetName}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TextButton(onClick = { viewModel.download(type, rel) }) {
                                    Text(stringResource(R.string.core_download))
                                }
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
                        val source = remember(packId, status, queue) { viewModel.addonSource(packId) }
                        val version = remember(packId, status, queue) { viewModel.addonVersion(packId) }
                        val pendingQueued = viewModel.isAddonQueued(state.pendingAddonInstall, packId)
                        val sessionItem = remember(packId, queue) { viewModel.addonQueueItem(packId) }
                        AddonPackRow(
                            label = packId.label,
                            source = source,
                            version = version,
                            pendingPublishQueued = pendingQueued,
                            sessionItem = sessionItem,
                            onInstall = { viewModel.installAddon(packId) },
                            onCancel = { viewModel.cancelAddon(packId) },
                            onDelete = { viewModel.deleteAddon(packId) }
                        )
                    }
                }
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.core_geo_title))
            Text(
                stringResource(R.string.core_geo_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(8)
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val geoState = remember(status, queue) { viewModel.geoState() }
                    val geoLabel = remember(status, queue) { viewModel.geoInstalledLabel() }
                    val geoItem = remember(queue) { viewModel.geoQueueItem() }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.core_geo_pack_label),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                when {
                                    geoItem?.state == QueueItemState.RUNNING ->
                                        geoItem.progressLabel()
                                    geoItem?.state == QueueItemState.QUEUED ->
                                        stringResource(R.string.core_queue_state_queued)
                                    geoItem?.state == QueueItemState.FAILED ->
                                        stringResource(R.string.core_queue_state_failed)
                                    geoState == GeoDataState.DOWNLOADED ->
                                        stringResource(R.string.core_geo_state_full, geoLabel ?: "")
                                    else -> stringResource(R.string.core_geo_state_mini)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    geoItem?.state == QueueItemState.FAILED -> ErrorRed
                                    geoItem?.state in listOf(QueueItemState.RUNNING, QueueItemState.QUEUED) ->
                                        MaterialTheme.colorScheme.primary
                                    geoState == GeoDataState.DOWNLOADED -> Connected
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (geoItem?.state == QueueItemState.RUNNING) {
                                VSpacer(6)
                                QueueProgressBar(geoItem.progress)
                            }
                        }
                        when {
                            geoItem?.state == QueueItemState.RUNNING || geoItem?.state == QueueItemState.QUEUED ->
                                TextButton(onClick = { viewModel.cancelQueueItem(geoItem.id) }) {
                                    Text(stringResource(R.string.core_queue_cancel))
                                }
                            geoState == GeoDataState.DOWNLOADED ->
                                TextButton(onClick = { viewModel.deleteGeo() }, enabled = !busy) {
                                    Text(stringResource(R.string.core_addon_delete))
                                }
                            else ->
                                TextButton(onClick = { viewModel.downloadGeo() }) {
                                    Text(stringResource(R.string.core_addon_install))
                                }
                        }
                    }
                }
            }
            VSpacer(20)

            if (status.isNotBlank()) {
                Text(status, color = MaterialTheme.colorScheme.primary)
                VSpacer(24)
            }
        }
    }
}

@Composable
private fun DownloadQueueSection(
    queue: List<DownloadQueueItem>,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onClearFinished: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.core_queue_title))
    CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (queue.isEmpty()) {
                Text(
                    stringResource(R.string.core_queue_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val hasFinished = queue.any {
                    it.state == QueueItemState.SUCCESS ||
                        it.state == QueueItemState.FAILED ||
                        it.state == QueueItemState.CANCELLED
                }
                if (hasFinished) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClearFinished) {
                            Text(stringResource(R.string.core_queue_clear_finished))
                        }
                    }
                }
                queue.forEach { item ->
                    QueueRow(item = item, onCancel = onCancel, onDismiss = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: DownloadQueueItem,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (item.subLabel.isBlank()) item.label else "${item.label} · ${item.subLabel}",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when (item.state) {
                        QueueItemState.QUEUED -> stringResource(R.string.core_queue_state_queued)
                        QueueItemState.RUNNING -> item.progressLabel()
                        QueueItemState.SUCCESS -> stringResource(R.string.core_queue_state_success)
                        QueueItemState.FAILED -> item.message?.let {
                            stringResource(R.string.core_queue_error_prefix, it)
                        } ?: stringResource(R.string.core_queue_state_failed)
                        QueueItemState.CANCELLED -> stringResource(R.string.core_queue_state_cancelled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (item.state) {
                        QueueItemState.SUCCESS -> Connected
                        QueueItemState.FAILED -> ErrorRed
                        QueueItemState.CANCELLED -> Warning
                        QueueItemState.RUNNING, QueueItemState.QUEUED -> MaterialTheme.colorScheme.primary
                    }
                )
            }
            when (item.state) {
                QueueItemState.QUEUED, QueueItemState.RUNNING ->
                    if (item.cancellable) {
                        TextButton(onClick = { onCancel(item.id) }) {
                            Text(stringResource(R.string.core_queue_cancel))
                        }
                    }
                QueueItemState.SUCCESS, QueueItemState.FAILED, QueueItemState.CANCELLED ->
                    TextButton(onClick = { onDismiss(item.id) }) {
                        Text(stringResource(R.string.core_queue_dismiss))
                    }
            }
        }
        if (item.state == QueueItemState.RUNNING) {
            QueueProgressBar(item.progress)
        }
    }
}

@Composable
private fun QueueProgressBar(progress: Float?) {
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp)
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
    }
}

@Composable
private fun DownloadQueueItem.progressLabel(): String {
    val pct = progress?.let { (it * 100).toInt() }
    return if (pct != null) {
        stringResource(R.string.core_queue_state_running_pct, pct)
    } else {
        stringResource(R.string.core_queue_state_running)
    }
}

@Composable
private fun AddonPackRow(
    label: String,
    source: PackSource,
    version: String?,
    pendingPublishQueued: Boolean,
    sessionItem: DownloadQueueItem?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val sessionActive = sessionItem?.state == QueueItemState.QUEUED ||
        sessionItem?.state == QueueItemState.RUNNING
    val (badge, badgeColor) = when {
        sessionItem?.state == QueueItemState.RUNNING ->
            sessionItem.progressLabel() to MaterialTheme.colorScheme.primary
        sessionItem?.state == QueueItemState.QUEUED ->
            stringResource(R.string.core_queue_state_queued) to MaterialTheme.colorScheme.primary
        sessionItem?.state == QueueItemState.FAILED ->
            (sessionItem.message?.let { stringResource(R.string.core_queue_error_prefix, it) }
                ?: stringResource(R.string.core_queue_state_failed)) to ErrorRed
        source == PackSource.DOWNLOADED ->
            stringResource(R.string.core_addon_source_downloaded, version ?: "") to Connected
        source == PackSource.BUNDLED ->
            stringResource(R.string.core_addon_source_bundled) to MaterialTheme.colorScheme.onSurfaceVariant
        pendingPublishQueued ->
            stringResource(R.string.core_addon_queued) to Warning
        else ->
            stringResource(R.string.core_addon_source_missing) to ErrorRed
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(badge, style = MaterialTheme.typography.bodySmall, color = badgeColor)
            if (sessionItem?.state == QueueItemState.RUNNING) {
                VSpacer(6)
                QueueProgressBar(sessionItem.progress)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                sessionActive ->
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.core_queue_cancel))
                    }
                source == PackSource.DOWNLOADED ->
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.core_addon_delete))
                    }
                source == PackSource.BUNDLED -> Unit
                pendingPublishQueued ->
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.core_addon_cancel))
                    }
                else ->
                    TextButton(onClick = onInstall) {
                        Text(stringResource(R.string.core_addon_install))
                    }
            }
        }
    }
}
