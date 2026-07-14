package com.v2rayez.app.ui.viewmodel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.domain.model.Subscription
import com.v2rayez.app.domain.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One launcher-visible app for the split-tunnel picker. [icon] is the real PackageManager icon. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val icon: ImageBitmap? = null
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverRepository: ServerRepository
) : ViewModel() {

    val subscriptions: StateFlow<List<Subscription>> =
        serverRepository.subscriptions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps

    private val _appsLoading = MutableStateFlow(false)
    /** True while the installed-app list (with icons) is being (re)loaded. */
    val appsLoading: StateFlow<Boolean> = _appsLoading

    /**
     * Load the installed-app list with real launcher icons off the main thread. Cached after the
     * first load; pass [force] = true for pull-to-refresh so newly installed/removed apps appear.
     */
    fun loadApps(force: Boolean = false) {
        if (!force && (_apps.value.isNotEmpty() || _appsLoading.value)) return
        viewModelScope.launch {
            _appsLoading.value = true
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { it.packageName != context.packageName }
                        .map { info ->
                            InstalledApp(
                                packageName = info.packageName,
                                label = runCatching { pm.getApplicationLabel(info).toString() }
                                    .getOrDefault(info.packageName),
                                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                icon = runCatching {
                                    pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
                                }.getOrNull()
                            )
                        }
                        .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
                }
                _apps.value = loaded
            } finally {
                _appsLoading.value = false
            }
        }
    }

    private companion object {
        /** Rasterization size for app icons; keeps memory bounded across hundreds of apps. */
        const val ICON_PX = 96
    }

    fun addSubscription(name: String, url: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val r = serverRepository.addSubscription(name, url)
            onResult(if (r.success) "Imported ${r.importedCount} servers" else r.message.ifBlank { "Failed" })
        }
    }

    fun refreshSubscription(id: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val r = serverRepository.refreshSubscription(id)
            onResult(if (r.success) "Updated: ${r.importedCount} servers" else r.message.ifBlank { "Failed" })
        }
    }

    fun deleteSubscription(id: String) = viewModelScope.launch { serverRepository.deleteSubscription(id) }

    fun setSubscriptionEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { serverRepository.setSubscriptionEnabled(id, enabled) }
}
