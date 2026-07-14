package com.v2rayez.app.ui.screens.servers

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.ServerListItem
import com.v2rayez.app.ui.components.V2TopBar
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.ErrorRed
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.viewmodel.ServerSection
import com.v2rayez.app.ui.viewmodel.ServerSectionType
import com.v2rayez.app.ui.viewmodel.ServerSortMode
import com.v2rayez.app.ui.viewmodel.ServersViewModel

// ---------------------------------------------------------------------------
// Collapsed-section persistence (survives app restarts).
// ---------------------------------------------------------------------------
private const val UI_PREFS = "servers_ui"
private const val KEY_COLLAPSED = "collapsed_sections"

private fun loadCollapsed(context: Context): Set<String> =
    context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_COLLAPSED, emptySet()).orEmpty()

private fun saveCollapsed(context: Context, collapsed: Set<String>) {
    context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(KEY_COLLAPSED, collapsed).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    onAddManual: () -> Unit = {},
    onEditServer: (String) -> Unit = {},
    onBrowseFreeServers: () -> Unit = {},
    onConnected: () -> Unit = {},
    viewModel: ServersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val lastPingMessage by viewModel.lastPingMessage.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    var connectTarget by remember { mutableStateOf<Server?>(null) }
    var menuTarget by remember { mutableStateOf<Server?>(null) }
    var qrTarget by remember { mutableStateOf<Server?>(null) }
    var moveTarget by remember { mutableStateOf<Server?>(null) }
    var showAddImport by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var manageSubscriptionId by remember { mutableStateOf<String?>(null) }
    var manageGroupName by remember { mutableStateOf<String?>(null) }
    var showMoveSelected by remember { mutableStateOf(false) }
    var pendingShareText by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    var collapsed by remember { mutableStateOf(loadCollapsed(context)) }
    fun toggleSection(id: String) {
        collapsed = if (id in collapsed) collapsed - id else collapsed + id
        saveCollapsed(context, collapsed)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val vpnPermission = com.v2rayez.app.ui.LocalVpnPermission.current
    val notify: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }

    androidx.compose.runtime.LaunchedEffect(lastPingMessage) {
        val msg = lastPingMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearPingMessage()
    }

    androidx.compose.runtime.LaunchedEffect(refreshError) {
        val msg = refreshError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearRefreshError()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.selectionMode) {
                SelectionTopBar(
                    count = state.selected.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAllVisible,
                    onFavorite = { viewModel.favoriteSelected(true) },
                    onMove = { showMoveSelected = true },
                    onShare = {
                        val text = viewModel.exportSelected()
                        if (text.isNotBlank()) pendingShareText = text
                    },
                    onDelete = viewModel::deleteSelected
                )
            } else {
                V2TopBar(
                    title = stringResource(R.string.servers_title),
                    actions = {
                        TopIconButton(Icons.Filled.Search, stringResource(R.string.action_search)) { showSearch = !showSearch }
                    }
                )
            }

            if (showSearch) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text(stringResource(R.string.servers_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Lean toolbar: ping all, auto-select fastest, sort.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { viewModel.pingAll() }) {
                    Text(stringResource(R.string.servers_ping_all))
                }
                TextButton(onClick = {
                    vpnPermission.request { viewModel.connectFastest(notify) }
                }) {
                    Text(stringResource(R.string.servers_auto_fastest))
                }
                SortMenuButton(current = state.sortMode, onSelect = viewModel::setSortMode)
            }

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(
                                text = stringResource(R.string.free_servers_browse),
                                onClick = onBrowseFreeServers,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (state.sections.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.servers_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                    state.sections.forEach { section ->
                        val expanded = section.id !in collapsed
                        item(key = "header_${section.id}") {
                            SectionHeaderRow(
                                section = section,
                                expanded = expanded,
                                onToggle = { toggleSection(section.id) },
                                onPingGroup = { viewModel.pingSection(section.id) },
                                onRefresh = section.subscription?.let { sub ->
                                    { viewModel.refreshSubscription(sub.id, notify) }
                                },
                                onManage = when (section.type) {
                                    ServerSectionType.SUBSCRIPTION -> ({ manageSubscriptionId = section.subscription?.id })
                                    ServerSectionType.CUSTOM_GROUP -> ({ manageGroupName = section.title })
                                    else -> null
                                }
                            )
                        }
                        if (expanded) {
                            // Traffic labels only on subscription + manual rows (never free servers,
                            // which live on their own screen). Favorites/custom groups mirror those.
                            val showTraffic = section.type == ServerSectionType.SUBSCRIPTION ||
                                section.type == ServerSectionType.MANUAL
                            items(section.servers, key = { "${section.id}_${it.id}" }) { server ->
                                SwipeableServerItem(
                                    server = server,
                                    testing = server.id in state.testing,
                                    selected = server.id in state.selected,
                                    connected = server.id == state.connectedId,
                                    isDefault = server.id == state.defaultServerId,
                                    usageBytes = if (showTraffic) state.usageByServer[server.id] else null,
                                    selectionMode = state.selectionMode,
                                    onClick = {
                                        if (state.selectionMode) viewModel.toggleSelect(server.id)
                                        else connectTarget = server
                                    },
                                    onLongClick = { viewModel.toggleSelect(server.id) },
                                    onMenuClick = { menuTarget = server },
                                    onDelete = { viewModel.delete(server.id) },
                                    onToggleFavorite = { viewModel.toggleFavorite(server.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddImport = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.servers_add_import_title))
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    connectTarget?.let { s ->
        ConnectSheet(
            server = s,
            onDismiss = { connectTarget = null },
            onConnect = {
                vpnPermission.request { viewModel.connect(s) }
                notify(context.getString(R.string.servers_connecting_to, s.name))
                connectTarget = null
                onConnected()
            }
        )
    }
    menuTarget?.let { s ->
        val isDefault = s.id == state.defaultServerId
        ServerActionsSheet(
            server = s,
            isDefault = isDefault,
            onDismiss = { menuTarget = null },
            onEdit = { onEditServer(s.id); menuTarget = null },
            onToggleFavorite = { viewModel.toggleFavorite(s.id); menuTarget = null },
            onSetDefault = {
                if (isDefault) {
                    viewModel.setDefaultServer(null)
                    notify(context.getString(R.string.servers_default_cleared))
                } else {
                    viewModel.setDefaultServer(s.id)
                    notify(context.getString(R.string.servers_default_set, s.name))
                }
                menuTarget = null
            },
            onDuplicate = { viewModel.duplicate(s.id); menuTarget = null },
            onShare = {
                pendingShareText = viewModel.exportUri(s)
                menuTarget = null
            },
            onQr = { qrTarget = s; menuTarget = null },
            onTest = { viewModel.testLatency(s); menuTarget = null },
            onMoveToGroup = { moveTarget = s; menuTarget = null },
            onDelete = { viewModel.delete(s.id); menuTarget = null }
        )
    }
    qrTarget?.let { s ->
        QrShareSheet(
            title = s.name,
            content = viewModel.exportUri(s),
            onDismiss = { qrTarget = null }
        )
    }
    pendingShareText?.let { content ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingShareText = null },
            title = { Text(stringResource(R.string.servers_share_confirm_title)) },
            text = { Text(stringResource(R.string.servers_share_credential_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingShareText = null
                    shareText(context, content)
                    viewModel.clearSelection()
                }) {
                    Text(stringResource(R.string.action_share))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingShareText = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (showAddImport) {
        AddImportSheet(
            onDismiss = { showAddImport = false },
            onImport = { text ->
                viewModel.import(text, notify)
                showAddImport = false
            },
            onAddManual = {
                showAddImport = false
                onAddManual()
            },
            onAddSubscription = { name, url ->
                viewModel.addSubscription(name, url, notify)
                showAddImport = false
            },
            onAddFreeServers = {
                onBrowseFreeServers()
                showAddImport = false
            }
        )
    }
    manageSubscriptionId?.let { subId ->
        state.subscriptions.firstOrNull { it.id == subId }?.let { sub ->
            ManageSubscriptionSheet(
                subscription = sub,
                onDismiss = { manageSubscriptionId = null },
                onRename = { viewModel.renameSubscription(sub.id, it) },
                onEditUrl = { viewModel.updateSubscriptionUrl(sub.id, it, notify) },
                onToggleEnabled = { viewModel.setSubscriptionEnabled(sub.id, it) },
                onRefresh = { viewModel.refreshSubscription(sub.id, notify); manageSubscriptionId = null },
                onDelete = { viewModel.deleteSubscription(sub.id); manageSubscriptionId = null }
            )
        }
    }
    manageGroupName?.let { groupName ->
        ManageGroupSheet(
            groupName = groupName,
            onDismiss = { manageGroupName = null },
            onRename = { newName ->
                viewModel.renameCustomGroup(groupName, newName)
                manageGroupName = null
            },
            onDissolve = {
                viewModel.dissolveCustomGroup(groupName)
                manageGroupName = null
            }
        )
    }
    moveTarget?.let { s ->
        MoveToGroupSheet(
            groups = state.customGroups,
            currentGroup = s.customGroup,
            onDismiss = { moveTarget = null },
            onSelect = { group ->
                viewModel.setCustomGroup(s.id, group)
                moveTarget = null
            }
        )
    }
    if (showMoveSelected) {
        MoveToGroupSheet(
            groups = state.customGroups,
            currentGroup = null,
            onDismiss = { showMoveSelected = false },
            onSelect = { group ->
                viewModel.moveSelectedToGroup(group)
                showMoveSelected = false
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Selection mode top bar
// ---------------------------------------------------------------------------
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopIconButton(Icons.Filled.Close, stringResource(R.string.servers_clear_selection), onClick = onClose)
        Text(
            stringResource(R.string.servers_selected_count, count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        )
        TopIconButton(Icons.Filled.SelectAll, stringResource(R.string.servers_select_all), onClick = onSelectAll)
        TopIconButton(Icons.Filled.Star, stringResource(R.string.servers_selection_favorite), onClick = onFavorite)
        TopIconButton(Icons.Filled.DriveFileMove, stringResource(R.string.servers_selection_move), onClick = onMove)
        TopIconButton(Icons.Filled.Share, stringResource(R.string.servers_selection_share), onClick = onShare)
        TopIconButton(Icons.Filled.Delete, stringResource(R.string.servers_selection_delete), tint = ErrorRed, onClick = onDelete)
    }
}

// ---------------------------------------------------------------------------
// Sort menu (lean toolbar)
// ---------------------------------------------------------------------------
@Composable
private fun SortMenuButton(current: ServerSortMode, onSelect: (ServerSortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.Sort,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                stringResource(R.string.servers_sort) + when (current) {
                    ServerSortMode.DEFAULT -> ""
                    ServerSortMode.PING -> ": " + stringResource(R.string.servers_sort_ping_short)
                    ServerSortMode.NAME -> ": " + stringResource(R.string.servers_sort_name)
                    ServerSortMode.PROTOCOL -> ": " + stringResource(R.string.servers_sort_protocol)
                },
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortItem(stringResource(R.string.servers_sort_default), current == ServerSortMode.DEFAULT) {
                onSelect(ServerSortMode.DEFAULT); open = false
            }
            SortItem(stringResource(R.string.servers_sort_ping_short), current == ServerSortMode.PING) {
                onSelect(ServerSortMode.PING); open = false
            }
            SortItem(stringResource(R.string.servers_sort_name), current == ServerSortMode.NAME) {
                onSelect(ServerSortMode.NAME); open = false
            }
            SortItem(stringResource(R.string.servers_sort_protocol), current == ServerSortMode.PROTOCOL) {
                onSelect(ServerSortMode.PROTOCOL); open = false
            }
        }
    }
}

@Composable
private fun SortItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        onClick = onClick
    )
}

// ---------------------------------------------------------------------------
// Section header
// ---------------------------------------------------------------------------
@Composable
private fun SectionHeaderRow(
    section: ServerSection,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPingGroup: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    onManage: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    val title = when (section.type) {
        ServerSectionType.FAVORITES -> stringResource(R.string.servers_section_favorites)
        ServerSectionType.MANUAL -> stringResource(R.string.servers_section_manual)
        else -> section.title
    }
    val accent = when (section.type) {
        ServerSectionType.FAVORITES -> com.v2rayez.app.ui.theme.Warning
        ServerSectionType.SUBSCRIPTION -> MaterialTheme.colorScheme.primary
        ServerSectionType.CUSTOM_GROUP -> Connected
        ServerSectionType.MANUAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val enabled = section.subscription?.enabled != false

    CardSurface(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.servers_collapse else R.string.servers_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Icon(
                when (section.type) {
                    ServerSectionType.FAVORITES -> Icons.Filled.Star
                    ServerSectionType.CUSTOM_GROUP -> Icons.Filled.Folder
                    else -> Icons.Filled.Folder
                },
                contentDescription = null,
                tint = accent.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(16.dp)
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
                    maxLines = 1
                )
                val stats = buildList {
                    if (section.avgPingMs > 0) add(stringResource(R.string.servers_avg_ping, section.avgPingMs))
                    if (section.bestPingMs > 0) add(stringResource(R.string.servers_best_ping, section.bestPingMs))
                }
                if (stats.isNotEmpty()) {
                    Text(
                        stats.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            CountBadge(section.totalCount)
            Box {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.servers_menu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.servers_ping_group)) },
                        leadingIcon = { Icon(Icons.Filled.NetworkPing, contentDescription = null) },
                        onClick = { onPingGroup(); menuOpen = false }
                    )
                    if (onRefresh != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.servers_sub_refresh)) },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = { onRefresh(); menuOpen = false }
                        )
                    }
                    if (onManage != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (section.type == ServerSectionType.SUBSCRIPTION) R.string.servers_sub_manage
                                        else R.string.servers_group_manage
                                    )
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                            onClick = { onManage(); menuOpen = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------------------
// Server row with swipe actions
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableServerItem(
    server: Server,
    testing: Boolean,
    selected: Boolean,
    connected: Boolean,
    isDefault: Boolean,
    selectionMode: Boolean,
    usageBytes: Long? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    if (selectionMode) {
        // Swiping is disabled during multi-select so drags don't delete rows.
        ServerListItem(
            server = server,
            onClick = onClick,
            onMenuClick = onMenuClick,
            testing = testing,
            selected = selected,
            connected = connected,
            isDefault = isDefault,
            usageBytes = usageBytes,
            onLongClick = onLongClick
        )
        return
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                SwipeToDismissBoxValue.StartToEnd -> { onToggleFavorite(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeBackground(dismissState.targetValue) }
    ) {
        ServerListItem(
            server = server,
            onClick = onClick,
            onMenuClick = onMenuClick,
            testing = testing,
            selected = selected,
            connected = connected,
            isDefault = isDefault,
            usageBytes = usageBytes,
            onLongClick = onLongClick
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(target: SwipeToDismissBoxValue) {
    val (color, icon, alignment) = when (target) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(Connected, Icons.Filled.Star, Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart -> Triple(ErrorRed, Icons.Filled.Delete, Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, Icons.Filled.Star, Alignment.CenterStart)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.20f))
            .padding(horizontal = 24.dp),
        contentAlignment = alignment
    ) {
        if (target != SwipeToDismissBoxValue.Settled) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        }
    }
}

private fun shareText(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.servers_share_chooser)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun TopIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    cd: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(9.dp)
    ) {
        Icon(icon, contentDescription = cd, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Preview
@Composable
private fun ServersScreenPreview() {
    V2RayEzTheme { ServersScreen(viewModel = ServersViewModel()) }
}
