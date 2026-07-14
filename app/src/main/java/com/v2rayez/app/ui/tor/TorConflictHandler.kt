package com.v2rayez.app.ui.tor

import androidx.annotation.StringRes
import com.v2rayez.app.R
import com.v2rayez.app.data.tor.TorConflictRemediation
import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI model for [com.v2rayez.app.ui.components.TorConflictDialog]. */
data class TorConflictUi(
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
    @StringRes val confirmRes: Int = R.string.tor_conflict_disable_and_continue
)

enum class TorConflictRemediationKind {
    /** Turn off Tor route-all (and optionally Tor entirely). */
    DISABLE_TOR,
    /** Turn off MITM capture-all so Tor whole-device can proceed. */
    DISABLE_MITM_CAPTURE
}

/**
 * Shared confirm-and-proceed gate: if [blocked], show a dialog; on confirm run remediation
 * then the original [action].
 */
class TorConflictHandler(
    private val settings: SettingsRepository,
    private val vpn: VpnController,
    private val scope: CoroutineScope,
    private val torController: TorController? = null
) {
    private val _dialog = MutableStateFlow<TorConflictUi?>(null)
    val dialog: StateFlow<TorConflictUi?> = _dialog.asStateFlow()

    private var pendingAction: (suspend () -> Unit)? = null
    private var pendingKind: TorConflictRemediationKind = TorConflictRemediationKind.DISABLE_TOR
    private var pendingStopDaemon: Boolean = false

    fun runOrPrompt(
        blocked: Boolean,
        @StringRes messageRes: Int,
        @StringRes titleRes: Int = R.string.tor_conflict_title,
        @StringRes confirmRes: Int = R.string.tor_conflict_disable_and_continue,
        kind: TorConflictRemediationKind = TorConflictRemediationKind.DISABLE_TOR,
        stopDaemon: Boolean = false,
        action: suspend () -> Unit
    ) {
        scope.launch {
            if (!blocked) {
                action()
                return@launch
            }
            pendingAction = action
            pendingKind = kind
            pendingStopDaemon = stopDaemon
            _dialog.value = TorConflictUi(titleRes, messageRes, confirmRes)
        }
    }

    fun confirm() {
        scope.launch {
            val action = pendingAction
            val kind = pendingKind
            val stopDaemon = pendingStopDaemon
            pendingAction = null
            _dialog.value = null
            val remediationsOk = when (kind) {
                TorConflictRemediationKind.DISABLE_TOR ->
                    TorConflictRemediation.disableTorBlocking(
                        settings = settings,
                        vpn = vpn,
                        torController = torController,
                        disableEnabled = stopDaemon
                    )
                TorConflictRemediationKind.DISABLE_MITM_CAPTURE ->
                    TorConflictRemediation.disableMitmCaptureBlocking(settings, vpn)
            }
            // Don't run the gated action if disconnect never completed while still CONNECTED.
            if (remediationsOk) action?.invoke()
        }
    }

    fun dismiss() {
        pendingAction = null
        _dialog.value = null
    }
}
