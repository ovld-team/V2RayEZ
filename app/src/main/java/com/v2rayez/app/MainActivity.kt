package com.v2rayez.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.domain.repository.VpnController
import com.v2rayez.app.ui.LocalVpnPermission
import com.v2rayez.app.ui.LocaleHelper
import com.v2rayez.app.ui.components.StartupPromoDialog
import com.v2rayez.app.ui.V2RayApp
import com.v2rayez.app.ui.VpnPermissionRequester
import com.v2rayez.app.ui.navigation.Routes
import com.v2rayez.app.ui.screens.onboarding.WelcomeWizardScreen
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var vpnController: VpnController

    private var pendingVpnAction: (() -> Unit)? = null

    /** Route requested by an app shortcut / deep link, consumed once by the UI. */
    private val initialRoute: MutableState<String?> = mutableStateOf(null)

    companion object {
        const val ACTION_SHORTCUT_CONNECT = "com.v2rayez.app.action.SHORTCUT_CONNECT"
        const val ACTION_SHORTCUT_SERVERS = "com.v2rayez.app.action.SHORTCUT_SERVERS"
        const val ACTION_SHORTCUT_STATS = "com.v2rayez.app.action.SHORTCUT_STATS"
        const val ACTION_SHORTCUT_SNI = "com.v2rayez.app.action.SHORTCUT_SNI"
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingVpnAction?.invoke()
            }
            pendingVpnAction = null
        }

    private val vpnPermission = VpnPermissionRequester { onGranted ->
        val prepareIntent: Intent? = try {
            // Must use the Activity context. Never call prepare from a Receiver/Tile —
            // that causes AppOps "package under uid but it is not" on many devices.
            VpnService.prepare(this)
        } catch (se: SecurityException) {
            Log.e("MainActivity", "VpnService.prepare AppOps failure", se)
            Toast.makeText(
                this,
                getString(R.string.vpn_error_appops),
                Toast.LENGTH_LONG
            ).show()
            pendingVpnAction = null
            return@VpnPermissionRequester
        }
        if (prepareIntent == null) {
            onGranted()
        } else {
            pendingVpnAction = onGranted
            vpnPermissionLauncher.launch(prepareIntent)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase, LocaleHelper.savedTag(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleShortcutIntent(intent)
        setContent {
            AppRoot(
                vpnPermission = vpnPermission,
                initialRoute = initialRoute,
                onLocaleChanged = { recreate() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    /** Map an app-shortcut / deep-link action to either a VPN toggle or a target route. */
    private fun handleShortcutIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SHORTCUT_CONNECT -> vpnPermission.request { vpnController.toggle() }
            ACTION_SHORTCUT_SERVERS -> initialRoute.value = Routes.SERVERS
            ACTION_SHORTCUT_STATS -> initialRoute.value = Routes.STATISTICS
            ACTION_SHORTCUT_SNI -> initialRoute.value = Routes.SNI_TUNNEL
        }
    }
}

/**
 * Root composable: reflects the persisted appearance settings (theme + accent)
 * live, and recreates the activity when the language changes so the new locale's
 * resources take effect.
 */
@Composable
private fun AppRoot(
    vpnPermission: VpnPermissionRequester,
    initialRoute: MutableState<String?>,
    onLocaleChanged: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by settingsViewModel.state.collectAsState()
    val hydrated by settingsViewModel.hydrated.collectAsState()
    val context = LocalContext.current

    // Dismissed for this session only (reappears next launch unless "do not show again").
    val promoShownThisSession = remember { mutableStateOf(false) }

    val darkTheme = when (settings.theme) {
        "Dark" -> true
        "Light" -> false
        else -> isSystemInDarkTheme()
    }

    LaunchedEffect(settings.language) {
        val tag = LocaleHelper.tagForLanguage(settings.language)
        if (tag != LocaleHelper.savedTag(context)) {
            LocaleHelper.persistTag(context, tag)
            onLocaleChanged()
        }
    }

    V2RayEzTheme(darkTheme = darkTheme, accent = settings.accentColor) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (!hydrated) {
                // Wait for DataStore so returning users do not flash the wizard.
            } else if (!settings.onboardingComplete) {
                WelcomeWizardScreen()
            } else {
                CompositionLocalProvider(LocalVpnPermission provides vpnPermission) {
                    V2RayApp(
                        initialRoute = initialRoute.value,
                        onInitialRouteConsumed = { initialRoute.value = null }
                    )
                    if (!settings.promoDismissed && !promoShownThisSession.value) {
                        StartupPromoDialog(
                            onDismiss = { dontShowAgain ->
                                if (dontShowAgain) settingsViewModel.dismissPromo()
                                promoShownThisSession.value = true
                            }
                        )
                    }
                }
            }
        }
    }
}
