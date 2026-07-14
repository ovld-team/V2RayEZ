package com.v2rayez.app.data.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.v2rayez.app.MainActivity
import com.v2rayez.app.R
import com.v2rayez.app.data.service.VpnStateHolder
import com.v2rayez.app.domain.model.ConnectionStatus
import com.v2rayez.app.domain.repository.VpnController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds and pushes RemoteViews for both home-screen widgets. Uses classic layouts only
 * for broad OEM launcher compatibility (Samsung / Xiaomi / Realme / LG / AOSP).
 */
object VpnWidgetUpdater {

    const val ACTION_REFRESH = "com.v2rayez.app.widget.REFRESH"

    private val lastTrafficRefreshMs = AtomicLong(0L)
    private const val TRAFFIC_THROTTLE_MS = 1000L

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun vpnStateHolder(): VpnStateHolder
        fun vpnController(): VpnController
    }

    fun entry(context: Context): Entry =
        EntryPointAccessors.fromApplication(context.applicationContext, Entry::class.java)

    /** Full refresh of every installed Quick Connect + Control Panel instance. */
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val quickIds = mgr.getAppWidgetIds(ComponentName(app, QuickConnectWidgetProvider::class.java))
        val panelIds = mgr.getAppWidgetIds(ComponentName(app, ControlPanelWidgetProvider::class.java))
        if (quickIds.isEmpty() && panelIds.isEmpty()) return
        val state = runCatching { entry(app).vpnStateHolder().connectionState.value }
            .getOrDefault(VpnStateHolder.DISCONNECTED)
        quickIds.forEach { id -> mgr.updateAppWidget(id, buildQuickConnect(app, state.status)) }
        panelIds.forEach { id -> mgr.updateAppWidget(id, buildControlPanel(app, state)) }
    }

    /**
     * Throttled refresh intended for ~1 Hz traffic ticks so MIUI/HyperOS aren't flooded
     * with binder updates. Connection transitions always refresh immediately via [refreshAll].
     */
    fun refreshAllThrottled(context: Context) {
        val now = System.currentTimeMillis()
        val prev = lastTrafficRefreshMs.get()
        if (now - prev < TRAFFIC_THROTTLE_MS) return
        if (!lastTrafficRefreshMs.compareAndSet(prev, now)) return
        refreshAll(context)
    }

    fun buildQuickConnect(context: Context, status: ConnectionStatus = currentStatus(context)): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_connect)
        val connected = status == ConnectionStatus.CONNECTED
        val connecting = status == ConnectionStatus.CONNECTING
        views.setImageViewResource(R.id.widget_quick_toggle, R.drawable.ic_logo_v_widget)
        views.setInt(
            R.id.widget_quick_toggle,
            "setBackgroundResource",
            if (connected) R.drawable.widget_btn_connected else R.drawable.widget_btn_connect
        )
        val label = when {
            connected -> context.getString(R.string.widget_disconnect)
            connecting -> context.getString(R.string.widget_connecting)
            else -> context.getString(R.string.widget_connect)
        }
        views.setTextViewText(R.id.widget_quick_label, label)
        views.setOnClickPendingIntent(
            R.id.widget_quick_toggle,
            actionPendingIntent(context, VpnWidgetActionReceiver.ACTION_TOGGLE, requestCode = 1001)
        )
        views.setOnClickPendingIntent(
            R.id.widget_quick_root,
            actionPendingIntent(context, VpnWidgetActionReceiver.ACTION_TOGGLE, requestCode = 1002)
        )
        return views
    }

    fun buildControlPanel(
        context: Context,
        state: com.v2rayez.app.domain.model.ConnectionState = currentState(context)
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_control_panel)
        val connected = state.status == ConnectionStatus.CONNECTED
        val connecting = state.status == ConnectionStatus.CONNECTING
        views.setTextViewText(
            R.id.widget_panel_status,
            when {
                connected -> context.getString(R.string.widget_vpn_on)
                connecting -> context.getString(R.string.widget_connecting)
                else -> context.getString(R.string.widget_vpn_off)
            }
        )
        views.setTextColor(
            R.id.widget_panel_status,
            if (connected) 0xFF22C55E.toInt() else 0xFFFFFFFF.toInt()
        )
        val server = state.server
        val serverLine = when {
            server == null -> context.getString(R.string.widget_no_server)
            else -> {
                val proto = server.protocol.name
                val country = server.countryCode.takeIf { it.isNotBlank() }
                listOfNotNull(server.name, proto, country).joinToString(" · ")
            }
        }
        views.setTextViewText(R.id.widget_panel_server, serverLine)
        val ping = if (state.pingMs >= 0) {
            String.format(Locale.US, "%d ms", state.pingMs)
        } else {
            "—"
        }
        val uptime = if (connected && state.uptimeSeconds > 0) {
            formatUptime(state.uptimeSeconds)
        } else null
        val metrics = buildString {
            append(ping)
            append(" · ↓")
            append(state.downloadLabel.ifBlank { "0" })
            append(" · ↑")
            append(state.uploadLabel.ifBlank { "0" })
            if (uptime != null) {
                append(" · ")
                append(uptime)
            }
        }
        views.setTextViewText(R.id.widget_panel_metrics, metrics)
        val err = state.errorMessage?.trim().orEmpty()
        if (!connected && !connecting && err.isNotEmpty()) {
            views.setViewVisibility(R.id.widget_panel_error, View.VISIBLE)
            views.setTextViewText(R.id.widget_panel_error, err)
        } else {
            views.setViewVisibility(R.id.widget_panel_error, View.GONE)
        }
        views.setInt(
            R.id.widget_panel_toggle,
            "setBackgroundResource",
            if (connected) R.drawable.widget_btn_connected else R.drawable.widget_btn_connect
        )
        views.setImageViewResource(R.id.widget_panel_toggle, R.drawable.ic_logo_v_widget)
        views.setOnClickPendingIntent(
            R.id.widget_panel_toggle,
            actionPendingIntent(context, VpnWidgetActionReceiver.ACTION_TOGGLE, requestCode = 2001)
        )
        views.setOnClickPendingIntent(
            R.id.widget_panel_servers,
            actionPendingIntent(context, VpnWidgetActionReceiver.ACTION_OPEN_SERVERS, requestCode = 2002)
        )
        views.setOnClickPendingIntent(
            R.id.widget_panel_open,
            actionPendingIntent(context, VpnWidgetActionReceiver.ACTION_OPEN_APP, requestCode = 2004)
        )
        views.setViewVisibility(R.id.widget_panel_servers, View.VISIBLE)
        return views
    }

    private fun formatUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%dh%02dm", h, m)
        else String.format(Locale.US, "%dm%02ds", m, s)
    }

    fun tileLabel(context: Context, status: ConnectionStatus): String =
        when (status) {
            ConnectionStatus.CONNECTED -> context.getString(R.string.widget_vpn_on)
            ConnectionStatus.CONNECTING -> context.getString(R.string.widget_connecting)
            ConnectionStatus.DISCONNECTED -> context.getString(R.string.widget_vpn_off)
        }

    fun tileSubtitle(context: Context, serverName: String?): String =
        serverName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.widget_no_server)

    private fun currentStatus(context: Context): ConnectionStatus =
        runCatching { entry(context).vpnStateHolder().connectionState.value.status }
            .getOrDefault(ConnectionStatus.DISCONNECTED)

    private fun currentState(context: Context) =
        runCatching { entry(context).vpnStateHolder().connectionState.value }
            .getOrDefault(VpnStateHolder.DISCONNECTED)

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, VpnWidgetActionReceiver::class.java)
            .setAction(action)
            .setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun openAppPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
