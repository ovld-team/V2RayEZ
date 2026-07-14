package com.v2rayez.app.data.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.v2rayez.app.MainActivity
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick Settings tile to toggle the VPN. */
@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class V2RayTileService : TileService() {

    @Inject lateinit var vpnController: VpnController
    @Inject lateinit var stateHolder: VpnStateHolder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tileJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        tileJob?.cancel()
        tileJob = scope.launch {
            stateHolder.connectionState.collectLatest { updateTile() }
        }
    }

    override fun onStopListening() {
        tileJob?.cancel()
        tileJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        tileJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val active = stateHolder.connectionState.value.status != ConnectionStatus.DISCONNECTED
        // Never call VpnService.prepare() from a TileService — on many devices AppOps then
        // throws SecurityException ("package under uid X but it is not"). Always hand connect
        // off to MainActivity, which owns the consent flow.
        if (active) {
            vpnController.disconnect()
        } else {
            launchAppToConnect()
        }
        updateTile()
    }

    private fun launchAppToConnect() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHORTCUT_CONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            launchAppToConnectLegacy(intent)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchAppToConnectLegacy(intent: Intent) {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = stateHolder.connectionState.value
        val connected = state.status == ConnectionStatus.CONNECTED
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "V2RayEz"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = com.v2rayez.app.data.widget.VpnWidgetUpdater.tileSubtitle(
                this,
                state.server?.name
            )
        }
        tile.contentDescription = com.v2rayez.app.data.widget.VpnWidgetUpdater.tileLabel(
            this,
            state.status
        )
        tile.updateTile()
    }
}
