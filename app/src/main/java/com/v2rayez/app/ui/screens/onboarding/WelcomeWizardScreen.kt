package com.v2rayez.app.ui.screens.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.OnboardingWants
import com.v2rayez.app.ui.SupportedLanguages
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.PrimaryButton
import com.v2rayez.app.ui.components.V2FilterChip
import com.v2rayez.app.ui.components.V2Switch
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.AccentBlue
import com.v2rayez.app.ui.theme.AccentGreen
import com.v2rayez.app.ui.theme.AccentOrange
import com.v2rayez.app.ui.theme.AccentPink
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.MotionTokens
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.Violet
import com.v2rayez.app.ui.theme.accentGradient
import com.v2rayez.app.ui.viewmodel.OnboardingViewModel

private const val PAGE_COUNT = 6

/**
 * First-run welcome wizard: brand intro, features, terms, notifications, ready.
 * Completing the last step persists [AppSettings.onboardingComplete].
 */
@Composable
fun WelcomeWizardScreen(
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    var page by remember { mutableIntStateOf(0) }
    var termsChecked by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()

    fun goTo(next: Int) {
        page = next
    }

    BackHandler(enabled = page > 0) {
        goTo(page - 1)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort — either outcome advances */ goTo(4) }

    fun requestNotificationsThenAdvance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            goTo(4)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        WizardPageIndicator(current = page, total = PAGE_COUNT)
        VSpacer(24)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState > initialState
                    if (forward) {
                        (slideInHorizontally { it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally { -it / 3 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally { -it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally { it / 3 } + fadeOut(tween(180)))
                    }
                },
                label = "wizardPage"
            ) { current ->
                when (current) {
                    0 -> WelcomePage(
                        selectedLanguage = settings.language,
                        onLanguageSelected = viewModel::setLanguage
                    )
                    1 -> FeaturesPage()
                    2 -> TermsPage(
                        checked = termsChecked,
                        onCheckedChange = { termsChecked = it }
                    )
                    3 -> NotificationsPage()
                    4 -> WantsPage(
                        wants = settings.onboardingWants,
                        onChange = { w -> viewModel.setWants(w, analytics = true) }
                    )
                    else -> ReadyPage()
                }
            }
        }

        VSpacer(16)

        when (page) {
            0, 1 -> PrimaryButton(
                text = stringResource(R.string.wizard_continue),
                onClick = { goTo(page + 1) },
                modifier = Modifier.fillMaxWidth()
            )
            2 -> PrimaryButton(
                text = stringResource(R.string.wizard_continue),
                onClick = {
                    viewModel.acceptTerms()
                    goTo(3)
                },
                enabled = termsChecked,
                modifier = Modifier.fillMaxWidth()
            )
            3 -> {
                PrimaryButton(
                    text = stringResource(R.string.wizard_notifications_allow),
                    onClick = { requestNotificationsThenAdvance() },
                    modifier = Modifier.fillMaxWidth()
                )
                VSpacer(8)
                TextButton(
                    onClick = { goTo(4) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.wizard_notifications_not_now))
                }
            }
            4 -> PrimaryButton(
                text = stringResource(R.string.wizard_continue),
                onClick = { goTo(5) },
                modifier = Modifier.fillMaxWidth()
            )
            else -> PrimaryButton(
                text = stringResource(R.string.wizard_get_started),
                onClick = { viewModel.completeOnboarding() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WizardPageIndicator(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val active = index == current
            val size by animateDpAsState(
                targetValue = if (active) 10.dp else 8.dp,
                animationSpec = tween(220),
                label = "dotSize$index"
            )
            val color by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                animationSpec = tween(220),
                label = "dotColor$index"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(size)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun WelcomePage(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val logoScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.82f,
        animationSpec = tween(520),
        label = "welcomeLogoScale"
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(420),
        label = "welcomeLogoAlpha"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .scale(logoScale)
                .graphicsLayer { alpha = logoAlpha }
        ) {
            // Soft concentric rings (HomeScreen connect-ring style)
            Box(
                Modifier
                    .size(168.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            )
            Box(
                Modifier
                    .size(148.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            )
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(accentGradient(MaterialTheme.colorScheme.primary))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo_v),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
        VSpacer(28)
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        VSpacer(12)
        Text(
            text = stringResource(R.string.wizard_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        VSpacer(28)
        Text(
            text = stringResource(R.string.wizard_choose_language),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        VSpacer(12)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SupportedLanguages.labels.forEach { label ->
                val chipLabel = when (label) {
                    SupportedLanguages.ENGLISH -> "EN"
                    SupportedLanguages.PERSIAN -> "FA"
                    SupportedLanguages.RUSSIAN -> "RU"
                    else -> label
                }
                V2FilterChip(
                    label = chipLabel,
                    selected = selectedLanguage == label,
                    onClick = { onLanguageSelected(label) }
                )
            }
        }
    }
}

@Composable
private fun FeaturesPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.wizard_features_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        VSpacer(8)
        Text(
            text = stringResource(R.string.wizard_features_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VSpacer(28)
        FeatureRow(
            icon = Icons.Filled.Security,
            title = stringResource(R.string.wizard_feature_tunnel_title),
            body = stringResource(R.string.wizard_feature_tunnel_body)
        )
        VSpacer(16)
        FeatureRow(
            icon = Icons.Filled.Storage,
            title = stringResource(R.string.wizard_feature_protocols_title),
            body = stringResource(R.string.wizard_feature_protocols_body)
        )
        VSpacer(16)
        FeatureRow(
            icon = Icons.Filled.Build,
            title = stringResource(R.string.wizard_feature_tools_title),
            body = stringResource(R.string.wizard_feature_tools_body)
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    CardSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(accentGradient(MaterialTheme.colorScheme.primary))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                VSpacer(4)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermsPage(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    var showFull by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.wizard_terms_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        VSpacer(8)
        Text(
            text = stringResource(R.string.wizard_terms_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VSpacer(16)
        FeatureRow(
            icon = Icons.Filled.Security,
            title = stringResource(R.string.wizard_terms_highlight_privacy),
            body = stringResource(R.string.wizard_terms_highlight_privacy_body)
        )
        VSpacer(8)
        FeatureRow(
            icon = Icons.Filled.Build,
            title = stringResource(R.string.wizard_terms_highlight_use),
            body = stringResource(R.string.wizard_terms_highlight_use_body)
        )
        VSpacer(12)
        TextButton(onClick = { showFull = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wizard_terms_read_full))
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
            Text(
                text = stringResource(R.string.wizard_terms_accept),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
    if (showFull) {
        ModalBottomSheet(
            onDismissRequest = { showFull = false }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.wizard_terms_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                VSpacer(24)
            }
        }
    }
}

@Composable
private fun NotificationsPage() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(accentGradient(MaterialTheme.colorScheme.primary))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(44.dp)
            )
        }
        VSpacer(28)
        Text(
            text = stringResource(R.string.wizard_notifications_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        VSpacer(12)
        Text(
            text = stringResource(R.string.wizard_notifications_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** One selectable feature in the [WantsPage] picker. */
private data class WantFeature(
    val icon: ImageVector,
    val accent: Color,
    val title: String,
    val description: String,
    val checked: Boolean,
    val onToggle: (Boolean) -> Unit
)

@Composable
private fun WantsPage(
    wants: OnboardingWants,
    onChange: (OnboardingWants) -> Unit
) {
    val features = listOf(
        WantFeature(
            icon = Icons.Filled.Shield,
            accent = AccentGreen,
            title = stringResource(R.string.wizard_want_tor),
            description = stringResource(R.string.wizard_want_tor_desc),
            checked = wants.tor,
            onToggle = { onChange(wants.copy(tor = it)) }
        ),
        WantFeature(
            icon = Icons.Filled.SwapHoriz,
            accent = AccentPink,
            title = stringResource(R.string.wizard_want_mitm),
            description = stringResource(R.string.wizard_want_mitm_desc),
            checked = wants.mitm,
            onToggle = { onChange(wants.copy(mitm = it)) }
        ),
        WantFeature(
            icon = Icons.Filled.Speed,
            accent = AccentBlue,
            title = stringResource(R.string.wizard_want_dpi),
            description = stringResource(R.string.wizard_want_dpi_desc),
            checked = wants.dpiBypass,
            onToggle = { onChange(wants.copy(dpiBypass = it)) }
        ),
        WantFeature(
            icon = Icons.Filled.Wifi,
            accent = AccentOrange,
            title = stringResource(R.string.wizard_want_hotspot),
            description = stringResource(R.string.wizard_want_hotspot_desc),
            checked = wants.hotspot,
            onToggle = { onChange(wants.copy(hotspot = it)) }
        ),
        WantFeature(
            icon = Icons.Filled.Memory,
            accent = Violet,
            title = stringResource(R.string.wizard_want_cores),
            description = stringResource(R.string.wizard_want_cores_desc),
            checked = wants.processCores,
            onToggle = { onChange(wants.copy(processCores = it)) }
        )
    )
    val selectedCount = features.count { it.checked }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.wizard_wants_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        VSpacer(8)
        Text(
            text = stringResource(R.string.wizard_wants_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VSpacer(14)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = stringResource(R.string.wizard_wants_selected_count, selectedCount, features.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        VSpacer(18)
        features.forEachIndexed { index, feature ->
            FeatureSelectCard(feature)
            if (index != features.lastIndex) VSpacer(10)
        }
        VSpacer(18)
        AnalyticsNoticeCard()
        VSpacer(4)
    }
}

/** Whole-card-tappable feature toggle with an accent icon badge, title and short description. */
@Composable
private fun FeatureSelectCard(feature: WantFeature) {
    val iconBg by animateColorAsState(
        targetValue = if (feature.checked) feature.accent else feature.accent.copy(alpha = 0.16f),
        animationSpec = MotionTokens.normal(),
        label = "featureIconBg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (feature.checked) Color.White else feature.accent,
        animationSpec = MotionTokens.normal(),
        label = "featureIconTint"
    )
    val cardColor by animateColorAsState(
        targetValue = if (feature.checked) feature.accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = MotionTokens.normal(),
        label = "featureCardBg"
    )
    CardSurface(
        modifier = Modifier.fillMaxWidth(),
        color = cardColor,
        shape = RoundedCornerShape(18.dp),
        border = if (feature.checked) BorderStroke(1.5.dp, feature.accent.copy(alpha = 0.55f)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { feature.onToggle(!feature.checked) })
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(feature.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feature.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                VSpacer(2)
                Text(
                    text = feature.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.size(10.dp))
            V2Switch(checked = feature.checked, onCheckedChange = feature.onToggle)
        }
    }
}

/** Always-on anonymous diagnostics notice — collection is not optional (policy 2B). */
@Composable
private fun AnalyticsNoticeCard() {
    CardSurface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Insights,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.wizard_want_analytics),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                VSpacer(2)
                Text(
                    text = stringResource(R.string.wizard_want_analytics_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ReadyPage() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Connected,
            modifier = Modifier.size(88.dp)
        )
        VSpacer(24)
        Text(
            text = stringResource(R.string.wizard_ready_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        VSpacer(12)
        Text(
            text = stringResource(R.string.wizard_ready_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeWizardPreview() {
    V2RayEzTheme {
        WelcomeWizardScreen(viewModel = OnboardingViewModel())
    }
}
