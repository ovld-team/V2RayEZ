package com.v2rayez.app.ui.screens.servers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.CountryFlag
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.ErrorRed
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.Warning
import com.v2rayez.app.ui.viewmodel.FreeServersViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeServersScreen(
    onBack: () -> Unit,
    viewModel: FreeServersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            V2BackTopBar(title = stringResource(R.string.free_title), onBack = onBack)

            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.free_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VSpacer(12)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.servers.isEmpty() && state.testedCount == 0) {
                                stringResource(R.string.free_none_loaded)
                            } else {
                                stringResource(R.string.free_available, state.servers.size)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.testedCount > 0) {
                            Text(
                                stringResource(R.string.free_working_tested, state.workingCount, state.testedCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = Connected
                            )
                        }
                    }
                }
                VSpacer(8)
                // Action chips: quick scan (sample) / add fastest / add all / sort / filter.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val progress = state.testProgress
                    V2FilterChip(
                        label = if (progress != null) {
                            stringResource(
                                R.string.free_scanning_progress,
                                progress.completed,
                                progress.total
                            )
                        } else {
                            stringResource(R.string.free_quick_scan)
                        },
                        selected = progress != null
                    ) {
                        if (progress != null) viewModel.cancelTests() else viewModel.quickScanSample()
                    }
                    V2FilterChip(
                        label = stringResource(R.string.free_test_all),
                        selected = false,
                        enabled = progress == null && state.servers.isNotEmpty()
                    ) {
                        viewModel.testAll()
                    }
                    V2FilterChip(stringResource(R.string.free_add_fastest), false) {
                        viewModel.addFastest(onResult = notify)
                    }
                    V2FilterChip(stringResource(R.string.free_add_all), false) {
                        viewModel.addAll(onResult = notify)
                    }
                    V2FilterChip(stringResource(R.string.free_sort_ping), state.sortByPing) {
                        viewModel.toggleSortByPing()
                    }
                    V2FilterChip(stringResource(R.string.free_working_only), state.workingOnly) {
                        viewModel.toggleWorkingOnly()
                    }
                }
                state.testProgress?.let { progress ->
                    VSpacer(8)
                    // Explicit stop affordance for the in-progress bulk scan (the chip toggle
                    // alone was too hidden — users reported "no stop button").
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = {
                                if (progress.total > 0) {
                                    progress.completed.toFloat() / progress.total
                                } else {
                                    0f
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        HSpacer(10)
                        RoundIconButton(
                            icon = Icons.Filled.Stop,
                            tint = ErrorRed,
                            contentDescription = stringResource(R.string.free_stop_scan),
                            enabled = true,
                            onClick = viewModel::cancelTests
                        )
                    }
                }
                state.probeError?.let { message ->
                    VSpacer(6)
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = ErrorRed
                    )
                }
                VSpacer(8)
            }

            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = viewModel::load,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.servers.isEmpty() && !state.loading) {
                        item {
                            Text(
                                state.error ?: stringResource(R.string.free_pull_to_load),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                    items(state.servers, key = { it.id }) { server ->
                        FreeServerRow(
                            server = server,
                            isAdded = server.id in state.added,
                            fromSubscription = server.id in state.addedFromSubscription,
                            isTesting = server.id in state.testing,
                            pingMs = state.pings[server.id],
                            onTest = { viewModel.test(server) },
                            onAdd = { viewModel.add(server, notify) }
                        )
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun FreeServerRow(
    server: Server,
    isAdded: Boolean,
    fromSubscription: Boolean,
    isTesting: Boolean,
    pingMs: Int?,
    onTest: () -> Unit,
    onAdd: () -> Unit
) {
    // Subtle breathing alpha while a probe is in flight — a second, ambient cue for
    // "this row is busy" beyond the small spinner, so users glancing at the list still
    // notice progress rather than mistaking it for a frozen screen.
    val rowAlpha by if (isTesting) {
        val transition = rememberInfiniteTransition(label = "freeServerTestingPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.72f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "freeServerTestingPulseAlpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    CardSurface(
        Modifier.fillMaxWidth().graphicsLayer(alpha = rowAlpha),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 64.dp)
                .clickable(enabled = !isAdded, onClick = onAdd)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CountryFlag(server.countryCode)
            HSpacer(12)
            Column(Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                VSpacer(2)
                // "Already added via a subscription" is a state, not an error — label it.
                Text(
                    if (isAdded && fromSubscription) {
                        stringResource(R.string.free_in_subscription)
                    } else {
                        "${server.protocol.label} · ${server.security.ifBlank { "none" }}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HSpacer(8)
            PingBadge(pingMs = pingMs, testing = isTesting)
            HSpacer(8)
            // Test-before-add: probe latency without saving the server.
            RoundIconButton(
                icon = if (isTesting) null else Icons.Filled.NetworkPing,
                tint = Warning,
                contentDescription = stringResource(R.string.free_test),
                enabled = !isTesting,
                onClick = onTest
            )
            HSpacer(6)
            val addTint = if (isAdded) Connected else MaterialTheme.colorScheme.primary
            RoundIconButton(
                icon = if (isAdded) Icons.Filled.Check else Icons.Filled.Add,
                tint = addTint,
                contentDescription = stringResource(if (isAdded) R.string.free_added else R.string.free_add_server),
                enabled = !isAdded,
                onClick = onAdd
            )
        }
    }
}

/** Ping result badge: green when reachable, red when tested-dead, hidden when untested. */
@Composable
private fun PingBadge(pingMs: Int?, testing: Boolean) {
    when {
        // Sized up from 16.dp and given a visible track ring so the sweeping arc reads
        // clearly as motion instead of shrinking to a barely-there dot at small scale.
        testing -> CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        )
        pingMs == null -> Unit
        pingMs > 0 -> BadgeText("$pingMs ms", Connected)
        else -> BadgeText("✕", ErrorRed)
    }
}

@Composable
private fun BadgeText(text: String, tint: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    tint: androidx.compose.ui.graphics.Color,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // Busy state (icon == null) previously drew a `tint`-colored spinner over a
    // `tint`-at-16%-alpha backdrop — same hue on same hue reads as a flat, static
    // grey-ish blob at small sizes instead of an obvious animated spinner. Swap the
    // backdrop to a neutral surface while busy so the accent-colored arc stands out.
    val isBusy = icon == null
    val backdrop = if (isBusy) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    } else {
        tint.copy(alpha = 0.16f)
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(backdrop)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp).semantics { this.contentDescription = contentDescription },
                strokeWidth = 2.5.dp,
                color = tint,
                trackColor = tint.copy(alpha = 0.3f)
            )
        }
    }
}

@Preview
@Composable
private fun FreeServersScreenPreview() {
    V2RayEzTheme { FreeServersScreen(onBack = {}, viewModel = FreeServersViewModel()) }
}
