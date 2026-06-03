package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.HourlyForecast
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Smooth hourly temperature curve for the desktop popup.
 *
 * Faithfully reuses the Android widget's temperature→color model and gradient logic from
 * `TemperatureGraphStyle` (cold #5AC8FA ≤50°F, mild #E8A24E @70°F, hot #FF6B35 ≥90°F, blended in
 * between), but draws through Compose's Skia-backed DrawScope rather than android.graphics. The
 * curve smoothing is the same Catmull-Rom approach used by the widget's GraphRenderUtils.
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
    currentTemp: Float? = null,
    modifier: Modifier = Modifier,
    hoursAhead: Int = 48,
) {
    val textMeasurer = rememberTextMeasurer()
    val now = System.currentTimeMillis()
    val cutoff = now + hoursAhead * 3_600_000L
    
    // Filter and sort points for the display window.
    val points = remember(hourly, hoursAhead) {
        hourly.filter { it.dateTime in (now - 3_600_000L)..cutoff }
            .sortedBy { it.dateTime }
            .ifEmpty { hourly.sortedBy { it.dateTime }.take(hoursAhead) }
    }

    // Pre-load painters in a way that works with Compose's rules.
    val iconSpacing = if (points.size > 24) 4 else if (points.size > 12) 3 else 2
    val painters = mutableListOf<Painter?>()
    for (i in points.indices) {
        val p = points[i]
        if (i % iconSpacing == 0) {
            painters.add(painterResource(WeatherIcon.getIconResource(p.condition)))
        } else {
            painters.add(null)
        }
    }

    if (points.size < 2) return

    Canvas(modifier = modifier) {
        val temps = points.map { it.temperature }
        val rawMin = temps.min()
        val rawMax = temps.max()
        // Pad the range for labels and breathing room.
        val pad = ((rawMax - rawMin) * 0.25f).coerceAtLeast(2f)
        val minTemp = rawMin - pad
        val maxTemp = rawMax + pad
        val range = (maxTemp - minTemp).coerceAtLeast(1f)

        val w = size.width
        val h = size.height
        val n = points.size

        fun xAt(i: Int): Float = if (n == 1) 0f else w * i / (n - 1)
        fun yAt(t: Float): Float = h * (1f - (t - minTemp) / range)

        val coords = points.mapIndexed { i, p -> Offset(xAt(i), yAt(p.temperature)) }

        drawCloudAndPrecipOverlays(points, ::xAt)

        // 1. Draw the Curve and Gradient Fill
        drawCurve(coords, minTemp, maxTemp, range)

        // 2. Identify and Draw Peak Labels (High/Low/Now)
        val highIdx = temps.indexOf(rawMax)
        val lowIdx = temps.indexOf(rawMin)
        val nowIdx = points.indexOfByClosestTime(now)

        val labels = mutableListOf<Triple<Int, String, Color>>()
        labels.add(Triple(highIdx, "${rawMax.roundToInt()}°", Color.White))
        labels.add(Triple(lowIdx, "${rawMin.roundToInt()}°", Color.White))
        if (nowIdx != highIdx && nowIdx != lowIdx) {
            labels.add(Triple(nowIdx, "${points[nowIdx].temperature.roundToInt()}°", Color.White.copy(alpha = 0.8f)))
        }

        val markerTemp = currentTemp ?: points[nowIdx].temperature
        val markerX = xAt(nowIdx)
        val markerY = yAt(markerTemp.coerceIn(minTemp, maxTemp))
        drawLine(
            color = Color.White.copy(alpha = 0.36f),
            start = Offset(markerX, 0f),
            end = Offset(markerX, h - 44f),
            strokeWidth = 1.5f,
        )
        drawCircle(
            color = Color.White,
            radius = 4.5f,
            center = Offset(markerX, markerY),
        )
        drawCircle(
            color = tempToColor(markerTemp),
            radius = 2.5f,
            center = Offset(markerX, markerY),
        )

        // Simple collision avoidance for labels
        val drawnLabels = mutableListOf<Rect>()
        labels.sortByDescending { it.first == highIdx || it.first == lowIdx } // Prioritize High/Low
        
        for ((idx, text, color) in labels) {
            val point = coords[idx]
            val textLayout = textMeasurer.measure(text, TextStyle(fontSize = 11.sp, color = color))
            val isHigh = idx == highIdx
            
            // Position above if it's a peak, below if it's a valley
            val topOffset = if (isHigh) -18f else 4f
            val labelRect = Rect(
                offset = Offset(point.x - textLayout.size.width / 2f, point.y + topOffset),
                size = androidx.compose.ui.geometry.Size(textLayout.size.width.toFloat(), textLayout.size.height.toFloat())
            )

            // Avoid overlap
            if (drawnLabels.none { it.overlaps(labelRect.inflate(4f)) }) {
                drawText(textLayout, topLeft = labelRect.topLeft)
                drawnLabels.add(labelRect)
            }
        }

        // 3. Draw Bottom Icons and Time Labels (Selective to avoid clutter)
        for (i in 0 until n step iconSpacing) {
            val p = points[i]
            val x = xAt(i)
            
            // Icon
            painters[i]?.let { painter ->
                val iconSize = 18.dp.toPx()
                translate(x - iconSize / 2f, h - 38f) {
                    with(painter) {
                        draw(size = androidx.compose.ui.geometry.Size(iconSize, iconSize))
                    }
                }
            }
            
            // Time (HH:mm)
            val time = java.time.Instant.ofEpochMilli(p.dateTime)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalTime()
            val timeStr = "%02d:00".format(time.hour)
            val timeLayout = textMeasurer.measure(timeStr, TextStyle(fontSize = 9.sp, color = Color.Gray))
            drawText(timeLayout, topLeft = Offset(x - timeLayout.size.width / 2f, h - 14f))
        }
    }
}

private fun DrawScope.drawCloudAndPrecipOverlays(points: List<HourlyForecast>, xAt: (Int) -> Float) {
    if (points.isEmpty()) return
    val bandBottom = size.height - 44f
    val cloudHeight = bandBottom.coerceAtLeast(0f)
    val stepWidth = if (points.size > 1) size.width / (points.size - 1) else size.width
    points.forEachIndexed { i, p ->
        val left = (xAt(i) - stepWidth / 2f).coerceAtLeast(0f)
        val right = (xAt(i) + stepWidth / 2f).coerceAtMost(size.width)
        p.cloudCover?.let { cover ->
            val alpha = (cover.coerceIn(0, 100) / 100f) * 0.22f
            drawRect(
                color = Color(0xFFB8C7D9).copy(alpha = alpha),
                topLeft = Offset(left, 0f),
                size = Size((right - left).coerceAtLeast(1f), cloudHeight),
            )
        }
        val precipSignal = maxOf(
            p.precipProbability?.toFloat()?.div(100f) ?: 0f,
            p.precipAmountMm?.let { (it / 6f).coerceIn(0f, 1f) } ?: 0f,
        )
        if (precipSignal > 0f) {
            val barHeight = (precipSignal * 18f).coerceAtLeast(2f)
            drawLine(
                color = Color(0xFF4FC3F7).copy(alpha = 0.35f + 0.45f * precipSignal),
                start = Offset(xAt(i), bandBottom - barHeight),
                end = Offset(xAt(i), bandBottom),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun DrawScope.drawCurve(coords: List<Offset>, minTemp: Float, maxTemp: Float, range: Float) {
    val h = size.height
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

    val fill = Path().apply {
        addPath(line)
        lineTo(coords.last().x, h)
        lineTo(coords.first().x, h)
        close()
    }

    fun posOf(t: Float): Float = ((maxTemp - t) / range).coerceIn(0f, 1f)
    val stops = buildList {
        add(0f to tempToColor(maxTemp))
        add(1f to tempToColor(minTemp))
        for (t in listOf(HOT_THRESHOLD, MILD_TEMP, COLD_THRESHOLD)) {
            if (t > minTemp && t < maxTemp) add(posOf(t) to tempToColor(t))
        }
    }.sortedBy { it.first }.distinctBy { (it.first * 1000).toInt() }

    val strokeStops = stops.map { it.first to it.second }.toTypedArray()
    val fillStops = stops.map { (pos, color) -> pos to color.copy(alpha = 0.40f * (1f - pos)) }.toTypedArray()

    drawPath(fill, brush = Brush.verticalGradient(colorStops = fillStops, startY = 0f, endY = h))
    drawPath(
        line,
        brush = Brush.verticalGradient(colorStops = strokeStops, startY = 0f, endY = h),
        style = Stroke(width = 3f),
    )
}

private fun List<HourlyForecast>.indexOfByClosestTime(targetTime: Long): Int {
    var minDiff = Long.MAX_VALUE
    var closestIdx = 0
    forEachIndexed { index, forecast ->
        val diff = abs(forecast.dateTime - targetTime)
        if (diff < minDiff) {
            minDiff = diff
            closestIdx = index
        }
    }
    return closestIdx
}
