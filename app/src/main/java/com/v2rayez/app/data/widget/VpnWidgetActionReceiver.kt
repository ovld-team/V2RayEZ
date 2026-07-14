package com.v2rayez.app.data.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.v2rayez.app.MainActivity
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles widget button taps.
 *
 * Do **not** call [android.net.VpnService.prepare] from a BroadcastReceiver — that trips
 * AppOps SecurityException on several OEMs/Android versions ("package under uid but it is not").
 * Disconnect can stay here; connect always goes through [MainActivity].
 */
@AndroidEntryPoint
class VpnWidgetActionReceiver : BroadcastReceiver() {

    @Inject lateinit var vpnController: VpnController

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_TOGGLE -> handleToggle(context)
            ACTION_OPEN_SERVERS -> openActivity(context, MainActivity.ACTION_SHORTCUT_SERVERS)
            ACTION_OPEN_SNI -> openActivity(context, MainActivity.ACTION_SHORTCUT_SNI)
            ACTION_OPEN_APP -> openActivity(context, Intent.ACTION_MAIN)
            VpnWidgetUpdater.ACTION_REFRESH -> VpnWidgetUpdater.refreshAll(context)
        }
    }

    private fun handleToggle(context: Context) {
        val active = vpnController.connectionState.value.status != ConnectionStatus.DISCONNECTED
        if (active) {
            vpnController.disconnect()
            VpnWidgetUpdater.refreshAll(context)
            return
        }
        openActivity(context, MainActivity.ACTION_SHORTCUT_CONNECT)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.widget_vpn_consent_needed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openActivity(context: Context, action: String) {
        val launch = Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action == Intent.ACTION_MAIN) {
            launch.addCategory(Intent.CATEGORY_LAUNCHER)
        }
        context.startActivity(launch)
    }

    companion object {
        const val ACTION_TOGGLE = "com.v2rayez.app.widget.TOGGLE"
        const val ACTION_OPEN_SERVERS = "com.v2rayez.app.widget.OPEN_SERVERS"
        const val ACTION_OPEN_SNI = "com.v2rayez.app.widget.OPEN_SNI"
        const val ACTION_OPEN_APP = "com.v2rayez.app.widget.OPEN_APP"
    }
}
