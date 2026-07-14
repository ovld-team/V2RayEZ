package com.v2rayez.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.Server
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.Warning

/**
 * Small stat tile: icon + label + value (Download / Upload / Ping / Speed).
 * Label/value are clamped to one line so long translated strings (FA/RU) never blow up the
 * tile's height and desync it from its row sibling on narrow (compact-width) screens — pair
 * with `Modifier.height(IntrinsicSize.Min)` on the parent `Row` for equal-height tiles.
 */
@Composable
fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    CardSurface(modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            VSpacer(8)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A row in a server list: flag, name, protocol/security subtitle, ping + signal, overflow. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ServerListItem(
    server: Server,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    testing: Boolean = false,
    selected: Boolean = false,
    connected: Boolean = false,
    isDefault: Boolean = false,
    /**
     * Lifetime traffic (down + up bytes) for this server. When > 0 a compact usage label is
     * shown. Only wired for the main Servers list (subscription + manual rows); the Free
     * Servers browser renders its own row and never passes this.
     */
    usageBytes: Long? = null,
    onLongClick: (() -> Unit)? = null
) {
    val highlight = when {
        selected -> MaterialTheme.colorScheme.primary
        connected -> Connected
        else -> null
    }
    CardSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = highlight?.let { androidx.compose.foundation.BorderStroke(1.5.dp, it) }
    ) {
        Row(
            modifier = Modifier
                .background(highlight?.copy(alpha = 0.10f) ?: Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                HSpacer(10)
            }
            CountryFlag(server.countryCode)
            HSpacer(12)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        server.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (server.isFavorite) {
                        HSpacer(6)
                        Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.servers_favorite), tint = Warning, modifier = Modifier.size(14.dp))
                    }
                    if (isDefault) {
                        HSpacer(6)
                        Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.servers_default_badge), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                    }
                }
                VSpacer(2)
                val pingLabel = when {
                    testing -> stringResource(R.string.servers_testing)
                    server.pingMs > 0 -> "${server.pingMs} ms"
                    server.pingMs == 0 -> "0 ms"
                    else -> "—"
                }
                val subtitle = if (connected) {
                    stringResource(R.string.servers_connected_label) + " · $pingLabel"
                } else {
                    "${server.protocol.label} · ${server.security} · $pingLabel"
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) Connected else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (usageBytes != null && usageBytes > 0L) {
                    VSpacer(2)
                    Text(
                        stringResource(R.string.servers_usage_label, com.v2rayez.app.util.Formatters.bytes(usageBytes)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
            if (connected) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Connected))
                HSpacer(8)
            }
            if (testing) {
                // Sized up from 16.dp with an explicit track so the probe spinner reads as
                // obvious motion instead of a barely-visible dot (matches the fix applied to
                // the Free Servers row indicator for the same "is this frozen?" complaint).
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
            } else {
                SignalBars(level = server.signal)
            }
            HSpacer(4)
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.action_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable(onClick = onMenuClick)
            )
        }
    }
}

/**
 * Quick action: circular icon over a caption. Label is clamped to 2 lines with a smaller
 * min-scale so long FA/RU captions shrink to fit instead of wrapping unevenly and pushing
 * sibling buttons out of alignment in a `SpaceBetween`/weighted row on narrow screens.
 */
@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            softWrap = true
        )
    }
}

/**
 * Tools grid card: icon tile, title, subtitle. Title/subtitle are clamped so mismatched text
 * length between grid siblings doesn't desync card heights; pair with
 * `Modifier.height(IntrinsicSize.Min)` on the parent `Row` + `Modifier.fillMaxHeight()` on each
 * card for equal-height rows on compact-width screens.
 */
@Composable
fun ToolCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    CardSurface(modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(onClick = onClick)
                .padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            VSpacer(12)
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            VSpacer(2)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A settings/advanced list row: leading icon, title, optional subtitle, trailing content. */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        HSpacer(14)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

/** Settings row with a value label on the right (e.g. Theme -> System). */
@Composable
fun SettingValueRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingRow(icon = icon, title = title, onClick = onClick, modifier = modifier, trailing = {
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    })
}

/** Settings row with a trailing switch. */
@Composable
fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    SettingRow(icon = icon, title = title, subtitle = subtitle, modifier = modifier, trailing = {
        V2Switch(checked = checked, onCheckedChange = onCheckedChange)
    })
}

/**
 * Shared “changes apply after reconnect” banner (App Proxy / SNI / Domain Front / etc.).
 */
@Composable
fun ReconnectBanner(
    hint: String,
    actionLabel: String,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    reconnecting: Boolean = false,
    reconnectingLabel: String = actionLabel
) {
    CardSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (reconnecting) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Warning,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = Warning,
                    modifier = Modifier.size(18.dp)
                )
            }
            HSpacer(10)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReconnect, enabled = !reconnecting) {
                Text(if (reconnecting) reconnectingLabel else actionLabel)
            }
        }
    }
}
