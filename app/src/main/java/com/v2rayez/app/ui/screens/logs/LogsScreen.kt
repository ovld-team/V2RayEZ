package com.v2rayez.app.ui.screens.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.LogRow
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.V2Switch
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.LogsViewModel

@Composable
fun LogsScreen(onBack: () -> Unit, viewModel: LogsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.filtered.size, state.autoScroll) {
        if (state.autoScroll && state.filtered.isNotEmpty()) {
            listState.animateScrollToItem(state.filtered.size - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        V2BackTopBar(
            title = stringResource(R.string.logs_title),
            onBack = onBack,
            actions = {
                TopIcon(
                    if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                    stringResource(R.string.action_search)
                ) { searchExpanded = !searchExpanded }
            }
        )

        if (searchExpanded) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.logs_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            V2FilterChip(stringResource(R.string.logs_all), state.levelFilter == null) { viewModel.setLevel(null) }
            LogLevel.entries.forEach { level ->
                V2FilterChip(level.label, state.levelFilter == level) { viewModel.setLevel(level) }
            }
        }

        if (state.filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                items(state.filtered, key = { it.id }) { entry ->
                    LogRow(entry)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), thickness = 1.dp)
                }
            }
        }

        LogsBottomBar(
            autoScroll = state.autoScroll,
            onAutoScrollChange = viewModel::setAutoScroll,
            onClear = viewModel::clear,
            onExport = {
                scope.launch {
                    val file = viewModel.export()
                    if (file != null) shareLogFile(context, file)
                }
            }
        )
    }
}

private fun shareLogFile(context: android.content.Context, file: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, context.getString(R.string.logs_export_chooser))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
private fun LogsBottomBar(
    autoScroll: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogsChipButton(Icons.Filled.FileDownload, stringResource(R.string.logs_export), onExport)
        HSpacer(10)
        LogsChipButton(Icons.Filled.DeleteOutline, stringResource(R.string.logs_clear), onClear)
        Box(Modifier.weight(1f))
        Text(stringResource(R.string.logs_auto_scroll), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HSpacer(8)
        V2Switch(checked = autoScroll, onCheckedChange = onAutoScrollChange)
    }
}

@Composable
private fun LogsChipButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TopIcon(icon: ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(9.dp)
    ) {
        Icon(icon, contentDescription = cd, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview
@Composable
private fun LogsScreenPreview() {
    V2RayEzTheme { LogsScreen(onBack = {}, viewModel = LogsViewModel()) }
}
