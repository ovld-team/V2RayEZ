package com.v2rayez.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2rayez.app.ui.theme.Connected

/** A section title with optional trailing content (e.g. a "This week" pill). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        trailing?.invoke()
    }
}

/** Elevated rounded surface used for the card style throughout the app. */
@Composable
fun CardSurface(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(18.dp),
    border: androidx.compose.foundation.BorderStroke? = null,
    content: @Composable () -> Unit
) {
    Surface(color = color, shape = shape, border = border, modifier = modifier) {
        content()
    }
}

/**
 * Circular country flag. Renders the real Unicode regional-indicator flag glyph
 * for a valid ISO 3166-1 alpha-2 code (e.g. "US" -> the US flag), which every
 * modern Android version can draw with no bundled image assets. Falls back to a
 * tinted disc with the code for unknown / placeholder codes (e.g. "UN").
 */
@Composable
fun CountryFlag(countryCode: String, size: Int = 40, modifier: Modifier = Modifier) {
    val tint = flagColor(countryCode)
    val flagEmoji = flagEmoji(countryCode)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        if (flagEmoji != null) {
            Text(
                text = flagEmoji,
                fontSize = (size * 0.5f).sp
            )
        } else {
            Text(
                text = countryCode,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Convert an ISO 3166-1 alpha-2 code to its Unicode regional-indicator flag
 * emoji, or null when the code is not two A–Z letters.
 */
private fun flagEmoji(code: String): String? {
    if (code.length != 2) return null
    val upper = code.uppercase()
    if (upper.any { it !in 'A'..'Z' }) return null
    val base = 0x1F1E6
    val first = base + (upper[0] - 'A')
    val second = base + (upper[1] - 'A')
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

private fun flagColor(code: String): Color = when (code) {
    "JP" -> Color(0xFFEF4444)
    "DE" -> Color(0xFFF59E0B)
    "US" -> Color(0xFF3B82F6)
    "SG" -> Color(0xFFEF4444)
    "FR" -> Color(0xFF3B82F6)
    "GB" -> Color(0xFF8B5CF6)
    "CA" -> Color(0xFFEF4444)
    else -> Color(0xFF64748B)
}

/** 4-bar signal indicator; [level] is 0..4. */
@Composable
fun SignalBars(level: Int, modifier: Modifier = Modifier, activeColor: Color = Connected) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val heights = listOf(6, 10, 14, 18)
        heights.forEachIndexed { index, h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < level) activeColor else MaterialTheme.colorScheme.outline)
            )
        }
    }
}

@Composable
fun VSpacer(height: Int) = Spacer(Modifier.height(height.dp))

@Composable
fun RowScope.HSpacer(width: Int) = Spacer(Modifier.width(width.dp))

/** Small caption in the muted secondary color. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}
