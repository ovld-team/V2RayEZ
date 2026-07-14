package com.v2rayez.app.ui.viewmodel

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2rayez.app.data.cert.MitmCaStore
import com.v2rayez.app.data.mock.MockMitmProxyController
import com.v2rayez.app.data.mock.MockSettingsRepository
import com.v2rayez.app.domain.repository.MitmProxyController
import com.v2rayez.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Built-in Browser state: MITM readiness, whether the standalone proxy is running,
 * HTTP proxy port for WebView [android.webkit.ProxyController], and API gate for proxy override.
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    settings: SettingsRepository,
    private val proxy: MitmProxyController,
    @ApplicationContext private val context: Context?
) : ViewModel() {

    /** Preview / no-arg constructor. */
    constructor() : this(MockSettingsRepository(), MockMitmProxyController(), null)

    val mitmReady: StateFlow<Boolean> = settings.settings()
        .map { s ->
            s.mitm.enabled && s.mitm.caInstallAcknowledged &&
                (context?.let(MitmCaStore::isPresent) ?: false)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True when the standalone MITM SOCKS/HTTP proxy is listening. */
    val proxyRunning: StateFlow<Boolean> = proxy.running

    val httpPort: StateFlow<Int> = settings.settings()
        .map { it.mitm.httpPort }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10809)

    /** WebView ProxyController.setProxyOverride needs API 30+. */
    val proxyApiSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Banner "MITM active" when set up and proxy is up. */
    val ready: StateFlow<Boolean> = combine(mitmReady, proxyRunning) { ready, running ->
        ready && running
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
