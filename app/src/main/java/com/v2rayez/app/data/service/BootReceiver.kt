package com.v2rayez.app.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.v2rayez.app.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Reconnects the last server on boot when auto-connect is enabled; always refreshes widgets. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { com.v2rayez.app.data.widget.VpnWidgetUpdater.refreshAll(context) }
                val settings = settingsRepository.current()
                // Prefer explicit boot flag; fall back to legacy autoConnect for upgrades.
                val shouldBootConnect = settings.bootAutoConnect || settings.autoConnect
                if (shouldBootConnect && settings.lastServerId != null) {
                    val svc = Intent(context, V2RayVpnService::class.java)
                        .setAction(V2RayVpnService.ACTION_CONNECT)
                        .putExtra(V2RayVpnService.EXTRA_SERVER_ID, settings.lastServerId)
                    ContextCompat.startForegroundService(context, svc)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
