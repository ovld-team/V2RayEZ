package com.v2rayez.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.R
import com.v2rayez.app.data.core.AddonInstallResult
import com.v2rayez.app.data.core.AddonPackId
import com.v2rayez.app.data.core.AddonPackManager
import com.v2rayez.app.data.core.CoreBinaryManager
import com.v2rayez.app.data.core.GeoAssetManager
import com.v2rayez.app.data.core.GeoDataState
import com.v2rayez.app.data.core.GeoInstallResult
import com.v2rayez.app.data.core.PackSource
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.CORE_VERSION_BUNDLED
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private fun str(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    /**
     * Actionable suffix for a failed download: in a censored network GitHub is often blocked on
     * the clearnet, so tell the user the fix (connect first — downloads then ride the tunnel)
     * instead of a bare "failed". Empty when a tunnel is already up (retry hint only).
     */
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

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _status.asStateFlow()

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

    fun download(type: ProxyCoreType, release: CoreBinaryManager.RemoteRelease) = viewModelScope.launch {
        _busy.value = true
        _status.value = str(R.string.core_status_downloading, type.label, release.tag, release.abi)
        val ok = binaries.downloadAndInstall(type, release, mode = state.value.downloadMode)
        if (ok) {
            settings.update {
                it.copy(selectedCoreVersions = it.selectedCoreVersions + (type to release.tag))
            }
            _status.value = str(R.string.core_status_installed, release.tag, release.abi)
        } else {
            _status.value = str(R.string.core_status_download_failed, binaries.deviceAbiLabel(), downloadFailHint())
        }
        _busy.value = false
    }

    fun deviceAbi(): String = binaries.deviceAbiLabel()

    fun refreshRemotes(type: ProxyCoreType) = viewModelScope.launch {
        _busy.value = true
        val abi = binaries.deviceAbiLabel()
        _status.value = str(R.string.core_status_fetching, type.label, abi)
        val list = binaries.listRemoteReleases(type)
        _remote.value = _remote.value + (type to list)
        _status.value = if (list.isEmpty()) {
            str(R.string.core_status_no_releases, abi, type.label)
        } else {
            str(R.string.core_status_found_releases, list.size, abi)
        }
        _busy.value = false
    }

    fun downloadLatest(type: ProxyCoreType) = viewModelScope.launch {
        if (type == ProxyCoreType.XRAY) {
            _status.value = str(R.string.core_status_xray_optional)
            return@launch
        }
        _busy.value = true
        val abi = binaries.deviceAbiLabel()
        _status.value = str(R.string.core_status_updating, type.label, abi)
        val release = binaries.downloadLatest(type, mode = state.value.downloadMode)
        if (release != null) {
            settings.update {
                it.copy(selectedCoreVersions = it.selectedCoreVersions + (type to release.tag))
            }
            _status.value = str(R.string.core_status_updated, release.tag, abi)
            val remotes = binaries.listRemoteReleases(type)
            _remote.value = _remote.value + (type to remotes)
        } else {
            _status.value = str(R.string.core_status_update_failed, abi, downloadFailHint())
        }
        _busy.value = false
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
        _status.value = str(R.string.core_status_deleted_version, version)
    }

    // ---------------------------------------------------------------- Add-on packs
    // Tor / pluggable transports / ByeDPI — optional native binaries that resolve
    // "downloaded first, then bundled jniLibs, else MISSING" via AddonPackManager (W3),
    // and are downloaded on demand instead of shipped in the APK (W4/W6).

    /** Add-on packs surfaced in the Core-manager "Add-ons" section, in display order. */
    val addonPackIds: List<AddonPackId> = listOf(
        AddonPackId.TOR,
        AddonPackId.LYREBIRD,
        AddonPackId.SNOWFLAKE,
        AddonPackId.WEBTUNNEL,
        AddonPackId.BYEDPI,
        // P7 protocol packs — rows show DOWNLOADED / MISSING with an Install CTA.
        AddonPackId.PSIPHON,
        AddonPackId.DNSTUNNEL
    )

    fun addonSource(packId: AddonPackId): PackSource = addonPacks.packSource(packId)

    fun addonVersion(packId: AddonPackId): String? = addonPacks.installedVersion(packId)

    /** True when [packId] is queued for install in [AppSettings.pendingAddonInstall]. */
    fun isAddonQueued(pending: List<String>, packId: AddonPackId): Boolean =
        pending.any { it.equals(packId.name, ignoreCase = true) }

    /**
     * Install [packId] now if a release source resolves, otherwise queue it into
     * [AppSettings.pendingAddonInstall] so it is picked up once an addon release index exists.
     */
    fun installAddon(packId: AddonPackId) = viewModelScope.launch { installAddonNow(packId) }

    /** Suspending install for one pack — [drainPendingAddons] awaits these sequentially so a
     *  batch never races the shared [_busy] / [_status] the way parallel launches would. */
    private suspend fun installAddonNow(packId: AddonPackId) {
        _busy.value = true
        val release = addonPacks.resolveRelease(packId)
        if (release == null) {
            queueAddonInternal(packId)
            _status.value = str(
                R.string.core_status_addon_queued_publish,
                packId.label,
                packId.name.lowercase(),
                com.v2rayez.app.BuildConfig.ADDONS_GITHUB_REPO
            )
        } else {
            _status.value = str(R.string.core_status_addon_downloading, packId.label, release.version, release.abi)
            when (val r = addonPacks.install(release, state.value.downloadMode)) {
                is AddonInstallResult.Success -> {
                    settings.update { it.copy(pendingAddonInstall = it.pendingAddonInstall.filterNot { id -> id.equals(packId.name, ignoreCase = true) }) }
                    _status.value = str(R.string.core_status_addon_installed, packId.label, r.version)
                }
                is AddonInstallResult.Failed ->
                    _status.value = str(R.string.core_status_addon_install_failed, packId.label, r.reason, downloadFailHint())
            }
        }
        _busy.value = false
    }

    /** Remove [packId] from the pending-install queue. */
    fun cancelAddon(packId: AddonPackId) = viewModelScope.launch {
        settings.update { it.copy(pendingAddonInstall = it.pendingAddonInstall.filterNot { id -> id.equals(packId.name, ignoreCase = true) }) }
        _status.value = str(R.string.core_status_addon_removed, packId.label)
    }

    /** Delete every downloaded version of [packId] from `filesDir/addons/`. */
    fun deleteAddon(packId: AddonPackId) = viewModelScope.launch {
        addonPacks.deleteAll(packId)
        _status.value = str(R.string.core_status_addon_deleted, packId.label)
    }

    // ---------------------------------------------------------------- Geo routing data
    // Full geoip.dat + geosite.dat are on-demand downloads since v0.9.50; out of the box the
    // packaged mini geoip (cn + private) backs LAN-bypass / CN routing and geosite rules are
    // gated off by ConfigBuilder.

    fun geoState(): GeoDataState = geoAssets.state()

    fun geoInstalledLabel(): String? = geoAssets.installedLabel()

    fun downloadGeo() = viewModelScope.launch {
        _busy.value = true
        _status.value = str(R.string.core_status_geo_downloading)
        _status.value = when (val r = geoAssets.download(mode = state.value.downloadMode)) {
            is GeoInstallResult.Success ->
                str(R.string.core_status_geo_installed, r.geoipBytes / 1_048_576.0, r.geositeBytes / 1_048_576.0)
            is GeoInstallResult.Failed -> str(R.string.core_status_geo_download_failed, r.reason, downloadFailHint())
        }
        _busy.value = false
    }

    fun deleteGeo() = viewModelScope.launch {
        geoAssets.delete()
        _status.value = str(R.string.core_status_geo_deleted)
    }

    private suspend fun queueAddonInternal(packId: AddonPackId) {
        settings.update {
            it.copy(pendingAddonInstall = (it.pendingAddonInstall + packId.name.lowercase()).distinct())
        }
    }

    /** Attempt install for every id in [AppSettings.pendingAddonInstall] that still resolves. */
    fun drainPendingAddons() = viewModelScope.launch {
        val pending = settings.current().pendingAddonInstall.toList()
        if (pending.isEmpty()) return@launch
        for (id in pending) {
            val coreType = when (id.lowercase().replace('_', '-')) {
                "sing-box", "singbox" -> ProxyCoreType.SING_BOX
                "mihomo", "clash" -> ProxyCoreType.CLASH
                else -> null
            }
            if (coreType != null) {
                _busy.value = true
                _status.value = str(R.string.core_status_addon_installing_queued, coreType.label, binaries.deviceAbiLabel())
                val release = binaries.downloadLatest(coreType, mode = settings.current().downloadMode)
                if (release != null) {
                    settings.update {
                        it.copy(
                            selectedCoreVersions = it.selectedCoreVersions + (coreType to release.tag),
                            pendingAddonInstall = it.pendingAddonInstall.filterNot { x -> x.equals(id, true) }
                        )
                    }
                    _status.value = str(R.string.core_status_addon_installed_queued, coreType.label, release.tag)
                } else {
                    _status.value = str(R.string.core_status_addon_queued_failed, coreType.label, downloadFailHint())
                }
                _busy.value = false
                continue
            }
            val pack = AddonPackId.fromId(id) ?: continue
            if (addonPacks.isAvailable(pack) && addonPacks.packSource(pack) == PackSource.DOWNLOADED) {
                settings.update {
                    it.copy(pendingAddonInstall = it.pendingAddonInstall.filterNot { x -> x.equals(id, true) })
                }
                continue
            }
            installAddonNow(pack)
        }
    }
}
