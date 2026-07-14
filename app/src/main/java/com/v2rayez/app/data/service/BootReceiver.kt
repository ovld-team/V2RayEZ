package com.v2rayez.app.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import com.v2rayez.app.data.repository.logVpn
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reconnects the last server on boot when the dedicated [com.v2rayez.app.domain.model.AppSettings.bootAutoConnect]
 * ("Connect on boot") setting is enabled; always refreshes widgets.
 *
 * Deliberately does **not** fall back to [com.v2rayez.app.domain.model.AppSettings.autoConnect] —
 * that Home toggle's own copy only promises "pick the fastest server when you tap connect", never
 * a boot-time reconnect, so OR-ing it into the boot gate silently connected users who never asked
 * for that (W-03 / boot-copy honesty).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var logRepository: LogRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { com.v2rayez.app.data.widget.VpnWidgetUpdater.refreshAll(context) }
                runCatching { migrateLegacyAutoConnectOnce() }
                val settings = settingsRepository.current()
                val lastServerId = settings.lastServerId
                if (settings.bootAutoConnect && lastServerId != null) {
                    maybeBootConnect(context, lastServerId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Runs [legacyAutoConnectMigration] against the persisted settings (see its doc for why). */
    private suspend fun migrateLegacyAutoConnectOnce() {
        val current = settingsRepository.current()
        if (current.legacyAutoConnectBootMigrated) return
        settingsRepository.update { legacyAutoConnectMigration(it) }
    }

    /**
     * A [BroadcastReceiver] can never drive the [VpnService.prepare] consent Activity flow the
     * way [com.v2rayez.app.MainActivity] does — there is no Activity context to launch the
     * system dialog from. Only start the tunnel when consent was already granted in a prior
     * session ([VpnService.prepare] returning `null`); otherwise skip instead of letting
     * `establish()` fail deep inside [V2RayVpnService], and leave a breadcrumb in the in-app
     * Logs so a user who enabled "Connect on boot" isn't left guessing why nothing happened
     * (W-02, 20-workers-boot.md).
     */
    private fun maybeBootConnect(context: Context, lastServerId: String) {
        val consentIntent = runCatching { VpnService.prepare(context) }.getOrElse {
            Log.w(TAG, "VpnService.prepare threw on boot — skipping auto-connect", it)
            return
        }
        if (consentIntent != null) {
            Log.i(TAG, "Boot auto-connect skipped — VPN permission not granted yet")
            runCatching {
                logRepository.logVpn(
                    LogLevel.WARNING,
                    "Skipped connect-on-boot",
                    "VPN permission not granted yet — open the app once to grant it"
                )
            }
            return
        }
        val svc = Intent(context, V2RayVpnService::class.java)
            .setAction(V2RayVpnService.ACTION_CONNECT)
            .putExtra(V2RayVpnService.EXTRA_SERVER_ID, lastServerId)
        ContextCompat.startForegroundService(context, svc)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}

/**
 * Runs at most once per install (guarded by [AppSettings.legacyAutoConnectBootMigrated]):
 * installs that predate the dedicated [AppSettings.bootAutoConnect] flag relied on the removed
 * `bootAutoConnect || autoConnect` OR to get boot reconnect from the Home "Auto Connect" toggle
 * alone. Fold that into an explicit `bootAutoConnect = true` exactly once so those users keep the
 * behavior they already had; every later toggle of either setting is fully independent after
 * this runs. No-op (besides flipping the guard) for anyone who already has an explicit
 * [AppSettings.bootAutoConnect] preference or never had [AppSettings.autoConnect] on.
 *
 * Pure (no Android/DataStore deps) so it's directly unit-testable.
 */
internal fun legacyAutoConnectMigration(current: AppSettings): AppSettings {
    if (current.legacyAutoConnectBootMigrated) return current
    val shouldGrantBootConnect = !current.bootAutoConnect && current.autoConnect
    return current.copy(
        bootAutoConnect = current.bootAutoConnect || shouldGrantBootConnect,
        legacyAutoConnectBootMigrated = true
    )
}
