package com.v2rayez.app.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Process-wide MITM standalone-proxy running / error state (read by UI + service). */
@Singleton
class MitmProxyStateHolder @Inject constructor() {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setError(message: String?) {
        _error.value = message
    }
}
