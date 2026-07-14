package com.v2rayez.app.ui.screens.donate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.v2rayez.app.R
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.CryptoDonationRow
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.PromoLinks
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.accentGradient

@Composable
fun DonateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2BackTopBar(title = stringResource(R.string.donate_title), onBack = onBack)

        Column(Modifier.padding(horizontal = 16.dp)) {
            SupportCard()
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.donate_section_community), modifier = Modifier.fillMaxWidth())
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column {
                    SocialRow(Icons.Filled.Send, "Telegram", "@EzAccess1") { openUrl(context, PromoLinks.TELEGRAM_URL) }
                    SocialRow(Icons.Filled.PlayCircle, "YouTube", "@MacanDev") { openUrl(context, PromoLinks.YOUTUBE_URL) }
                }
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.donate_section_crypto), modifier = Modifier.fillMaxWidth())
            Donations.list.forEach { donation ->
                CryptoDonationRow(
                    donation = donation,
                    onCopy = { copyToClipboard(context, donation.address) }
                )
                VSpacer(12)
            }

            VSpacer(8)
            ThankYouCard()
            VSpacer(24)
        }
    }
}

@Composable
private fun SupportCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(accentGradient(MaterialTheme.colorScheme.primary)))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.donate_support_dev), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                VSpacer(6)
                Text(
                    stringResource(R.string.donate_support_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun ThankYouCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(R.string.donate_thanks), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SocialRow(icon: ImageVector, title: String, handle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        HSpacer(14)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(handle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("address", text))
    Toast.makeText(context, context.getString(R.string.donate_address_copied), Toast.LENGTH_SHORT).show()
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.common_no_app_for_link), Toast.LENGTH_SHORT).show()
    }
}

@Preview
@Composable
private fun DonateScreenPreview() {
    V2RayEzTheme { DonateScreen(onBack = {}) }
}
