package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.weatherwidget.data.model.HourlyForecast

/**
 * Smooth hourly temperature curve for the desktop popup.
 *
 * Faithfully reuses the Android widget's temperature→color model and gradient logic from
 * `TemperatureGraphStyle` (cold #5AC8FA ≤50°F, mild #E8A24E @70°F, hot #FF6B35 ≥90°F, blended in
 * between), but draws through Compose's Skia-backed DrawScope rather than android.graphics. The
 * curve smoothing is the same Catmull-Rom approach used by the widget's GraphRenderUtils.
 *
 * Scope: MVP curve only. The widget's multi-day labels, forecast/accuracy overlays, and "now"
 * marker are intentionally not ported here yet.
 */
private val COLOR_COLD = Color(0xFF5AC8FA)
private val COLOR_MILD = Color(0xFFE8A24E)
private val COLOR_HOT = Color(0xFFFF6B35)

private const val COLD_THRESHOLD = 50f
private const val MILD_TEMP = 70f
private const val HOT_THRESHOLD = 90f

private fun tempToColor(temp: Float): Color = when {
    temp <= COLD_THRESHOLD -> COLOR_COLD
    temp >= HOT_THRESHOLD -> COLOR_HOT
    temp <= MILD_TEMP -> lerp(COLOR_COLD, COLOR_MILD, (temp - COLD_THRESHOLD) / (MILD_TEMP - COLD_THRESHOLD))
    else -> lerp(COLOR_MILD, COLOR_HOT, (temp - MILD_TEMP) / (HOT_THRESHOLD - MILD_TEMP))
}

@Composable
fun TemperatureGraph(
    hourly: List<HourlyForecast>,
    modifier: Modifier = Modifier,
    hoursAhead: Int = 48,
) {
    // Window to the upcoming hours, sorted by time.
    val now = System.currentTimeMillis()
    val cutoff = now + hoursAhead * 3_600_000L
    val points = hourly
        .filter { it.dateTime in (now - 3_600_000L)..cutoff }
        .sortedBy { it.dateTime }
        .ifEmpty { hourly.sortedBy { it.dateTime }.take(hoursAhead) }

    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        drawTemperatureCurve(points)
    }
}

private fun DrawScope.drawTemperatureCurve(points: List<HourlyForecast>) {
    val temps = points.map { it.temperature }
    val rawMin = temps.min()
    val rawMax = temps.max()
    // Pad the range slightly so the curve isn't flush against the edges.
    val pad = ((rawMax - rawMin) * 0.15f).coerceAtLeast(1f)
    val minTemp = rawMin - pad
    val maxTemp = rawMax + pad
    val range = (maxTemp - minTemp).coerceAtLeast(1f)

    val w = size.width
    val h = size.height
    val n = points.size

    fun xAt(i: Int): Float = if (n == 1) 0f else w * i / (n - 1)
    fun yAt(t: Float): Float = h * (1f - (t - minTemp) / range) // mirrors TemperatureGraphStyle.tempToY

    val coords = points.mapIndexed { i, p -> Offset(xAt(i), yAt(p.temperature)) }

    // Catmull-Rom -> cubic bezier for a smooth line (same technique as the widget renderer).
    val line = Path().apply {
        moveTo(coords[0].x, coords[0].y)
        for (i in 0 until coords.size - 1) {
            val p0 = coords[if (i == 0) 0 else i - 1]
            val p1 = coords[i]
            val p2 = coords[i + 1]
            val p3 = coords[if (i + 2 <= coords.size - 1) i + 2 else coords.size - 1]
            val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
            val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
            cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
        }
    }

    // Closed path for the gradient fill beneath the curve.
    val fill = Path().apply {
        addPath(line)
        lineTo(coords.last().x, h)
        lineTo(coords.first().x, h)
        close()
    }

    // Vertical gradient keyed to temperature: top = hottest color, bottom = coldest, with
    // threshold stops — same construction as TemperatureGraphStyle.buildTempGradient.
    fun posOf(t: Float): Float = ((maxTemp - t) / range).coerceIn(0f, 1f)
    val stops = buildList {
        add(0f to tempToColor(maxTemp))
        add(1f to tempToColor(minTemp))
        for (t in listOf(HOT_THRESHOLD, MILD_TEMP, COLD_THRESHOLD)) {
            if (t > minTemp && t < maxTemp) add(posOf(t) to tempToColor(t))
        }
    }.sortedBy { it.first }.distinctBy { "%.4f".format(it.first) }

    val strokeStops = stops.map { it.first to it.second }.toTypedArray()
    val fillStops = stops.map { (pos, color) -> pos to color.copy(alpha = 0.40f * (1f - pos)) }.toTypedArray()

    drawPath(fill, brush = Brush.verticalGradient(colorStops = fillStops, startY = 0f, endY = h))
    drawPath(
        line,
        brush = Brush.verticalGradient(colorStops = strokeStops, startY = 0f, endY = h),
        style = Stroke(width = 3f),
    )
}
