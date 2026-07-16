package com.v2rayez.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.R
import com.v2rayez.app.data.analytics.FirebaseTelemetry
import com.v2rayez.app.data.core.AddonPackId
import com.v2rayez.app.data.core.AddonPackManager
import com.v2rayez.app.data.core.CoreBinaryManager
import com.v2rayez.app.data.core.DownloadQueueItem
import com.v2rayez.app.data.core.GeoAssetManager
import com.v2rayez.app.data.core.GeoDataState
import com.v2rayez.app.data.core.GeoInstallResult
import com.v2rayez.app.data.core.PackInstallCoordinator
import com.v2rayez.app.data.core.PackSource
import com.v2rayez.app.data.core.QueueItemKind
import com.v2rayez.app.data.core.QueueItemState
import com.v2rayez.app.data.core.QueueOutcome
import com.v2rayez.app.data.repository.logCore
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.CORE_VERSION_BUNDLED
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CoreManagerViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val binaries: CoreBinaryManager,
    private val addonPacks: AddonPackManager,
    private val geoAssets: GeoAssetManager,
    private val vpn: VpnController,
    private val logs: LogRepository,
    private val packInstalls: PackInstallCoordinator,
    private val firebaseTelemetry: FirebaseTelemetry,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private fun str(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)
    private fun logCore(level: LogLevel, message: String) {
        logs.logCore(level, message)
        firebaseTelemetry.addLogBreadcrumb("core_manager", level, message)
    }

    private fun downloadFailHint(): String =
        if (vpn.connectionState.value.status == ConnectionStatus.CONNECTED) {
            str(R.string.core_hint_check_connection)
        } else {
            str(R.string.core_hint_connect_first)
        }

    val state: StateFlow<AppSettings> =
        settings.settings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _remote = MutableStateFlow<Map<ProxyCoreType, List<CoreBinaryManager.RemoteRelease>>>(emptyMap())
    val remoteReleases: StateFlow<Map<ProxyCoreType, List<CoreBinaryManager.RemoteRelease>>> = _remote.asStateFlow()

    private val _manualBusy = MutableStateFlow(false)

    /** Shared with [PackInstallCoordinator] so background / Core Manager show the same status. */
    val statusMessage: StateFlow<String> = packInstalls.statusMessage

    /** Session queue — shared with background wizard / cold-start installs. */
    val queue: StateFlow<List<DownloadQueueItem>> = packInstalls.queue

    val busy: StateFlow<Boolean> = combine(_manualBusy, packInstalls.queue) { manual, q ->
        manual || q.any { it.state == QueueItemState.RUNNING }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun cancelQueueItem(id: String) = packInstalls.cancelQueueItem(id)

    fun dismissQueueItem(id: String) = packInstalls.dismissQueueItem(id)

    fun clearFinishedQueueItems() = packInstalls.clearFinishedQueueItems()

    fun installed(type: ProxyCoreType): List<String> = binaries.installedVersions(type)

    fun bundledLabel(type: ProxyCoreType): String = binaries.bundledVersionLabel(type)

    fun setDefaultCore(type: ProxyCoreType) = viewModelScope.launch {
        settings.update { it.copy(defaultCore = type) }
    }

    fun selectVersion(type: ProxyCoreType, version: String) = viewModelScope.launch {
        settings.update {
            it.copy(selectedCoreVersions = it.selectedCoreVersions + (type to version))
        }
    }

    fun download(type: ProxyCoreType, release: CoreBinaryManager.RemoteRelease) {
        val id = binaries.downloadTag(type, release.tag)
        packInstalls.enqueue(
            id = id,
            kind = QueueItemKind.CORE,
            label = type.label,
            subLabel = release.tag,
            cancel = { binaries.cancelDownload(type, release.tag) }
        ) {
            logCore(LogLevel.INFO, str(R.string.log_core_download_start, type.label, release.tag, release.abi))
            val ok = binaries.downloadAndInstall(type, release, mode = state.value.downloadMode)
            if (ok) {
                settings.update {
                    it.copy(selectedCoreVersions = it.selectedCoreVersions + (type to release.tag))
                }
                packInstalls.setStatus(str(R.string.core_status_installed, release.tag, release.abi))
                logCore(LogLevel.INFO, str(R.string.log_core_download_ok, type.label, release.tag))
            } else {
                packInstalls.setStatus(str(R.string.core_status_download_failed, binaries.deviceAbiLabel(), downloadFailHint()))
                logCore(LogLevel.ERROR, str(R.string.log_core_download_fail, type.label, release.tag))
            }
            QueueOutcome(packInstalls.cancelledOrFailed(ok, id), if (ok) null else packInstalls.statusMessage.value)
        }
    }

    fun deviceAbi(): String = binaries.deviceAbiLabel()

    fun refreshRemotes(type: ProxyCoreType) = viewModelScope.launch {
        _manualBusy.value = true
        val abi = binaries.deviceAbiLabel()
        packInstalls.setStatus(str(R.string.core_status_fetching, type.label, abi))
        val list = binaries.listRemoteReleases(type)
        _remote.value = _remote.value + (type to list)
        packInstalls.setStatus(
            if (list.isEmpty()) {
                str(R.string.core_status_no_releases, abi, type.label)
            } else {
                str(R.string.core_status_found_releases, list.size, abi)
            }
        )
        _manualBusy.value = false
    }

    fun downloadLatest(type: ProxyCoreType) {
        if (type == ProxyCoreType.XRAY) {
            packInstalls.setStatus(str(R.string.core_status_xray_optional))
            return
        }
        val id = "core-latest:${type.name}"
        packInstalls.enqueue(
            id = id,
            kind = QueueItemKind.CORE,
            label = type.label,
            subLabel = str(R.string.core_latest_label),
            cancellable = false,
            progressTagPrefix = "core:${type.name}:"
        ) {
            val abi = binaries.deviceAbiLabel()
            packInstalls.setStatus(str(R.string.core_status_updating, type.label, abi))
            logCore(LogLevel.INFO, str(R.string.log_core_update_start, type.label, abi))
            val release = binaries.downloadLatest(type, mode = state.value.downloadMode)
            if (release != null) {
                settings.update {
                    it.copy(selectedCoreVersions = it.selectedCoreVersions + (type to release.tag))
                }
                packInstalls.setStatus(str(R.string.core_status_updated, release.tag, abi))
                logCore(LogLevel.INFO, str(R.string.log_core_update_ok, type.label, release.tag))
                val remotes = binaries.listRemoteReleases(type)
                _remote.value = _remote.value + (type to remotes)
                QueueOutcome(QueueItemState.SUCCESS)
            } else {
                packInstalls.setStatus(str(R.string.core_status_update_failed, abi, downloadFailHint()))
                logCore(LogLevel.ERROR, str(R.string.log_core_update_fail, type.label))
                QueueOutcome(QueueItemState.FAILED, packInstalls.statusMessage.value)
            }
        }
    }

    fun isBundledRunnable(type: ProxyCoreType): Boolean = binaries.isBundledRunnable(type)

    fun deleteVersion(type: ProxyCoreType, version: String) = viewModelScope.launch {
        if (version == CORE_VERSION_BUNDLED) return@launch
        binaries.deleteVersion(type, version)
        settings.update {
            val cur = it.selectedCoreVersions[type]
            if (cur == version) {
                it.copy(selectedCoreVersions = it.selectedCoreVersions + (type to CORE_VERSION_BUNDLED))
            } else it
        }
        packInstalls.setStatus(str(R.string.core_status_deleted_version, version))
        logCore(LogLevel.INFO, str(R.string.log_core_deleted, type.label, version))
    }

    /** Add-on packs surfaced in the Core-manager "Add-ons" section, in display order. */
    val addonPackIds: List<AddonPackId> = listOf(
        AddonPackId.TOR,
        AddonPackId.LYREBIRD,
        AddonPackId.SNOWFLAKE,
        AddonPackId.WEBTUNNEL,
        AddonPackId.BYEDPI,
        AddonPackId.PSIPHON,
        AddonPackId.DNSTUNNEL
    )

    fun addonSource(packId: AddonPackId): PackSource = addonPacks.packSource(packId)

    fun addonVersion(packId: AddonPackId): String? = addonPacks.installedVersion(packId)

    fun isAddonQueued(pending: List<String>, packId: AddonPackId): Boolean =
        pending.any { it.equals(packId.name, ignoreCase = true) }

    fun addonQueueItem(packId: AddonPackId): DownloadQueueItem? = packInstalls.addonQueueItem(packId)

    fun installAddon(packId: AddonPackId) = packInstalls.installAddon(packId)

    fun cancelAddon(packId: AddonPackId) = viewModelScope.launch {
        settings.update {
            it.copy(
                pendingAddonInstall = it.pendingAddonInstall.filterNot { id ->
                    id.equals(packId.name, ignoreCase = true)
                }
            )
        }
        addonQueueItem(packId)?.let { cancelQueueItem(it.id) }
        packInstalls.setStatus(str(R.string.core_status_addon_removed, packId.label))
    }

    fun deleteAddon(packId: AddonPackId) = viewModelScope.launch {
        addonPacks.deleteAll(packId)
        packInstalls.setStatus(str(R.string.core_status_addon_deleted, packId.label))
        logCore(LogLevel.INFO, str(R.string.log_addon_deleted, packId.label))
    }

    fun geoState(): GeoDataState = geoAssets.state()

    fun geoInstalledLabel(): String? = geoAssets.installedLabel()

    fun geoQueueItem(): DownloadQueueItem? = packInstalls.geoQueueItem()

    fun downloadGeo() {
        val id = "geo:full"
        packInstalls.enqueue(
            id = id,
            kind = QueueItemKind.GEO,
            label = str(R.string.core_geo_pack_label),
            subLabel = "",
            progressTags = geoAssets.downloadTags(),
            cancel = { geoAssets.cancelDownload() }
        ) {
            packInstalls.setStatus(str(R.string.core_status_geo_downloading))
            logCore(LogLevel.INFO, str(R.string.log_geo_download_start))
            when (val r = geoAssets.download(mode = state.value.downloadMode)) {
                is GeoInstallResult.Success -> {
                    packInstalls.setStatus(
                        str(
                            R.string.core_status_geo_installed,
                            r.geoipBytes / 1_048_576.0,
                            r.geositeBytes / 1_048_576.0
                        )
                    )
                    logCore(LogLevel.INFO, str(R.string.log_geo_download_ok))
                    QueueOutcome(QueueItemState.SUCCESS)
                }
                is GeoInstallResult.Failed -> {
                    packInstalls.setStatus(
                        str(R.string.core_status_geo_download_failed, r.reason, downloadFailHint())
                    )
                    logCore(LogLevel.ERROR, str(R.string.log_geo_download_fail, r.reason))
                    QueueOutcome(
                        packInstalls.cancelledOrFailed(false, *geoAssets.downloadTags().toTypedArray()),
                        r.reason
                    )
                }
            }
        }
    }

    fun deleteGeo() = viewModelScope.launch {
        geoAssets.delete()
        packInstalls.setStatus(str(R.string.core_status_geo_deleted))
        logCore(LogLevel.INFO, str(R.string.log_geo_deleted))
    }

    /** Drain persisted pending packs — usually already running from Application / onboarding. */
    fun drainPendingAddons() = packInstalls.drainPendingAddons(showHomeBanner = false)
}
