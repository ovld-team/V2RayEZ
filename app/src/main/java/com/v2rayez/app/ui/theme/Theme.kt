package com.v2rayez.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Accent options exposed in Settings -> Color, mapped to a primary swatch. */
fun accentColor(name: String): Color = when (name) {
    "Blue" -> AccentBlue
    "Green" -> AccentGreen
    "Orange" -> AccentOrange
    "Pink" -> AccentPink
    else -> Violet
}

/** A two-stop gradient derived from the current accent/primary color. */
fun accentGradient(primary: Color): List<Color> =
    listOf(primary, lerp(primary, Color.Black, 0.24f))

private fun darkColors(primary: Color) = darkColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = VioletContainer,
    onPrimaryContainer = VioletLight,
    secondary = ChartUpload,
    onSecondary = Color(0xFF001018),
    tertiary = VioletLight,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceElevated,
    surfaceContainerHigh = SurfaceElevated2,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = ErrorRed,
    onError = Color.White
)

private fun lightColors(primary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primary.copy(alpha = 0.14f),
    onPrimaryContainer = primary,
    secondary = ChartUpload,
    onSecondary = Color.White,
    tertiary = VioletDeep,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceLightElevated,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainer = SurfaceLightElevated,
    surfaceContainerHigh = SurfaceLightElevated2,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun V2RayEzTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: String = "Purple",
    content: @Composable () -> Unit
) {
    val primary = accentColor(accent)
    MaterialTheme(
        colorScheme = if (darkTheme) darkColors(primary) else lightColors(primary),
        typography = V2Typography,
        shapes = V2Shapes,
        content = content
    )
}
