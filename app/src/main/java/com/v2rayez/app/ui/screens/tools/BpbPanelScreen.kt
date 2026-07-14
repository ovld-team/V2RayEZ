package com.v2rayez.app.ui.screens.tools

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.components.V2Switch
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.ErrorRed
import com.v2rayez.app.ui.viewmodel.ToolsViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BPB Panel / subscription manager. Manages subscription URLs (import/refresh/delete)
 * and embeds a WebView to open a panel dashboard for account/config management.
 */
@Composable
fun BpbPanelScreen(onBack: () -> Unit, viewModel: ToolsViewModel = hiltViewModel()) {
    val subs by viewModel.subscriptions.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var panelUrl by remember { mutableStateOf("") }
    var loadedPanel by remember { mutableStateOf<String?>(null) }

    val invalidUrlMessage = stringResource(R.string.bpb_invalid_url)
    val loadFailedMessage = stringResource(R.string.bpb_load_failed)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            V2BackTopBar(title = stringResource(R.string.servers_subscriptions), onBack = onBack)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                SectionHeader(title = stringResource(R.string.servers_subscriptions))
                if (subs.isEmpty()) {
                    Text(stringResource(R.string.bpb_no_subscriptions), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                subs.forEach { sub ->
                    CardSurface(Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sub.name, color = MaterialTheme.colorScheme.onSurface)
                                val lastUpdated = if (sub.lastUpdated <= 0) stringResource(R.string.bpb_never) else formatTime(sub.lastUpdated)
                                Text(
                                    stringResource(R.string.bpb_sub_summary, sub.serverCount, lastUpdated),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            V2Switch(sub.enabled, { viewModel.setSubscriptionEnabled(sub.id, it) })
                            IconButton(onClick = {
                                viewModel.refreshSubscription(sub.id) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                            }) { Icon(Icons.Filled.Refresh, stringResource(R.string.common_refresh), tint = MaterialTheme.colorScheme.primary) }
                            IconButton(onClick = { viewModel.deleteSubscription(sub.id) }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = ErrorRed)
                            }
                        }
                    }
                }
                VSpacer(12)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.routing_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                VSpacer(4)
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.bpb_subscription_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                VSpacer(8)
                PrimaryButton(stringResource(R.string.bpb_import_subscription), onClick = {
                    if (url.isNotBlank()) {
                        viewModel.addSubscription(name.ifBlank { url }, url) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                        name = ""; url = ""
                    }
                }, modifier = Modifier.fillMaxWidth())

                VSpacer(20)
                SectionHeader(title = stringResource(R.string.bpb_section_panel))
                OutlinedTextField(panelUrl, { panelUrl = it }, label = { Text(stringResource(R.string.bpb_panel_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                VSpacer(8)
                PrimaryButton(stringResource(R.string.bpb_open_panel), onClick = {
                    if (panelUrl.isNotBlank()) {
                        val normalized = if (panelUrl.startsWith("http", true)) panelUrl else "https://$panelUrl"
                        if (normalized.startsWith("http://", true) || normalized.startsWith("https://", true)) {
                            loadedPanel = normalized
                        } else {
                            scope.launch { snackbar.showSnackbar(invalidUrlMessage) }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth())
                VSpacer(12)
            }

            loadedPanel?.let { target ->
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(420.dp).padding(horizontal = 16.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?
                                ): Boolean = !isAllowedPanelScheme(request?.url?.scheme)

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?
                                ) {
                                    scope.launch { snackbar.showSnackbar(loadFailedMessage) }
                                }
                            }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                        }
                    },
                    update = { it.loadUrl(target) },
                    onRelease = { it.destroy() }
                )
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

private fun formatTime(epoch: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epoch))

/**
 * BPB panel navigation allowlist: only `http`/`https` are safe destinations for a dashboard
 * WebView (SEC-05). Blocks `file://`, `content://`, `intent://`, `javascript:`, and other
 * schemes a hostile or compromised panel URL could pivot into once loaded.
 */
internal fun isAllowedPanelScheme(scheme: String?): Boolean =
    scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
