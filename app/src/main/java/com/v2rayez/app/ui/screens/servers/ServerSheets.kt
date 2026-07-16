package com.v2rayez.app.ui.screens.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.domain.model.Subscription
import com.v2rayez.app.ui.components.CountryFlag
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.TextActionButton
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.ErrorRed

/** Bottom sheet showing a server's config with a Connect button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectSheet(server: Server, onDismiss: () -> Unit, onConnect: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CountryFlag(server.countryCode, size = 44)
                HSpacer(12)
                Column {
                    Text(stringResource(R.string.sheet_connect_to), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            VSpacer(20)
            ConfigRow(stringResource(R.string.sheet_protocol), server.protocol.label)
            ConfigRow(stringResource(R.string.sheet_transport), server.transport)
            ConfigRow(stringResource(R.string.sheet_security), server.security)
            ConfigRow("SNI", server.sni)
            VSpacer(20)
            PrimaryButton(text = stringResource(R.string.sheet_connect), onClick = onConnect, modifier = Modifier.fillMaxWidth())
            VSpacer(4)
            TextActionButton(text = stringResource(R.string.sheet_cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            VSpacer(8)
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Unified Add / Import sheet (V2RayNG-style): Clipboard, QR, File, Manual editor,
 * and Subscription URL in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddImportSheet(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    onAddManual: () -> Unit,
    onAddSubscription: (name: String, url: String) -> Unit,
    onAddFreeServers: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var link by remember { mutableStateOf("") }
    var subName by remember { mutableStateOf("") }
    var subUrl by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var showScanner by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val fileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            }.getOrNull()?.let { text -> link = text }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.servers_add_import_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            VSpacer(16)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImportTab(Icons.Filled.ContentPaste, stringResource(R.string.servers_add_clipboard), tab == 0) {
                    tab = 0
                    val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    clip?.primaryClip?.getItemAt(0)?.text?.let { link = it.toString() }
                }
                ImportTab(Icons.Filled.QrCode, stringResource(R.string.servers_add_qr), tab == 1) {
                    tab = 1
                    showScanner = true
                }
                ImportTab(Icons.Filled.FileUpload, stringResource(R.string.servers_add_file), tab == 2) {
                    tab = 2
                    fileLauncher.launch("*/*")
                }
                ImportTab(Icons.Filled.EditNote, stringResource(R.string.servers_add_manual), tab == 3) {
                    onAddManual()
                    onDismiss()
                }
                ImportTab(Icons.Filled.Link, stringResource(R.string.servers_add_subscription), tab == 4) {
                    tab = 4
                }
            }
            VSpacer(16)
            when (tab) {
                4 -> {
                    OutlinedTextField(
                        value = subName,
                        onValueChange = { subName = it },
                        label = { Text(stringResource(R.string.servers_sub_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    VSpacer(10)
                    OutlinedTextField(
                        value = subUrl,
                        onValueChange = { subUrl = it },
                        label = { Text(stringResource(R.string.servers_sub_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    VSpacer(16)
                    PrimaryButton(
                        text = stringResource(R.string.action_add),
                        onClick = { if (subUrl.isNotBlank()) onAddSubscription(subName, subUrl.trim()) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        placeholder = { Text(stringResource(R.string.servers_import_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    )
                    VSpacer(16)
                    PrimaryButton(
                        text = stringResource(R.string.servers_import_action),
                        onClick = { onImport(link) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            VSpacer(8)
            TextActionButton(
                text = stringResource(R.string.free_servers_browse),
                onClick = onAddFreeServers,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            VSpacer(8)
        }
    }

    if (showScanner) {
        com.v2rayez.app.ui.components.QrScannerDialog(
            onResult = { value ->
                link = value
                tab = 0
                showScanner = false
            },
            onDismiss = { showScanner = false },
            prompt = stringResource(R.string.servers_scan_qr_prompt)
        )
    }
}

@Composable
private fun ImportTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Context menu for a server: duplicate / share / test / move to group / delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerActionsSheet(
    server: Server,
    onDismiss: () -> Unit,
    isDefault: Boolean = false,
    onEdit: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onSetDefault: () -> Unit = {},
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onQr: () -> Unit = {},
    onTest: () -> Unit,
    onMoveToGroup: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                server.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            ActionItem(Icons.Filled.Edit, stringResource(R.string.sheet_edit_server), onEdit)
            ActionItem(
                if (isDefault) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                stringResource(if (isDefault) R.string.servers_action_unset_default else R.string.servers_action_set_default),
                onSetDefault
            )
            ActionItem(
                if (server.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                stringResource(if (server.isFavorite) R.string.servers_action_unfavorite else R.string.servers_action_favorite),
                onToggleFavorite
            )
            if (onMoveToGroup != null) {
                ActionItem(Icons.Filled.DriveFileMove, stringResource(R.string.servers_action_move_group), onMoveToGroup)
            }
            ActionItem(Icons.Filled.ContentCopy, stringResource(R.string.sheet_duplicate_server), onDuplicate)
            ActionItem(Icons.Filled.Share, stringResource(R.string.sheet_share_server), onShare)
            ActionItem(Icons.Filled.QrCode, stringResource(R.string.servers_action_qr), onQr)
            ActionItem(Icons.Filled.NetworkPing, stringResource(R.string.sheet_test_connectivity), onTest)
            ActionItem(Icons.Filled.Delete, stringResource(R.string.sheet_delete_server), onDelete, ErrorRed)
        }
    }
}

/** Inline subscription management: rename, edit URL, enable/disable, refresh, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageSubscriptionSheet(
    subscription: Subscription,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onEditUrl: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(subscription.id) { mutableStateOf(subscription.name) }
    var url by remember(subscription.id) { mutableStateOf(subscription.url) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(
                stringResource(R.string.servers_sub_manage),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            VSpacer(16)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.servers_sub_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(10)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.servers_sub_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(12)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.servers_sub_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.servers_sub_enabled_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = subscription.enabled, onCheckedChange = onToggleEnabled)
            }
            VSpacer(16)
            PrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    if (name.trim() != subscription.name) onRename(name)
                    if (url.trim() != subscription.url) onEditUrl(url)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(6)
            TextActionButton(
                text = stringResource(R.string.servers_sub_refresh),
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            TextActionButton(
                text = stringResource(R.string.servers_sub_delete),
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                color = ErrorRed
            )
            VSpacer(8)
        }
    }
}

/** Custom group management: rename or dissolve. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageGroupSheet(
    groupName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDissolve: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(groupName) { mutableStateOf(groupName) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(
                stringResource(R.string.servers_group_manage),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            VSpacer(16)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.servers_group_rename)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(16)
            PrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = { if (name.isNotBlank()) onRename(name.trim()) },
                modifier = Modifier.fillMaxWidth()
            )
            VSpacer(6)
            TextActionButton(
                text = stringResource(R.string.servers_group_dissolve),
                onClick = onDissolve,
                modifier = Modifier.fillMaxWidth(),
                color = ErrorRed
            )
            VSpacer(8)
        }
    }
}

/** Pick (or create) the custom group to assign servers to; null clears the group. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToGroupSheet(
    groups: List<String>,
    currentGroup: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newGroup by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(
                stringResource(R.string.servers_move_to_group),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            VSpacer(12)
            GroupOptionRow(
                label = stringResource(R.string.servers_group_none),
                selected = currentGroup.isNullOrBlank(),
                onClick = { onSelect(null) }
            )
            groups.forEach { g ->
                GroupOptionRow(label = g, selected = g == currentGroup, onClick = { onSelect(g) })
            }
            VSpacer(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newGroup,
                    onValueChange = { newGroup = it },
                    label = { Text(stringResource(R.string.servers_group_new_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                HSpacer(10)
                PrimaryButton(
                    text = stringResource(R.string.action_add),
                    onClick = { if (newGroup.isNotBlank()) onSelect(newGroup.trim()) }
                )
            }
            VSpacer(8)
        }
    }
}

@Composable
private fun GroupOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        HSpacer(12)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Bottom sheet showing a QR code for a server's share URI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrShareSheet(title: String, content: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bitmap = androidx.compose.runtime.remember(content) { com.v2rayez.app.util.qrBitmap(content) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            VSpacer(16)
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.action_qr_code),
                    modifier = Modifier.size(260.dp)
                )
            } else {
                Text(stringResource(R.string.sheet_qr_unable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VSpacer(16)
            Text(
                content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 3
            )
            VSpacer(8)
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.primary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        HSpacer(16)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (tint == ErrorRed) ErrorRed else MaterialTheme.colorScheme.onSurface)
    }
}
