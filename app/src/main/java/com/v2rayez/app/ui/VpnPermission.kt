package com.v2rayez.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Requests Android VPN consent (VpnService.prepare) then runs [onGranted]. */
fun interface VpnPermissionRequester {
    fun request(onGranted: () -> Unit)
}

/** Provided by the host Activity; no-op fallback for previews. */
val LocalVpnPermission = staticCompositionLocalOf<VpnPermissionRequester> {
    VpnPermissionRequester { onGranted -> onGranted() }
}
