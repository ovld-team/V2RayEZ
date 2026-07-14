package com.v2rayez.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v2rayez.app.ui.theme.LocalReduceMotion
import com.v2rayez.app.ui.theme.MotionTokens

@Composable
fun V2Skeleton(
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp)
) {
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val reduce = LocalReduceMotion.current
    val brush = if (reduce) {
        Brush.linearGradient(listOf(base, base))
    } else {
        val t = rememberInfiniteTransition(label = "shimmer")
        val x by t.animateFloat(
            initialValue = 0f,
            targetValue = 1000f,
            animationSpec = infiniteRepeatable(
                animation = tween(MotionTokens.DurationSlow * 3, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmerX"
        )
        Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(x - 200f, 0f),
            end = Offset(x, 200f)
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(brush, shape)
    )
}
