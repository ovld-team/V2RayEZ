package com.v2rayez.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.v2rayez.app.data.tor.TorController
import com.v2rayez.app.data.tor.TorState
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
class V2RayApplication : Application() {

    @Inject lateinit var logRepository: LogRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var torController: TorController
    @Inject lateinit var iranGeoAutoConfigurator: com.v2rayez.app.data.core.IranGeoAutoConfigurator

    @Inject lateinit var firebaseTelemetry: com.v2rayez.app.data.analytics.FirebaseTelemetry
    @Inject lateinit var remoteTelemetry: com.v2rayez.app.data.analytics.RemoteTelemetry

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        val tag = LocaleHelper.savedTag(base)
        super.attachBaseContext(LocaleHelper.wrap(base, tag))
    }

    override fun onCreate() {
        // Hilt injects fields inside super.onCreate() — must call first, then arm telemetry
        // so the rest of startup (and fatals) are covered.
        super.onCreate()
        remoteTelemetry.init()
        firebaseTelemetry.enableCrashReporting()
        installCrashLogger()
        restoreTor()
        appScope.launch { iranGeoAutoConfigurator.applyIfNeeded() }
        appScope.launch {
            runCatching {
                settingsRepository.settings().collect { firebaseTelemetry.applyConsent(it) }
            }
        }
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
                remoteTelemetry.captureFatal(throwable)
                firebaseTelemetry.recordFatal(throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
