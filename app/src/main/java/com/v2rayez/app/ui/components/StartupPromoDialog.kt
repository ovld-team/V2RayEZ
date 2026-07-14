package com.v2rayez.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.v2rayez.app.R
import com.v2rayez.app.ui.theme.accentGradient

/** Telegram / YouTube handles surfaced by the first-launch promo. */
object PromoLinks {
    const val TELEGRAM_HANDLE = "EzAccess1"
    const val TELEGRAM_URL = "https://t.me/EzAccess1"
    const val YOUTUBE_URL = "https://youtube.com/@MacanDev"
}

/**
 * First-launch modal inviting the user to join the Telegram channel and subscribe on
 * YouTube. The links deep-link into the native apps first and fall back to the browser.
 * A "Do not show again" checkbox permanently dismisses it via [onDismiss] (persisted by
 * the caller).
 */
@Composable
fun StartupPromoDialog(onDismiss: (dontShowAgain: Boolean) -> Unit) {
    val context = LocalContext.current
    var dontShowAgain by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { onDismiss(dontShowAgain) }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.linearGradient(accentGradient(MaterialTheme.colorScheme.primary))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            VSpacer(16)
            Text(
                stringResource(R.string.promo_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            VSpacer(8)
            Text(
                stringResource(R.string.promo_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            VSpacer(20)

            PromoButton(
                icon = Icons.Filled.Send,
                label = stringResource(R.string.promo_join_telegram),
                onClick = { openTelegram(context) }
            )
            VSpacer(10)
            PromoButton(
                icon = Icons.Filled.PlayCircle,
                label = stringResource(R.string.promo_subscribe_youtube),
                onClick = { openYoutube(context) }
            )
            VSpacer(16)

            V2Checkbox(
                checked = dontShowAgain,
                onCheckedChange = { dontShowAgain = it },
                label = stringResource(R.string.promo_dont_show)
            )
            VSpacer(8)
            TextActionButton(
                text = stringResource(R.string.promo_maybe_later),
                onClick = { onDismiss(dontShowAgain) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PromoButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        HSpacer(10)
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

private fun openTelegram(context: Context) {
    // Try the native Telegram app first, then fall back to the web link.
    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=${PromoLinks.TELEGRAM_HANDLE}"))
        .setPackage("org.telegram.messenger")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (!tryStart(context, appIntent)) {
        openUrl(context, PromoLinks.TELEGRAM_URL)
    }
}

private fun openYoutube(context: Context) {
    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PromoLinks.YOUTUBE_URL))
        .setPackage("com.google.android.youtube")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (!tryStart(context, appIntent)) {
        openUrl(context, PromoLinks.YOUTUBE_URL)
    }
}

private fun tryStart(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent) }.isSuccess

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.common_no_app_for_link), Toast.LENGTH_SHORT).show()
    }
}
