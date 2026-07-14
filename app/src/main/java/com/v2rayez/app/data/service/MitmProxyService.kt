package com.v2rayez.app.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.v2rayez.app.MainActivity
import com.v2rayez.app.R
import com.v2rayez.app.data.analytics.FailureCategory
import com.v2rayez.app.data.analytics.RemoteTelemetry
import com.v2rayez.app.data.cert.MitmCaStore
import com.v2rayez.app.data.core.GeoAssetManager
import com.v2rayez.app.data.core.V2RayCore
import com.v2rayez.app.data.mitm.MitmConfigBuilder
import com.v2rayez.app.domain.model.ProxyCoreType
import com.v2rayez.app.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

/**
 * Foreground service that runs the MITM Domain Fronting Xray config as a local SOCKS/HTTP
 * proxy only (no VpnService / TUN). See [MitmProxyController].
 */
@AndroidEntryPoint
class MitmProxyService : Service() {

    @Inject lateinit var core: V2RayCore
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var stateHolder: MitmProxyStateHolder
    @Inject lateinit var geoAssets: GeoAssetManager
    @Inject lateinit var remoteTelemetry: RemoteTelemetry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { stopProxy() }
                return START_NOT_STICKY
            }
            else -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        getString(R.string.mitm_proxy_notif_title),
                        getString(R.string.mitm_proxy_notif_starting)
                    )
                )
                startJob?.cancel()
                startJob = scope.launch { startProxy() }
            }
        }
        return START_STICKY
    }

    private suspend fun startProxy() {
        try {
            val settings = settingsRepository.current()
            val mitm = settings.mitm
            if (!MitmCaStore.isPresent(this) || !mitm.caInstallAcknowledged) {
                fail(getString(R.string.vpn_error_mitm_ca))
                return
            }
            if (settings.defaultCore != ProxyCoreType.XRAY) {
                fail(getString(R.string.vpn_error_mitm_xray))
                return
            }
            // Refuse if VPN (or any other) Xray core already owns the exclusive lock.
            if (core.isRunning) {
                fail(getString(R.string.mitm_proxy_error_core_busy))
                return
            }

            val certPath = MitmCaStore.crtFile(this).absolutePath
            val keyPath = MitmCaStore.keyFile(this).absolutePath
            val config = MitmConfigBuilder.build(
                mitm,
                certPath,
                keyPath,
                includeTun = false,
                geositeAvailable = geoAssets.geositeAvailable()
            )
            core.onStatus = { _, msg ->
                if (msg.isNotBlank()) Log.d(TAG, msg)
            }
            val started = core.startProxyLoop(config)
            if (!started || !core.isRunning) {
                fail(getString(R.string.vpn_error_core))
                return
            }
            // Exit gate: SOCKS + HTTP must accept local connections.
            val socksOk = portAccepts(mitm.proxyPort)
            val httpOk = portAccepts(mitm.httpPort)
            if (!socksOk || !httpOk) {
                Log.e(TAG, "proxy ports not listening socks=$socksOk http=$httpOk")
                core.stopLoop()
                fail(getString(R.string.mitm_proxy_error_ports))
                return
            }
            stateHolder.setRunning(true)
            stateHolder.setError(null)
            updateNotification(
                getString(R.string.mitm_proxy_notif_title),
                getString(R.string.mitm_proxy_notif_running, mitm.proxyPort, mitm.httpPort)
            )
            Log.i(TAG, "MITM proxy listening socks=${mitm.proxyPort} http=${mitm.httpPort}")
        } catch (t: Throwable) {
            Log.e(TAG, "startProxy failed", t)
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun stopProxy() {
        startJob?.cancel()
        runCatching { core.stopLoop() }
        stateHolder.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun fail(message: String) {
        runCatching { remoteTelemetry.captureVpnFailure(FailureCategory.MITM, message) }
        stateHolder.setError(message)
        stateHolder.setRunning(false)
        runCatching { core.stopLoop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun portAccepts(port: Int, timeoutMs: Int = 1_500): Boolean {
        if (port !in 1..65535) return false
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    private fun buildNotification(title: String, text: String): Notification {
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MitmProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.mitm_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(title, text))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.mitm_proxy_channel),
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (stateHolder.running.value) {
            runCatching { core.stopLoopBlocking() }
            stateHolder.setRunning(false)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MitmProxyService"
        const val ACTION_START = "com.v2rayez.app.mitm.START"
        const val ACTION_STOP = "com.v2rayez.app.mitm.STOP"
        private const val CHANNEL_ID = "mitm_proxy"
        private const val NOTIFICATION_ID = 42
    }
}
