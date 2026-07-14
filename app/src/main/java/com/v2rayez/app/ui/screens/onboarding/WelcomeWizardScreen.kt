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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
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
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.V2RayEzTheme
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
                        analytics = settings.analyticsConsent,
                        onChange = { w, a -> viewModel.setWants(w, a) }
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

@Composable
private fun WantsPage(
    wants: OnboardingWants,
    analytics: Boolean,
    onChange: (OnboardingWants, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.wizard_wants_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        VSpacer(8)
        Text(
            text = stringResource(R.string.wizard_wants_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        VSpacer(16)
        WantRow(R.string.wizard_want_tor, wants.tor) {
            onChange(wants.copy(tor = it), analytics)
        }
        WantRow(R.string.wizard_want_mitm, wants.mitm) {
            onChange(wants.copy(mitm = it), analytics)
        }
        WantRow(R.string.wizard_want_browser, wants.browser) {
            onChange(wants.copy(browser = it), analytics)
        }
        WantRow(R.string.wizard_want_hotspot, wants.hotspot) {
            onChange(wants.copy(hotspot = it), analytics)
        }
        WantRow(R.string.wizard_want_cores, wants.processCores) {
            onChange(wants.copy(processCores = it), analytics)
        }
        WantRow(R.string.wizard_want_analytics, analytics) {
            onChange(wants.copy(analytics = it), it)
        }
    }
}

@Composable
private fun WantRow(labelRes: Int, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked,
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
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
