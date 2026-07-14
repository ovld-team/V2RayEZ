package com.v2rayez.app.ui.screens.about

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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText
import com.v2rayez.app.BuildConfig
import com.v2rayez.app.R
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.accentGradient

@Composable
fun AboutScreen(onBack: () -> Unit, onDonate: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2BackTopBar(title = stringResource(R.string.settings_about), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            VSpacer(12)
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(accentGradient(MaterialTheme.colorScheme.primary))),
                contentAlignment = Alignment.Center
            ) {
                Text("V", color = Color.White, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
            }
            VSpacer(14)
            Text("V2RayEz", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            VSpacer(10)
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            VSpacer(20)

            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column {
                    LinkRow(Icons.Filled.Language, stringResource(R.string.about_website)) { openUrl(context, BuildConfig.LINK_WEBSITE) }
                    Divider()
                    LinkRow(Icons.Filled.Send, stringResource(R.string.about_telegram)) { openUrl(context, BuildConfig.LINK_TELEGRAM) }
                    Divider()
                    LinkRow(Icons.Filled.PlayCircle, stringResource(R.string.about_youtube)) { openUrl(context, BuildConfig.LINK_YOUTUBE) }
                }
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.about_section_information), modifier = Modifier.fillMaxWidth())
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    InfoRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
                    InfoRow(stringResource(R.string.about_build), BuildConfig.VERSION_CODE.toString())
                    DevelopersRow(
                        label = stringResource(R.string.about_developer),
                        macan = stringResource(R.string.about_developer_macan),
                        overlord = stringResource(R.string.about_developer_overlord_team),
                        onOverlordClick = { openUrl(context, OVERLORD_TEAM_URL) }
                    )
                    InfoRow(stringResource(R.string.about_license), "Apache 2.0")
                }
            }
            VSpacer(20)

            PrimaryButton(text = stringResource(R.string.donate_support_dev), onClick = onDonate, modifier = Modifier.fillMaxWidth())
            VSpacer(24)
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        HSpacer(14)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DevelopersRow(
    label: String,
    macan: String,
    overlord: String,
    onOverlordClick: () -> Unit
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val annotated = buildAnnotatedString {
        append(macan)
        append(" · ")
        pushStringAnnotation(tag = OVERLORD_TAG, annotation = OVERLORD_TEAM_URL)
        withStyle(
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Medium
            )
        ) {
            append(overlord)
        }
        pop()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ClickableText(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium.copy(color = bodyColor),
            onClick = { offset ->
                annotated.getStringAnnotations(OVERLORD_TAG, offset, offset)
                    .firstOrNull()
                    ?.let { onOverlordClick() }
            }
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp, modifier = Modifier.padding(start = 52.dp))
}

private const val OVERLORD_TAG = "overlord"
private const val OVERLORD_TEAM_URL = "https://overlord.team"

private fun openUrl(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.common_no_app_for_link), Toast.LENGTH_SHORT).show()
    }
}

@Preview
@Composable
private fun AboutScreenPreview() {
    V2RayEzTheme { AboutScreen(onBack = {}, onDonate = {}) }
}
