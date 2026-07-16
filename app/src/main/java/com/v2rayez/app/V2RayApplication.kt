package com.v2rayez.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.v2rayez.app.data.analytics.sanitizedForCrashlytics
import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.data.tor.TorState
import com.v2rayez.app.domain.model.AppSettings
import com.v2rayez.app.data.work.SubscriptionRefreshWorker
import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.ui.LocaleHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltAndroidApp
class V2RayApplication : Application(), Configuration.Provider {

    @Inject lateinit var logRepository: LogRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var torController: TorController
    @Inject lateinit var iranGeoAutoConfigurator: com.v2rayez.app.data.core.IranGeoAutoConfigurator
    @Inject lateinit var packInstallCoordinator: com.v2rayez.app.data.core.PackInstallCoordinator

    @Inject lateinit var firebaseTelemetry: com.v2rayez.app.data.analytics.FirebaseTelemetry
    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lets WorkManager resolve [SubscriptionRefreshWorker]'s `@AssistedInject` deps via Hilt. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    override fun attachBaseContext(base: Context) {
        val tag = LocaleHelper.savedTag(base)
        super.attachBaseContext(LocaleHelper.wrap(base, tag))
    }

    override fun onCreate() {
        // Hilt injects fields inside super.onCreate() — must call first, then arm telemetry
        // so the rest of startup (and fatals) are covered.
        super.onCreate()
        firebaseTelemetry.enableTelemetry()
        installCrashLogger()
        restoreTor()
        runCatching { SubscriptionRefreshWorker.schedule(this) }
            .onFailure { Log.w("V2RayApplication", "WorkManager schedule failed", it) }
        appScope.launch { iranGeoAutoConfigurator.applyIfNeeded() }
        packInstallCoordinator.start()
        appScope.launch {
            runCatching {
                var previous: AppSettings? = null
                settingsRepository.settings().collect { settings ->
                    firebaseTelemetry.applyConsent(settings)
                    previous?.let { old -> logFeatureToggleChanges(old, settings) }
                    previous = settings
                }
            }
        }
    }

    private fun logFeatureToggleChanges(old: AppSettings, new: AppSettings) {
        fun changed(name: String, before: Boolean, after: Boolean) {
            if (before != after) firebaseTelemetry.logFeatureToggle(name, after)
        }
        changed("analytics", old.analyticsConsent, new.analyticsConsent)
        changed("notifications", old.notifications, new.notifications)
        changed("auto_connect", old.autoConnect, new.autoConnect)
        changed("boot_auto_connect", old.bootAutoConnect, new.bootAutoConnect)
        changed("battery_saver", old.batterySaver, new.batterySaver)
        changed("always_on", old.vpnAlwaysOn, new.vpnAlwaysOn)
        changed("lockdown", old.blockWithoutVpn, new.blockWithoutVpn)
        changed("full_device_tunnel", old.fullDeviceTunnel, new.fullDeviceTunnel)
        changed("allow_lan", old.allowLan, new.allowLan)
        changed("ipv6", old.enableIpv6, new.enableIpv6)
        changed("local_dns", old.enableLocalDns, new.enableLocalDns)
        changed("sniffing", old.enableSniffing, new.enableSniffing)
        changed("mux", old.enableMux, new.enableMux)
        changed("lan_sharing", old.enableLanSharing, new.enableLanSharing)
        changed("reduce_data", old.reduceData, new.reduceData)
        changed("tor", old.tor.enabled, new.tor.enabled)
        changed("tor_auto_rotate", old.tor.autoRotateBridges, new.tor.autoRotateBridges)
        changed("domain_front", old.domainFront.enabled, new.domainFront.enabled)
        changed("allow_insecure", old.tls.allowInsecure, new.tls.allowInsecure)
        changed("fragment", old.fragment.enabled, new.fragment.enabled)
        changed("warp", old.warp.enabled, new.warp.enabled)
        changed("fake_dns", old.dns.enableFakeDns, new.dns.enableFakeDns)
        changed("app_proxy", old.appProxy.enabled, new.appProxy.enabled)
        changed("app_proxy_bypass", old.appProxy.bypassMode, new.appProxy.bypassMode)
        changed("bypass_lan", old.routing.bypassLan, new.routing.bypassLan)
        changed("bypass_mainland", old.routing.bypassMainland, new.routing.bypassMainland)
        changed("bypass_iran", old.routing.bypassIran, new.routing.bypassIran)
        changed("block_ads", old.routing.blockAds, new.routing.blockAds)
    }

    /**
     * If the user left Tor enabled, restore it after a short delay so Application.onCreate
     * never blocks/crashes on native Tor startup (16 KB page devices, slow storage).
     */
    private fun restoreTor() {
        appScope.launch {
            runCatching {
                kotlinx.coroutines.delay(1_500)
                val tor = settingsRepository.current().tor
                if (tor.enabled && torController.status.value.state == TorState.OFF) {
                    torController.start(tor)
                }
            }.onFailure { Log.w("V2RayApplication", "Tor restore skipped", it) }
        }
    }

    /**
     * Best-effort last-gasp logger: records the fatal exception to the in-app log stream before
     * delegating to the platform default handler, so crashes are visible in the Logs screen and
     * exported reports instead of vanishing.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                logRepository.append(
                    LogEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = ts,
                        level = LogLevel.ERROR,
                        message = "Fatal: ${throwable.message ?: throwable.javaClass.simpleName}",
                        detail = throwable.stackTraceToString().take(2000)
                    )
                )
                Log.e("V2RayApplication", "Uncaught exception on ${thread.name}", throwable)
                firebaseTelemetry.recordFatal(throwable)
            }
            // Crashlytics' default handler is usually `previous` — never hand it a raw
            // throwable (hosts/URIs in messages would bypass our scrub boundary).
            previous?.uncaughtException(thread, sanitizedForCrashlytics(throwable))
        }
    }
}
