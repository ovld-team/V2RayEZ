package com.v2rayez.app.ui.screens.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.v2rayez.app.R
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.Warning
import com.v2rayez.app.ui.viewmodel.BrowserViewModel
import java.util.concurrent.Executor

private data class BrowserPreset(val labelRes: Int, val url: String)

private val BROWSER_PRESETS = listOf(
    BrowserPreset(R.string.browser_preset_google, "https://www.google.com"),
    BrowserPreset(R.string.browser_preset_youtube, "https://www.youtube.com"),
    BrowserPreset(R.string.browser_preset_x, "https://x.com"),
    BrowserPreset(R.string.browser_preset_cloudflare, "https://1.1.1.1")
)

private const val HOME_URL = "https://www.google.com"
private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

@Composable
fun BrowserScreen(
    onOpenMitmSetup: () -> Unit = {},
    viewModel: BrowserViewModel = hiltViewModel()
) {
    BrowserContent(onOpenMitmSetup = onOpenMitmSetup, viewModel = viewModel)
}

@Composable
internal fun BrowserContent(
    onOpenMitmSetup: () -> Unit,
    viewModel: BrowserViewModel
) {
    val context = LocalContext.current
    val ready by viewModel.ready.collectAsState()
    val mitmReady by viewModel.mitmReady.collectAsState()
    val proxyRunning by viewModel.proxyRunning.collectAsState()
    val httpPort by viewModel.httpPort.collectAsState()
    val proxyApiSupported = viewModel.proxyApiSupported

    var urlText by rememberSaveable { mutableStateOf(HOME_URL) }
    var pendingUrl by rememberSaveable { mutableStateOf(HOME_URL) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var menuOpen by remember { mutableStateOf(false) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }
    // True once ProxyController.setProxyOverride has fired its completion callback, meaning the
    // MITM HTTP proxy is actually wired for this WebView process. Media-heavy sites (YouTube)
    // must not navigate before this flips or the first CONNECTs bypass the MITM http-in.
    var proxyApplied by remember { mutableStateOf(false) }
    var proxyOverrideGeneration by remember { mutableStateOf(0) }

    fun reloadIfProxyReady() {
        if (shouldDeferInitialLoad(proxyApiSupported, proxyRunning, proxyApplied)) return
        webViewHolder.value?.reload()
    }

    fun navigate(target: String) {
        val normalized = normalizeBrowserInput(target)
        urlText = normalized
        pendingUrl = normalized
        // If the MITM proxy is expected but its override hasn't applied yet, defer the load —
        // the setProxyOverride callback below will pick up the pending URL once routing is live.
        if (shouldDeferInitialLoad(proxyApiSupported, proxyRunning, proxyApplied)) return
        webViewHolder.value?.loadUrl(normalized)
    }

    // Apply / clear WebView proxy override when MITM standalone proxy starts/stops.
    LaunchedEffect(proxyRunning, httpPort, proxyApiSupported) {
        if (!proxyApiSupported || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyApplied = false
            return@LaunchedEffect
        }
        val gen = ++proxyOverrideGeneration
        val mainExecutor = java.util.concurrent.Executor { r ->
            android.os.Handler(android.os.Looper.getMainLooper()).post(r)
        }
        runCatching {
            if (proxyRunning) {
                proxyApplied = false
                val config = ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:$httpPort")
                    .addDirect("<-loopback>")
                    .build()
                // Only after this callback is the override guaranteed live for the WebView
                // process. Load/reload the pending URL here so YouTube/media CONNECTs are
                // routed through the MITM http-in from the very first request.
                ProxyController.getInstance().setProxyOverride(config, mainExecutor) {
                    if (gen != proxyOverrideGeneration) return@setProxyOverride
                    proxyApplied = true
                    webViewHolder.value?.let { wv ->
                        if (wv.url.isNullOrBlank() || wv.url != pendingUrl) {
                            wv.loadUrl(pendingUrl)
                        } else {
                            wv.reload()
                        }
                    }
                }
            } else {
                ProxyController.getInstance().clearProxyOverride(mainExecutor) {
                    if (gen != proxyOverrideGeneration) return@clearProxyOverride
                    proxyApplied = false
                }
            }
        }.onFailure {
            // Don't leave the WebView blank forever if ProxyController rejects the override.
            proxyApplied = false
            if (proxyRunning) webViewHolder.value?.loadUrl(pendingUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                runCatching {
                    ProxyController.getInstance().clearProxyOverride({ r -> r.run() }) {}
                }
            }
            webViewHolder.value?.apply {
                stopLoading()
                destroy()
            }
            webViewHolder.value = null
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val wv = webViewHolder.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> wv.onPause()
                Lifecycle.Event.ON_RESUME -> wv.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(desktopMode) {
        webViewHolder.value?.settings?.userAgentString =
            if (desktopMode) DESKTOP_UA else WebSettings.getDefaultUserAgent(context)
        reloadIfProxyReady()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Chrome-like omnibox row (no V2TopBar / fat Go).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { webViewHolder.value?.takeIf { canGoBack }?.goBack() }, enabled = canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browser_back_cd))
            }
            IconButton(onClick = { webViewHolder.value?.takeIf { canGoForward }?.goForward() }, enabled = canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.browser_forward_cd))
            }
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.browser_url_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigate(urlText) }),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
            IconButton(onClick = { reloadIfProxyReady() }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.browser_reload_cd))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.browser_menu_cd))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_share)) },
                        onClick = {
                            menuOpen = false
                            val url = webViewHolder.value?.url ?: urlText
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, url)
                                    },
                                    null
                                )
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_copy)) },
                        onClick = {
                            menuOpen = false
                            val url = webViewHolder.value?.url ?: urlText
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("url", url))
                            Toast.makeText(context, context.getString(R.string.browser_copied), Toast.LENGTH_SHORT).show()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_desktop)) },
                        onClick = {
                            menuOpen = false
                            desktopMode = !desktopMode
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_mitm)) },
                        onClick = {
                            menuOpen = false
                            onOpenMitmSetup()
                        }
                    )
                }
            }
        }

        if (progress in 0.01f..0.99f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                // Locale-independent status marker for device-lab adb/Maestro flows (the
                // visible copy below is trilingual EN/FA/RU).
                .semantics {
                    contentDescription = "browser_status:" + when {
                        !proxyApiSupported -> "api_unsupported"
                        ready -> "active"
                        !mitmReady -> "banner"
                        else -> "inactive"
                    }
                }
        ) {
            when {
                !proxyApiSupported -> {
                    Text(
                        stringResource(R.string.browser_proxy_api_gate),
                        style = MaterialTheme.typography.labelSmall,
                        color = Warning,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Connected, modifier = Modifier.size(14.dp))
                        HSpacer(6)
                        Text(
                            stringResource(R.string.browser_active_status),
                            style = MaterialTheme.typography.labelSmall,
                            color = Connected,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                !mitmReady -> MitmBanner(onOpenMitmSetup)
                else -> {
                    Text(
                        stringResource(R.string.browser_proxy_inactive),
                        style = MaterialTheme.typography.labelSmall,
                        color = Warning
                    )
                }
            }
            VSpacer(6)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BROWSER_PRESETS.forEach { preset ->
                    V2FilterChip(
                        stringResource(preset.labelRes),
                        selected = false,
                        onClick = { navigate(preset.url) }
                    )
                }
            }
            VSpacer(6)
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    // Align with BpbPanelScreen hardening (SEC-06) — this Browser never needs
                    // local file:// or content:// access, only http(s) navigation.
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    // This Browser exists to verify MITM + user-CA playback (YouTube). YouTube's
                    // player calls video.play() programmatically after the watch page loads, so a
                    // gesture requirement leaves the player stuck on a spinner. Allow autoplay so
                    // the MITM media path (googlevideo segments) can be validated end-to-end.
                    settings.mediaPlaybackRequiresUserGesture = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean = !isAllowedWebViewScheme(request?.url?.scheme)

                        override fun onPageFinished(view: WebView?, url: String?) {
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                            if (!url.isNullOrBlank()) urlText = url
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress / 100f
                        }
                    }
                    webViewHolder.value = this
                    // Defer the first navigation while a MITM proxy override is still being
                    // applied; the setProxyOverride callback loads pendingUrl once routing is
                    // live. In every other case (no proxy / API<30 / override already applied)
                    // load immediately so the Browser isn't blank.
                    if (!shouldDeferInitialLoad(proxyApiSupported, proxyRunning, proxyApplied)) {
                        loadUrl(pendingUrl)
                    }
                }
            },
            update = { wv ->
                if (wv.url != pendingUrl && pendingUrl.isNotBlank() && wv.url != pendingUrl) {
                    // navigation handled via navigate(); avoid reload loops
                }
            }
        )
    }
}

@Composable
private fun MitmBanner(onOpenMitmSetup: () -> Unit) {
    CardSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                HSpacer(8)
                Text(
                    stringResource(R.string.browser_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            VSpacer(4)
            Text(
                stringResource(R.string.browser_banner_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenMitmSetup) {
                Text(stringResource(R.string.browser_banner_cta))
            }
        }
    }
}

/**
 * Whether the WebView's next navigation must wait for [ProxyController.setProxyOverride] to
 * finish before loading. Deferral only applies when a MITM proxy is running on a proxy-capable
 * API (30+) and the override callback hasn't confirmed yet — loading YouTube/media before that
 * point would issue CONNECTs that miss the MITM `http-in` and fail TLS/spin forever.
 *
 * Pure function so the sequencing rule is unit-testable without a WebView.
 */
internal fun shouldDeferInitialLoad(
    proxyApiSupported: Boolean,
    proxyRunning: Boolean,
    proxyApplied: Boolean
): Boolean = proxyApiSupported && proxyRunning && !proxyApplied

/**
 * Browser navigation allowlist: only `http`/`https` are safe destinations for this
 * general-purpose WebView (SEC-06). Blocks `file://`, `content://`, `intent://`,
 * `javascript:`, and other schemes a hostile/redirected page could pivot into once loaded.
 */
internal fun isAllowedWebViewScheme(scheme: String?): Boolean =
    scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

internal fun normalizeBrowserInput(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return HOME_URL
    if (trimmed.contains("://") || trimmed.startsWith("about:")) return trimmed
    if (trimmed.contains('.') && !trimmed.contains(' ')) {
        return "https://$trimmed"
    }
    return "https://www.google.com/search?q=${Uri.encode(trimmed)}"
}

@Preview(showBackground = true)
@Composable
private fun BrowserScreenPreview() {
    V2RayEzTheme {
        BrowserContent(onOpenMitmSetup = {}, viewModel = BrowserViewModel())
    }
}
