package com.v2rayez.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.CryptoDonation
import com.v2rayez.app.domain.model.LogEntry
import com.v2rayez.app.domain.model.LogLevel
import com.v2rayez.app.ui.theme.BtcOrange
import com.v2rayez.app.ui.theme.Debug
import com.v2rayez.app.ui.theme.ErrorRed
import com.v2rayez.app.ui.theme.EthBlue
import com.v2rayez.app.ui.theme.Info
import com.v2rayez.app.ui.theme.TonBlue
import com.v2rayez.app.ui.theme.UsdtGreen
import com.v2rayez.app.ui.theme.Warning

fun logLevelColor(level: LogLevel): Color = when (level) {
    LogLevel.INFO -> Info
    LogLevel.WARNING -> Warning
    LogLevel.ERROR -> ErrorRed
    LogLevel.DEBUG -> Debug
}

/** A single log line: timestamp, colored level tag, message and optional detail. */
@Composable
fun LogRow(entry: LogEntry, modifier: Modifier = Modifier) {
    val color = logLevelColor(entry.level)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            entry.timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp)
        )
        HSpacer(8)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("[${entry.level.label}]", style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        }
        HSpacer(10)
        Column(Modifier.weight(1f)) {
            Text(entry.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (entry.detail != null) {
                Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun cryptoColor(symbol: String): Color = when (symbol) {
    "USDT" -> UsdtGreen
    "BTC" -> BtcOrange
    "ETH" -> EthBlue
    "TON" -> TonBlue
    "SOL" -> Color(0xFF14F195)
    "BNB" -> Color(0xFFF0B90B)
    "TRX" -> Color(0xFFEF4444)
    else -> Color(0xFF64748B)
}

/** Crypto donation row: coin badge, symbol/network, address, copy button. */
@Composable
fun CryptoDonationRow(
    donation: CryptoDonation,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = cryptoColor(donation.symbol)
    CardSurface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoinBadge(donation, color)
            HSpacer(12)
            Column(Modifier.weight(1f)) {
                // [network] is self-describing (e.g. "Bitcoin (BTC)", "USDT · TRC20"); the
                // coin is also shown as the emoji/letter badge, so no need to prefix the symbol.
                Text(donation.network, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(donation.shortAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.action_copy_address),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCopy)
                    .padding(4.dp)
            )
        }
    }
}

/** Coin badge: renders the real coin logo from the CDN, falling back to the emoji/letter. */
@Composable
private fun CoinBadge(donation: CryptoDonation, color: Color) {
    val shape = RoundedCornerShape(50)
    if (donation.iconUrl.isNotBlank()) {
        coil.compose.SubcomposeAsyncImage(
            model = donation.iconUrl,
            contentDescription = donation.symbol,
            modifier = Modifier.size(40.dp).clip(shape),
            loading = { CoinGlyphFallback(donation, color, shape) },
            error = { CoinGlyphFallback(donation, color, shape) }
        )
    } else {
        CoinGlyphFallback(donation, color, shape)
    }
}

@Composable
private fun CoinGlyphFallback(donation: CryptoDonation, color: Color, shape: androidx.compose.ui.graphics.Shape) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        if (donation.emoji.isNotBlank()) {
            Text(donation.emoji, fontWeight = FontWeight.Bold)
        } else {
            Text(donation.symbol.take(1), color = color, fontWeight = FontWeight.Bold)
        }
    }
}

/** Activity timeline row for Home "Recent Activity". */
@Composable
fun ActivityRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    time: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
