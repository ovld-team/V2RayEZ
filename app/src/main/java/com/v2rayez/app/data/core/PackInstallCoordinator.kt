package com.v2rayez.app.data.core

import android.content.Context
import com.v2rayez.app.R
import com.v2rayez.app.data.analytics.RemoteTelemetry
import com.v2rayez.app.data.download.DownloadPhase
import com.v2rayez.app.data.download.DownloadProgress
import com.v2rayez.app.data.download.DownloadTransport
import com.v2rayez.app.data.repository.logCore
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped serial download queue for cores / addon packs / geo.
 *
 * Owns the same queue [CoreManagerViewModel] displays so wizard / cold-start drains start work
 * immediately without waiting for the Core Manager screen, while progress stays shared.
 */
@Singleton
class PackInstallCoordinator @Inject constructor(
    private val settings: SettingsRepository,
    private val binaries: CoreBinaryManager,
    private val addonPacks: AddonPackManager,
    private val vpn: VpnController,
    private val downloadTransport: DownloadTransport,
    private val logs: LogRepository,
    private val remoteTelemetry: RemoteTelemetry,
    @ApplicationContext private val appContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun str(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)
    private fun logCore(level: LogLevel, message: String) {
        logs.logCore(level, message)
        remoteTelemetry.addLogBreadcrumb("core_manager", level, message)
    }

    private fun downloadFailHint(): String =
        if (vpn.connectionState.value.status == ConnectionStatus.CONNECTED) {
            str(R.string.core_hint_check_connection)
        } else {
            str(R.string.core_hint_connect_first)
        }

    private data class QueueJob(val id: String, val action: suspend () -> QueueOutcome)

    private val jobChannel = Channel<QueueJob>(Channel.UNLIMITED)
    private val cancelledIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val cancelActions = ConcurrentHashMap<String, () -> Unit>()
    private val drainMutex = Mutex()

    private val _queue = MutableStateFlow<List<DownloadQueueItem>>(emptyList())
    val queue: StateFlow<List<DownloadQueueItem>> = _queue.asStateFlow()

    private val _status = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _status.asStateFlow()

    /** True while any queued item is actively downloading. */
    val hasActiveDownload: StateFlow<Boolean> = _queue
        .map { q -> q.any { it.state == QueueItemState.RUNNING || it.state == QueueItemState.QUEUED } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Home banner: set when a background drain actually enqueues work; cleared when the
     * active queue drains or the user dismisses it.
     */
    private val _homeInstallBanner = MutableStateFlow(false)
    val homeInstallBanner: StateFlow<Boolean> = _homeInstallBanner.asStateFlow()

    init {
        scope.launch {
            for (job in jobChannel) {
                if (cancelledIds.remove(job.id)) continue
                _queue.update { list ->
                    list.map {
                        if (it.id == job.id) it.copy(state = QueueItemState.RUNNING, message = null) else it
                    }
                }
                val outcome = runCatching { job.action() }.getOrElse {
                    QueueOutcome(QueueItemState.FAILED, it.message)
                }
                _queue.update { list ->
                    list.map {
                        if (it.id == job.id) {
                            it.copy(
                                state = outcome.state,
                                message = outcome.message,
                                progress = if (outcome.state == QueueItemState.SUCCESS) 1f else it.progress
                            )
                        } else it
                    }
                }
                cancelActions.remove(job.id)
                refreshHomeBanner()
                if (outcome.state == QueueItemState.FAILED &&
                    settings.current().pendingAddonInstall.isNotEmpty()
                ) {
                    drainPendingAddons(showHomeBanner = false)
                }
            }
        }
        scope.launch {
            downloadTransport.progress.collect { map -> applyProgress(map) }
        }
    }

    /** Cold-start + post-wizard: drain pending packs whenever the persisted queue is non-empty. */
    fun start() {
        scope.launch {
            // Eager cold-start pass so FAILED leftovers from a previous session retry even if
            // settings Flow re-emits the same fingerprint first.
            if (settings.current().pendingAddonInstall.isNotEmpty()) {
                drainPendingAddons(showHomeBanner = true)
            }
            var lastPendingFingerprint = settings.current().pendingAddonInstall.joinToString(",")
            settings.settings().collect { s ->
                val fingerprint = s.pendingAddonInstall.joinToString(",")
                if (fingerprint.isNotEmpty() && fingerprint != lastPendingFingerprint) {
                    lastPendingFingerprint = fingerprint
                    drainPendingAddons(showHomeBanner = true)
                } else if (fingerprint.isEmpty()) {
                    lastPendingFingerprint = ""
                }
            }
        }
    }

    fun dismissHomeBanner() {
        _homeInstallBanner.value = false
    }

    private fun refreshHomeBanner() {
        if (!_queue.value.any { it.state == QueueItemState.QUEUED || it.state == QueueItemState.RUNNING }) {
            _homeInstallBanner.value = false
        }
    }

    private fun matchTags(item: DownloadQueueItem, map: Map<String, DownloadProgress>): List<DownloadProgress> =
        when {
            item.progressTags.isNotEmpty() -> item.progressTags.mapNotNull { map[it] }
            item.progressTagPrefix != null ->
                map.filterKeys { it.startsWith(item.progressTagPrefix) }.values.toList()
            else -> map[item.id]?.let { listOf(it) } ?: emptyList()
        }

    private fun applyProgress(map: Map<String, DownloadProgress>) {
        _queue.update { list ->
            list.map { item ->
                if (item.state != QueueItemState.RUNNING) return@map item
                val matches = matchTags(item, map)
                if (matches.isEmpty()) return@map item
                val fractions = matches.mapNotNull { it.fraction }
                val fraction = if (fractions.size == matches.size) fractions.average().toFloat() else null
                item.copy(progress = fraction)
            }
        }
    }

    fun cancelledOrFailed(success: Boolean, vararg tags: String): QueueItemState {
        if (success) return QueueItemState.SUCCESS
        val cancelled = tags.any { downloadTransport.progress.value[it]?.phase == DownloadPhase.CANCELLED }
        return if (cancelled) QueueItemState.CANCELLED else QueueItemState.FAILED
    }

    fun enqueue(
        id: String,
        kind: QueueItemKind,
        label: String,
        subLabel: String,
        cancellable: Boolean = true,
        progressTags: List<String> = emptyList(),
        progressTagPrefix: String? = null,
        cancel: () -> Unit = {},
        action: suspend () -> QueueOutcome
    ) {
        val existing = _queue.value.firstOrNull { it.id == id }
        if (existing != null && existing.state in listOf(QueueItemState.QUEUED, QueueItemState.RUNNING)) return
        cancelActions[id] = cancel
        val item = DownloadQueueItem(
            id = id,
            kind = kind,
            label = label,
            subLabel = subLabel,
            cancellable = cancellable,
            progressTags = progressTags,
            progressTagPrefix = progressTagPrefix
        )
        _queue.update { list -> if (existing != null) list.map { if (it.id == id) item else it } else list + item }
        logCore(LogLevel.INFO, str(R.string.log_core_queue_added, label, subLabel))
        scope.launch { jobChannel.send(QueueJob(id, action)) }
    }

    fun cancelQueueItem(id: String) {
        val item = _queue.value.firstOrNull { it.id == id } ?: return
        when (item.state) {
            QueueItemState.QUEUED -> {
                cancelledIds.add(id)
                _queue.update { list ->
                    list.map { if (it.id == id) it.copy(state = QueueItemState.CANCELLED) else it }
                }
            }
            QueueItemState.RUNNING -> cancelActions[id]?.invoke()
            else -> Unit
        }
        refreshHomeBanner()
    }

    fun dismissQueueItem(id: String) {
        _queue.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinishedQueueItems() {
        _queue.update { list ->
            list.filter { it.state == QueueItemState.QUEUED || it.state == QueueItemState.RUNNING }
        }
    }

    fun setStatus(message: String) {
        _status.value = message
    }

    fun addonQueueItem(packId: AddonPackId): DownloadQueueItem? =
        _queue.value.firstOrNull {
            it.kind == QueueItemKind.ADDON && it.id.startsWith("addon:${packId.name}:")
        }

    fun geoQueueItem(): DownloadQueueItem? =
        _queue.value.firstOrNull { it.kind == QueueItemKind.GEO }

    fun installAddon(packId: AddonPackId) {
        scope.launch { installAddonNow(packId) }
    }

    suspend fun installAddonNow(packId: AddonPackId) {
        val release = addonPacks.resolveRelease(packId)
        if (release == null) {
            queueAddonInternal(packId)
            _status.value = str(
                R.string.core_status_addon_queued_publish,
                packId.label,
                packId.name.lowercase(),
                com.v2rayez.app.BuildConfig.ADDONS_GITHUB_REPO
            )
            return
        }
        val id = "addon:${packId.name}:${release.version}"
        enqueue(
            id = id,
            kind = QueueItemKind.ADDON,
            label = packId.label,
            subLabel = release.version,
            cancel = { addonPacks.cancelInstall(packId, release.version) }
        ) {
            _status.value = str(R.string.core_status_addon_downloading, packId.label, release.version, release.abi)
            logCore(LogLevel.INFO, str(R.string.log_addon_download_start, packId.label, release.version))
            when (val r = addonPacks.install(release, settings.current().downloadMode)) {
                is AddonInstallResult.Success -> {
                    settings.update {
                        it.copy(
                            pendingAddonInstall = it.pendingAddonInstall.filterNot { x ->
                                x.equals(packId.name, ignoreCase = true)
                            }
                        )
                    }
                    _status.value = str(R.string.core_status_addon_installed, packId.label, r.version)
                    logCore(LogLevel.INFO, str(R.string.log_addon_download_ok, packId.label, r.version))
                    QueueOutcome(QueueItemState.SUCCESS)
                }
                is AddonInstallResult.Failed -> {
                    _status.value = str(
                        R.string.core_status_addon_install_failed,
                        packId.label,
                        r.reason,
                        downloadFailHint()
                    )
                    logCore(LogLevel.ERROR, str(R.string.log_addon_download_fail, packId.label, r.reason))
                    QueueOutcome(cancelledOrFailed(false, id), r.reason)
                }
            }
        }
    }

    private suspend fun queueAddonInternal(packId: AddonPackId) {
        settings.update {
            it.copy(pendingAddonInstall = (it.pendingAddonInstall + packId.name.lowercase()).distinct())
        }
    }

    /**
     * Attempt install for every id in [AppSettings.pendingAddonInstall] that still needs work.
     * Safe to call repeatedly — mutex + enqueue de-dupe prevent double downloads.
     */
    fun drainPendingAddons(showHomeBanner: Boolean = false) {
        scope.launch {
            drainMutex.withLock {
                val pending = settings.current().pendingAddonInstall.toList()
                if (pending.isEmpty()) return@withLock
                var enqueued = 0
                for (id in pending) {
                    val coreType = when (id.lowercase().replace('_', '-')) {
                        "sing-box", "singbox" -> ProxyCoreType.SING_BOX
                        "mihomo", "clash" -> ProxyCoreType.CLASH
                        else -> null
                    }
                    if (coreType != null) {
                        val already = _queue.value.any {
                            it.id == "core-queued:${coreType.name}" &&
                                it.state in listOf(QueueItemState.QUEUED, QueueItemState.RUNNING)
                        }
                        if (already) continue
                        enqueue(
                            id = "core-queued:${coreType.name}",
                            kind = QueueItemKind.CORE,
                            label = coreType.label,
                            subLabel = str(R.string.core_latest_label),
                            cancellable = false,
                            progressTagPrefix = "core:${coreType.name}:"
                        ) {
                            _status.value = str(
                                R.string.core_status_addon_installing_queued,
                                coreType.label,
                                binaries.deviceAbiLabel()
                            )
                            val release = binaries.downloadLatest(coreType, mode = settings.current().downloadMode)
                            if (release != null) {
                                settings.update {
                                    it.copy(
                                        selectedCoreVersions = it.selectedCoreVersions + (coreType to release.tag),
                                        pendingAddonInstall = it.pendingAddonInstall.filterNot { x ->
                                            x.equals(id, true)
                                        }
                                    )
                                }
                                _status.value = str(
                                    R.string.core_status_addon_installed_queued,
                                    coreType.label,
                                    release.tag
                                )
                                QueueOutcome(QueueItemState.SUCCESS)
                            } else {
                                _status.value = str(
                                    R.string.core_status_addon_queued_failed,
                                    coreType.label,
                                    downloadFailHint()
                                )
                                QueueOutcome(QueueItemState.FAILED, _status.value)
                            }
                        }
                        enqueued++
                        continue
                    }
                    val pack = AddonPackId.fromId(id) ?: continue
                    // Bundled or already downloaded — clear pending, no network.
                    if (addonPacks.isAvailable(pack)) {
                        settings.update {
                            it.copy(
                                pendingAddonInstall = it.pendingAddonInstall.filterNot { x ->
                                    x.equals(id, true)
                                }
                            )
                        }
                        continue
                    }
                    val alreadyAddon = addonQueueItem(pack)?.state in
                        listOf(QueueItemState.QUEUED, QueueItemState.RUNNING)
                    if (alreadyAddon) continue
                    installAddonNow(pack)
                    enqueued++
                }
                if (showHomeBanner && enqueued > 0) {
                    _homeInstallBanner.value = true
                }
            }
        }
    }
}
