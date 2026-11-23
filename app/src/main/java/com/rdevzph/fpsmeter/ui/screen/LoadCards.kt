package com.rdevzph.fpsmeter.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rdevzph.fpsmeter.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// Tunable thresholds
private const val SPIKE_JUMP = 30   // a "big swing" = load jumps by this many points in one second
private const val BUSY_LEVEL = 90   // load at or above this counts as "busy"
private const val LOW_FPS = 30      // under this FPS counts as "low"

/** Which part of the timeline is visible, as fractions: 0..1 = everything. */
data class ChartZoom(val start: Float = 0f, val end: Float = 1f) {
    val span: Float get() = end - start
    val isZoomed: Boolean get() = span < 0.999f
}

/** One line (or set of bars) on a chart. NaN in values = no data for that second. */
private class ChartLine(
    val label: String,
    val color: Color,
    val values: List<Float>,
    val onRightAxis: Boolean = false,
    val bars: Boolean = false,
    val format: (Float) -> String
)

private data class LoadStats(val avg: Int, val peak: Int, val busyPercent: Float, val swings: Int)

// Samples below 0 mean "no data for that second" and are ignored.
private fun computeLoadStats(samples: List<Int>): LoadStats? {
    val valid = samples.filter { it >= 0 }
    if (valid.isEmpty()) return null
    var swings = 0
    var prev = -1
    for (v in samples) {
        if (v < 0) { prev = -1; continue }
        if (prev >= 0 && abs(v - prev) >= SPIKE_JUMP) swings++
        prev = v
    }
    val busy = valid.count { it >= BUSY_LEVEL } * 100f / valid.size
    return LoadStats(valid.average().toInt(), valid.maxOrNull() ?: 0, busy, swings)
}

private fun loadToFloats(samples: List<Int>): List<Float> =
    samples.map { if (it < 0) Float.NaN else it.toFloat() }

private fun loadTimeLabel(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

@Composable
fun FpsTemperatureZoomCard(
    stats: AnalyticsData,
    zoom: ChartZoom,
    onZoomChange: (ChartZoom) -> Unit
) {
    val batColor = Color(0xFFFFA000)
    val cpuColor = Color(0xFFFF5722)
    val gpuColor = Color(0xFFAB47BC)
    val fpsColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)

    val temps = stats.tempSamples
    val cpuT = stats.cpuTempSamples
    val gpuT = stats.gpuTempSamples
    val hasAnyTemp = temps.isNotEmpty() || cpuT.isNotEmpty() || gpuT.isNotEmpty()

    val fpsValues = remember(stats.fpsSamples) { stats.fpsSamples.map { it.toFloat() } }
    val maxFpsVal = max(120, ((stats.maxFps + 9) / 10) * 10).toFloat()
    val minTemp = 30f
    val peakTemp = maxOf(temps.maxOrNull() ?: 0f, cpuT.maxOrNull() ?: 0f, gpuT.maxOrNull() ?: 0f, 45f)
    val maxTemp = max(50f, peakTemp + 2f)
    val tempFormat: (Float) -> String = { v -> "${v.roundToInt()}°" }

    val lines = ArrayList<ChartLine>()
    lines.add(ChartLine("FPS", fpsColor, fpsValues, format = { "${it.roundToInt()} FPS" }))
    if (temps.isNotEmpty()) {
        lines.add(ChartLine("BAT", batColor, temps, onRightAxis = true, format = { String.format(Locale.US, "%.1f°C", it) }))
    }
    if (cpuT.isNotEmpty()) {
        lines.add(ChartLine("CPU", cpuColor, cpuT, onRightAxis = true, format = { String.format(Locale.US, "%.1f°C", it) }))
    }
    if (gpuT.isNotEmpty()) {
        lines.add(ChartLine("GPU", gpuColor, gpuT, onRightAxis = true, format = { String.format(Locale.US, "%.1f°C", it) }))
    }

    LoadCardShell(title = null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.analytics_fps_chart),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (hasAnyTemp) {
                Text(
                    text = stringResource(R.string.analytics_temp_chart),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ZoomChart(
            lines = lines,
            durationSeconds = stats.durationSeconds,
            zoom = zoom,
            onZoomChange = onZoomChange,
            leftMin = 0f,
            leftMax = maxFpsVal,
            leftFormat = { it.roundToInt().toString() },
            rightMin = minTemp,
            rightMax = maxTemp,
            rightFormat = if (hasAnyTemp) tempFormat else null,
            height = 175.dp,
            showHint = true
        )
        Spacer(Modifier.height(4.dp))
        LoadLegend(
            buildList {
                add("FPS" to fpsColor)
                if (temps.isNotEmpty()) add("BAT(°C)" to batColor)
                if (cpuT.isNotEmpty()) add("CPU(°C)" to cpuColor)
                if (gpuT.isNotEmpty()) add("GPU(°C)" to gpuColor)
            }
        )
    }
}

@Composable
fun FrameTimeZoomCard(
    stats: AnalyticsData,
    zoom: ChartZoom,
    onZoomChange: (ChartZoom) -> Unit
) {
    val frameTimes = remember(stats.fpsSamples) {
        stats.fpsSamples.map { if (it > 0) 1000f / it else 0f }
    }
    val lines = listOf(
        ChartLine(
            "Frame time", Color(0xFF64B5F6), frameTimes, bars = true,
            format = { String.format(Locale.US, "%.1f ms", it) }
        )
    )
    LoadCardShell(title = stringResource(R.string.analytics_frametime_chart)) {
        ZoomChart(
            lines = lines,
            durationSeconds = stats.durationSeconds,
            zoom = zoom,
            onZoomChange = onZoomChange,
            leftMin = 0f,
            leftMax = 50f,
            leftFormat = { it.roundToInt().toString() },
            leftRef = 16.67f,
            height = 145.dp
        )
    }
}

@Composable
fun CpuLoadCard(
    stats: AnalyticsData,
    zoom: ChartZoom,
    onZoomChange: (ChartZoom) -> Unit
) {
    val samples = stats.cpuLoadSamples
    val ls = remember(samples) { computeLoadStats(samples) }
    val values = remember(samples) { loadToFloats(samples) }
    val color = Color(0xFF00B0FF)
    LoadCardShell(title = "CPU Load") {
        if (ls == null) {
            NoLoadData(samples.isNotEmpty())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LoadTile("AVG", "${ls.avg}%", "load", color, Modifier.weight(1f))
                LoadTile("PEAK", "${ls.peak}%", "load", color, Modifier.weight(1f))
                LoadTile("TIME ≥90%", String.format(Locale.US, "%.0f%%", ls.busyPercent), "of session", color, Modifier.weight(1f))
                LoadTile(
                    "MAX TEMP",
                    if (stats.maxCpuTemp > 0f) String.format(Locale.US, "%.1f°C", stats.maxCpuTemp) else "N/A",
                    "CPU", Color(0xFFFF5722), Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            ZoomChart(
                lines = listOf(ChartLine("CPU", color, values, format = { "${it.roundToInt()}%" })),
                durationSeconds = stats.durationSeconds,
                zoom = zoom,
                onZoomChange = onZoomChange,
                leftMin = 0f,
                leftMax = 100f,
                leftFormat = { "${it.roundToInt()}%" }
            )
        }
    }
}

@Composable
fun GpuLoadCard(
    stats: AnalyticsData,
    zoom: ChartZoom,
    onZoomChange: (ChartZoom) -> Unit
) {
    val samples = stats.gpuLoadSamples
    val ls = remember(samples) { computeLoadStats(samples) }
    val values = remember(samples) { loadToFloats(samples) }
    val color = Color(0xFF00E676)
    LoadCardShell(title = "GPU Load (device-wide)") {
        if (ls == null) {
            NoLoadData(samples.isNotEmpty())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LoadTile("AVG", "${ls.avg}%", "load", color, Modifier.weight(1f))
                LoadTile("PEAK", "${ls.peak}%", "load", color, Modifier.weight(1f))
                LoadTile("TIME ≥90%", String.format(Locale.US, "%.0f%%", ls.busyPercent), "of session", color, Modifier.weight(1f))
                LoadTile("BIG SWINGS", "${ls.swings}", "${SPIKE_JUMP}+ pt jumps", color, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            ZoomChart(
                lines = listOf(ChartLine("GPU", color, values, format = { "${it.roundToInt()}%" })),
                durationSeconds = stats.durationSeconds,
                zoom = zoom,
                onZoomChange = onZoomChange,
                leftMin = 0f,
                leftMax = 100f,
                leftFormat = { "${it.roundToInt()}%" }
            )
        }
    }
}

@Composable
fun CombinedPerformanceCard(
    stats: AnalyticsData,
    zoom: ChartZoom,
    onZoomChange: (ChartZoom) -> Unit
) {
    val fps = stats.fpsSamples
    val cpu = stats.cpuLoadSamples
    val gpu = stats.gpuLoadSamples
    val cpuStats = remember(cpu) { computeLoadStats(cpu) }
    val gpuStats = remember(gpu) { computeLoadStats(gpu) }
    val fpsValues = remember(fps) { fps.map { it.toFloat() } }
    val cpuValues = remember(cpu) { loadToFloats(cpu) }
    val gpuValues = remember(gpu) { loadToFloats(gpu) }
    val cpuColor = Color(0xFF00B0FF)
    val gpuColor = Color(0xFF00E676)
    val fpsColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)

    LoadCardShell(title = "Everything: FPS + CPU + GPU") {
        if (cpuStats == null && gpuStats == null) {
            NoLoadData(cpu.isNotEmpty() || gpu.isNotEmpty())
        } else {
            val maxFpsVal = max(60, ((stats.maxFps + 9) / 10) * 10)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LoadTile("AVG FPS", "${stats.avgFps}", "FPS", fpsColor, Modifier.weight(1f))
                LoadTile("AVG CPU", cpuStats?.let { "${it.avg}%" } ?: "N/A", "load", cpuColor, Modifier.weight(1f))
                LoadTile("AVG GPU", gpuStats?.let { "${it.avg}%" } ?: "N/A", "load", gpuColor, Modifier.weight(1f))
                LoadTile("PEAK GPU", gpuStats?.let { "${it.peak}%" } ?: "N/A", "load", gpuColor, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))

            val lines = ArrayList<ChartLine>()
            lines.add(ChartLine("FPS", fpsColor, fpsValues, format = { "${it.roundToInt()} FPS" }))
            if (cpuStats != null) {
                lines.add(ChartLine("CPU", cpuColor, cpuValues, onRightAxis = true, format = { "${it.roundToInt()}%" }))
            }
            if (gpuStats != null) {
                lines.add(ChartLine("GPU", gpuColor, gpuValues, onRightAxis = true, format = { "${it.roundToInt()}%" }))
            }

            ZoomChart(
                lines = lines,
                durationSeconds = stats.durationSeconds,
                zoom = zoom,
                onZoomChange = onZoomChange,
                leftMin = 0f,
                leftMax = maxFpsVal.toFloat(),
                leftFormat = { it.roundToInt().toString() },
                rightMin = 0f,
                rightMax = 100f,
                rightFormat = { "${it.roundToInt()}%" }
            )
            Spacer(Modifier.height(4.dp))
            LoadLegend(
                buildList {
                    add("FPS" to fpsColor)
                    if (cpuStats != null) add("CPU %" to cpuColor)
                    if (gpuStats != null) add("GPU %" to gpuColor)
                }
            )

            val lowFps = fps.count { it in 1 until LOW_FPS }
            val lowWithGpu = (0 until minOf(fps.size, gpu.size)).count { fps[it] in 1 until LOW_FPS && gpu[it] >= BUSY_LEVEL }
            val lowWithCpu = (0 until minOf(fps.size, cpu.size)).count { fps[it] in 1 until LOW_FPS && cpu[it] >= BUSY_LEVEL }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LoadTile("UNDER $LOW_FPS FPS", "${lowFps}s", "total", fpsColor, Modifier.weight(1f))
                LoadTile("…WITH GPU ≥90%", "${lowWithGpu}s", "GPU busy", gpuColor, Modifier.weight(1f))
                LoadTile("…WITH CPU ≥90%", "${lowWithCpu}s", "CPU busy", cpuColor, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Seconds spent under $LOW_FPS FPS, and how many of those the GPU or CPU was at $BUSY_LEVEL% or more. A hint about where the limit is, not a verdict. GPU load is device-wide, not just this game.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun NoLoadData(hadSamples: Boolean) {
    Text(
        text = if (hadSamples) {
            "No readings for this selection. Either this phone can't provide this value, or the sessions were recorded before load tracking was added."
        } else {
            "No CPU/GPU load was recorded for this selection. Load is only saved for sessions recorded after this update, so record a new session to see it here."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LoadCardShell(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
private fun LoadTile(label: String, value: String, unit: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LoadLegend(items: List<Pair<String, Color>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { i, (label, c) ->
            if (i > 0) Spacer(Modifier.width(14.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The zoomable chart
// ---------------------------------------------------------------------------

@Composable
private fun ZoomChart(
    lines: List<ChartLine>,
    durationSeconds: Long,
    zoom: ChartZoom,
    onZoomChange: (ChartZoom) -> Unit,
    leftMin: Float,
    leftMax: Float,
    leftFormat: (Float) -> String,
    rightMin: Float = 0f,
    rightMax: Float = 1f,
    rightFormat: ((Float) -> String)? = null,
    leftRef: Float? = null,
    height: Dp = 150.dp,
    showHint: Boolean = false
) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1f) }

    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val indicatorColor = MaterialTheme.colorScheme.primary

    val hasRight = rightFormat != null
    val leftPad = 32f
    val rightPad = if (hasRight) 32f else 12f
    val topPad = 12f
    val bottomPad = 12f

    val maxN = lines.maxOfOrNull { it.values.size } ?: 0
    val minSpan = if (maxN > 0) (12f / maxN).coerceIn(0.01f, 1f) else 1f

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .chartGestures(zoom, minSpan, onZoomChange) { touchX = it }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cw = w - leftPad - rightPad
                val ch = h - topPad - bottomPad
                val span = zoom.span.coerceAtLeast(0.0001f)

                for (i in 0..4) {
                    val y = topPad + ch * (i / 4f)
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPad, y),
                        end = Offset(w - rightPad, y),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                }

                if (leftRef != null) {
                    val f = ((leftRef - leftMin) / max(0.001f, leftMax - leftMin)).coerceIn(0f, 1f)
                    val y = topPad + ch * (1f - f)
                    drawLine(
                        color = Color(0xFF4CAF50).copy(alpha = 0.6f),
                        start = Offset(leftPad, y),
                        end = Offset(w - rightPad, y),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                }

                clipRect(left = leftPad, top = topPad, right = w - rightPad, bottom = topPad + ch) {
                    lines.forEach { line ->
                        val n = line.values.size
                        if (n == 0) return@forEach
                        val axisMin = if (line.onRightAxis) rightMin else leftMin
                        val axisMax = if (line.onRightAxis) rightMax else leftMax
                        val range = max(0.001f, axisMax - axisMin)

                        if (line.bars) {
                            val slot = cw / (span * n)
                            val from = floor(zoom.start * n).toInt().coerceIn(0, n - 1)
                            val to = ceil(zoom.end * n).toInt().coerceIn(0, n - 1)
                            for (i in from..to) {
                                val v = line.values[i]
                                if (v.isNaN()) continue
                                val norm = ((v - axisMin) / range).coerceIn(0f, 1f)
                                val x = leftPad + (i.toFloat() / n - zoom.start) / span * cw
                                val barH = ch * norm
                                drawRect(
                                    color = line.color,
                                    topLeft = Offset(x, topPad + ch - barH),
                                    size = Size(max(1f, slot - 0.5f), barH)
                                )
                            }
                        } else {
                            val from = (floor(zoom.start * (n - 1)).toInt() - 1).coerceIn(0, n - 1)
                            val to = (ceil(zoom.end * (n - 1)).toInt() + 1).coerceIn(0, n - 1)
                            val path = Path()
                            var penDown = false
                            for (i in from..to) {
                                val v = line.values[i]
                                if (v.isNaN()) {
                                    penDown = false
                                    continue
                                }
                                val frac = if (n > 1) i.toFloat() / (n - 1) else 0f
                                val x = leftPad + (frac - zoom.start) / span * cw
                                val y = topPad + ch * (1f - ((v - axisMin) / range).coerceIn(0f, 1f))
                                if (penDown) {
                                    path.lineTo(x, y)
                                } else {
                                    path.moveTo(x, y)
                                    penDown = true
                                }
                            }
                            drawPath(
                                path = path,
                                color = line.color,
                                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }
                }

                touchX?.let { tx ->
                    val x = tx.coerceIn(leftPad, w - rightPad)
                    drawLine(
                        color = indicatorColor,
                        start = Offset(x, topPad),
                        end = Offset(x, topPad + ch),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
            }

            // Left axis labels
            Column(
                modifier = Modifier.fillMaxHeight().padding(vertical = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                for (i in 0..4) {
                    val v = leftMax - (leftMax - leftMin) * i / 4f
                    Text(
                        text = leftFormat(v),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = labelColor
                    )
                }
            }

            // Right axis labels
            if (rightFormat != null) {
                Column(
                    modifier = Modifier.fillMaxHeight().align(Alignment.TopEnd).padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    for (i in 0..4) {
                        val v = rightMax - (rightMax - rightMin) * i / 4f
                        Text(
                            text = rightFormat(v),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = labelColor
                        )
                    }
                }
            }

            // Touch tooltip
            touchX?.let { tx ->
                val plotW = (widthPx - leftPad - rightPad).coerceAtLeast(1f)
                val frac = ((tx - leftPad) / plotW).coerceIn(0f, 1f)
                val pos = zoom.start + frac * zoom.span
                val seconds = (pos * durationSeconds).toLong()
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    shadowElevation = 3.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = loadTimeLabel(seconds),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        lines.forEach { line ->
                            val n = line.values.size
                            if (n > 0) {
                                val idx = (if (line.bars) (pos * n).toInt() else (pos * (n - 1)).roundToInt())
                                    .coerceIn(0, n - 1)
                                val v = line.values[idx]
                                if (!v.isNaN()) {
                                    Text(
                                        text = "${line.label}: ${line.format(v)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = line.color
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Time labels for the visible window
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val t0 = zoom.start * durationSeconds
            val t1 = zoom.end * durationSeconds
            for (k in 0..4) {
                val t = (t0 + (t1 - t0) * k / 4f).toLong()
                Text(
                    text = if (t == 0L) "0" else loadTimeLabel(t),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = labelColor
                )
            }
        }

        if (zoom.isZoomed) {
            Text(
                text = "Zoomed ×" + String.format(Locale.US, "%.1f", 1f / zoom.span) + " · double-tap to reset",
                modifier = Modifier.clickable { onZoomChange(ChartZoom()) }.padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (showHint) {
            Text(
                text = "Pinch with two fingers to zoom · drag with two fingers to move · double-tap to reset",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
        }
    }
}

/**
 * Touch handling for a chart:
 *  - one finger: shows the tooltip (a mostly vertical drag is left to the page, so it still scrolls)
 *  - two fingers: pinch to zoom, drag to move along the timeline
 *  - double-tap: reset zoom
 */
@Composable
private fun Modifier.chartGestures(
    zoom: ChartZoom,
    minSpan: Float,
    onZoomChange: (ChartZoom) -> Unit,
    onTouch: (Float?) -> Unit
): Modifier {
    val zoomNow by rememberUpdatedState(zoom)
    val minSpanNow by rememberUpdatedState(minSpan)
    val changeNow by rememberUpdatedState(onZoomChange)
    val touchNow by rememberUpdatedState(onTouch)

    return this.pointerInput(Unit) {
        var lastTapTime = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downTime = System.currentTimeMillis()
            val slop = viewConfiguration.touchSlop
            var multiTouch = false
            var scrolling = false
            var moved = false
            touchNow(down.position.x)

            do {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2) {
                    multiTouch = true
                    touchNow(null)
                    val rawZoom = event.calculateZoom()
                    val zc = if (rawZoom.isNaN() || rawZoom <= 0f) 1f else rawZoom
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val z = zoomNow
                    val span = z.end - z.start
                    val newSpan = (span / zc).coerceIn(minSpanNow, 1f)
                    val focusFrac = if (centroid.x.isNaN()) 0.5f else (centroid.x / w).coerceIn(0f, 1f)
                    val focusPos = z.start + focusFrac * span
                    val newStart = (focusPos - focusFrac * newSpan - pan.x / w * newSpan)
                        .coerceIn(0f, 1f - newSpan)
                    changeNow(ChartZoom(newStart, newStart + newSpan))
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } else if (!multiTouch && !scrolling && pressedCount == 1) {
                    val c = event.changes.first { it.pressed }
                    val d = c.position - down.position
                    if (abs(d.x) > slop || abs(d.y) > slop) moved = true
                    if (abs(d.y) > slop && abs(d.y) > abs(d.x)) {
                        scrolling = true
                        touchNow(null)
                    } else {
                        touchNow(c.position.x)
                        if (abs(d.x) > slop) c.consume()
                    }
                }
            } while (event.changes.any { it.pressed })

            touchNow(null)
            val now = System.currentTimeMillis()
            if (!multiTouch && !moved && now - downTime < 250L) {
                if (now - lastTapTime < 350L) {
                    changeNow(ChartZoom())
                    lastTapTime = 0L
                } else {
                    lastTapTime = now
                }
            }
        }
    }
}