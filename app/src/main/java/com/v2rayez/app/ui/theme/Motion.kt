package com.v2rayez.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf

/** Shared motion tokens — light, consistent, reduce-motion aware at call sites. */
object MotionTokens {
    const val DurationFast = 180
    const val DurationNormal = 280
    const val DurationSlow = 420

    val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> fast() = tween<T>(DurationFast, easing = EmphasizedEasing)
    fun <T> normal() = tween<T>(DurationNormal, easing = EmphasizedEasing)
    fun <T> slow() = tween<T>(DurationSlow, easing = EmphasizedEasing)
}

val LocalReduceMotion = staticCompositionLocalOf { false }
