package com.v2rayez.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.v2rayez.app.domain.model.ThroughputSample
import com.v2rayez.app.domain.model.TrafficPoint
import com.v2rayez.app.domain.model.UsageSlice
import com.v2rayez.app.ui.theme.ChartDownload
import com.v2rayez.app.ui.theme.ChartOther
import com.v2rayez.app.ui.theme.ChartUpload
import com.v2rayez.app.util.Formatters
import java.util.Locale

/** Grouped bar chart: two bars (download, upload) per label. */
@Composable
fun TrafficBarChart(
    points: List<TrafficPoint>,
    modifier: Modifier = Modifier,
    downloadColor: Color = ChartDownload,
    uploadColor: Color = ChartUpload
) {
    val maxValue = (points.flatMap { listOf(it.download, it.upload) }.maxOrNull() ?: 1f).coerceAtLeast(0.001f)
    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val slot = size.width / points.size
            val barW = (slot * 0.22f)
            val gap = barW * 0.4f
            points.forEachIndexed { i, p ->
                val centerX = slot * i + slot / 2
                val dh = (p.download / maxValue) * size.height
                val uh = (p.upload / maxValue) * size.height
                // download bar
                drawRoundedBar(
                    x = centerX - barW - gap / 2,
                    barWidth = barW,
                    barHeight = dh,
                    color = downloadColor
                )
                // upload bar
                drawRoundedBar(
                    x = centerX + gap / 2,
                    barWidth = barW,
                    barHeight = uh,
                    color = uploadColor
                )
            }
        }
        VSpacer(6)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            points.forEach {
                Text(it.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundedBar(
    x: Float,
    barWidth: Float,
    barHeight: Float,
    color: Color
) {
    val top = size.height - barHeight
    drawRoundRect(
        color = color,
        topLeft = Offset(x, top),
        size = Size(barWidth, barHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2)
    )
}

/**
 * Smooth dual-series area chart (download + upload) with gradient fills, a peak
 * value caption, gridlines and sparse X labels so it stays readable for 7–30 buckets.
 */
@Composable
fun TrafficAreaChart(
    points: List<TrafficPoint>,
    modifier: Modifier = Modifier,
    downloadColor: Color = MaterialTheme.colorScheme.primary,
    uploadColor: Color = ChartUpload
) {
    val maxValue = (points.flatMap { listOf(it.download, it.upload) }.maxOrNull() ?: 0f).coerceAtLeast(0.001f)
    val gridColor = MaterialTheme.colorScheme.outline
    val hasData = points.any { it.download > 0f || it.upload > 0f }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (hasData) "peak ${formatMb(maxValue)}" else "No traffic in this range",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        VSpacer(4)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        ) {
            // gridlines
            val gridLines = 4
            repeat(gridLines) { g ->
                val y = size.height / gridLines * g
                drawLine(gridColor.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            if (points.size < 2) return@Canvas
            val stepX = size.width / (points.size - 1)

            fun seriesPath(selector: (TrafficPoint) -> Float): Pair<Path, Path> {
                fun offset(i: Int): Offset {
                    val y = size.height - (selector(points[i]) / maxValue) * size.height * 0.9f - 8f
                    return Offset(stepX * i, y)
                }
                val line = Path().apply {
                    moveTo(offset(0).x, offset(0).y)
                    for (i in 1 until points.size) {
                        val prev = offset(i - 1)
                        val curr = offset(i)
                        val midX = (prev.x + curr.x) / 2
                        cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                    }
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                return line to fill
            }

            // Upload underneath, download on top.
            val (upLine, upFill) = seriesPath { it.upload }
            drawPath(upFill, Brush.verticalGradient(listOf(uploadColor.copy(alpha = 0.28f), uploadColor.copy(alpha = 0f))))
            drawPath(upLine, color = uploadColor, style = Stroke(width = 2.5.dp.toPx()))

            val (downLine, downFill) = seriesPath { it.download }
            drawPath(downFill, Brush.verticalGradient(listOf(downloadColor.copy(alpha = 0.35f), downloadColor.copy(alpha = 0f))))
            drawPath(downLine, color = downloadColor, style = Stroke(width = 3.dp.toPx()))
        }
        VSpacer(6)
        SparseAxisLabels(points.map { it.label })
    }
}

/** Renders up to ~6 evenly spaced X labels so dense series don't overlap. */
@Composable
private fun SparseAxisLabels(labels: List<String>) {
    if (labels.isEmpty()) return
    val maxLabels = 6
    val step = (labels.size + maxLabels - 1) / maxLabels
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { i, label ->
            val show = step <= 1 || i % step == 0 || i == labels.lastIndex
            Text(
                if (show) label else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatMb(mb: Float): String =
    if (mb >= 1024f) String.format(Locale.US, "%.1f GB", mb / 1024f)
    else String.format(Locale.US, "%.0f MB", mb)

/**
 * Live scrolling throughput graph (download + upload in bytes/sec) for the Home
 * screen while connected. Newest sample is on the right.
 */
@Composable
fun LiveThroughputChart(
    samples: List<ThroughputSample>,
    modifier: Modifier = Modifier,
    downloadColor: Color = ChartDownload,
    uploadColor: Color = ChartUpload
) {
    val maxValue = (samples.flatMap { listOf(it.downBps, it.upBps) }.maxOrNull() ?: 0L)
        .coerceAtLeast(1L)
    val gridColor = MaterialTheme.colorScheme.outline
    val latest = samples.lastOrNull()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendValue(downloadColor, "↓ " + Formatters.speed(latest?.downBps ?: 0L))
            LegendValue(uploadColor, "↑ " + Formatters.speed(latest?.upBps ?: 0L))
        }
        VSpacer(8)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val gridLines = 3
            repeat(gridLines) { g ->
                val y = size.height / gridLines * g
                drawLine(gridColor.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            if (samples.size < 2) return@Canvas
            val stepX = size.width / (samples.size - 1)

            fun draw(color: Color, selector: (ThroughputSample) -> Long) {
                fun offset(i: Int): Offset {
                    val y = size.height - (selector(samples[i]).toFloat() / maxValue) * size.height * 0.9f - 6f
                    return Offset(stepX * i, y)
                }
                val line = Path().apply {
                    moveTo(offset(0).x, offset(0).y)
                    for (i in 1 until samples.size) {
                        val prev = offset(i - 1)
                        val curr = offset(i)
                        val midX = (prev.x + curr.x) / 2
                        cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                    }
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f))))
                drawPath(line, color = color, style = Stroke(width = 2.5.dp.toPx()))
            }
            draw(uploadColor) { it.upBps }
            draw(downloadColor) { it.downBps }
        }
    }
}

@Composable
private fun LegendValue(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Donut chart with a legend to the right (Data Usage). */
@Composable
fun DonutChart(
    slices: List<UsageSlice>,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(ChartDownload, ChartUpload, ChartOther)
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(0.001f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(120.dp)) {
            var startAngle = -90f
            val stroke = 24.dp.toPx()
            val inset = stroke / 2
            slices.forEachIndexed { i, s ->
                val sweep = (s.value / total) * 360f
                drawArc(
                    color = colors[i % colors.size],
                    startAngle = startAngle,
                    sweepAngle = sweep - 3f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke)
                )
                startAngle += sweep
            }
        }
        HSpacer(20)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            slices.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colors[i % colors.size])
                    )
                    Text(s.valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(s.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Horizontal usage bar used in "Top Servers". */
@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.outline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}
