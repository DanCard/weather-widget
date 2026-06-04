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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import java.time.format.TextStyle as JavaTextStyle

/**
 * Smooth hourly temperature curve for the desktop popup.
 *
 * Faithfully reuses the Android widget's temperature→color model and gradient logic from
 * `TemperatureGraphStyle` (cold #5AC8FA ≤50°F, mild #E8A24E @70°F, hot #FF6B35 ≥90°F, blended in
 * between), but draws through Compose's Skia-backed DrawScope rather than android.graphics. The
 * curve smoothing is the same Catmull-Rom approach used by the widget's GraphRenderUtils.
 *
 * When [observations] are provided, past hours show a solid pink actual line (matching Android's
 * OBSERVED color) and future hours show a dashed forecast line. Without observations the full curve
 * is drawn solid.
 */
private val COLOR_COLD = Color(0xFF5AC8FA)
private val COLOR_MILD = Color(0xFFE8A24E)
private val COLOR_HOT = Color(0xFFFF6B35)
private val COLOR_ACTUAL = Color(0xFFFF3366) // matches Android TemperatureGraphStyle.OBSERVED

private val FORECAST_SUNNY = Color(0xFFF4C542)
private val FORECAST_CLOUDY = Color(0xFF8E99A4)
private val FORECAST_RAINY = Color(0xFF5A8FBF)
private val FORECAST_NIGHT = Color(0xFFBBBBBB)
private val FORECAST_TWILIGHT = Color(0xFFFFA726)

private fun forecastColor(flags: WeatherIcon.ConditionFlags): Color {
    return when {
        flags.isRainy -> FORECAST_RAINY
        flags.isNight -> FORECAST_NIGHT
        flags.isTwilight && flags.isSunny -> FORECAST_TWILIGHT
        flags.isMixed -> FORECAST_SUNNY
        flags.isSunny -> FORECAST_SUNNY
        else -> FORECAST_CLOUDY
    }
}

private const val COLD_THRESHOLD = 50f
private const val MILD_TEMP = 70f
private const val HOT_THRESHOLD = 90f
private const val WIDE_BACK_HOURS = 12
private const val WIDE_FORWARD_HOURS = 12
private const val ACTUALS_CONTEXT_LOOKBACK_HOURS = 72L
private const val ACTUALS_CONTEXT_LOOKAHEAD_HOURS = 60L
private const val WIDE_LABEL_INTERVAL = 4
private const val NARROW_WIDE_LABEL_INTERVAL = 6
private const val NARROW_WIDTH_PX = 420f

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
    currentObservedAt: Long? = null,
    observations: List<ObservationReading> = emptyList(),
    displaySourceId: String = "NWS",
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    modifier: Modifier = Modifier,
    centerOffsetHours: Int = 0,
    zoomLevel: String = "WIDE",
    onViewModeChange: (String) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val now = System.currentTimeMillis()
    val center = now + centerOffsetHours * 3_600_000L
    
    val backHours = if (zoomLevel == "NARROW") 2 else WIDE_BACK_HOURS
    val forwardHours = if (zoomLevel == "NARROW") 2 else WIDE_FORWARD_HOURS
    
    val start = center - backHours * 3_600_000L
    val cutoff = center + forwardHours * 3_600_000L

    val points = remember(hourly, centerOffsetHours, zoomLevel) {
        hourly.filter { it.dateTime in (start - 3_600_000L)..cutoff }
            .sortedBy { it.dateTime }
            .ifEmpty { hourly.sortedBy { it.dateTime }.take(backHours + forwardHours + 1) }
    }

    val iconSpacing = if (points.size > 24) 4 else if (points.size > 12) 3 else 2
    val painters = mutableListOf<Painter?>()
    for (i in points.indices) {
        painters.add(if (i % iconSpacing == 0) painterResource(WeatherIcon.getIconResource(points[i].condition)) else null)
    }

    val smoothIterations = if (zoomLevel == "NARROW") 1 else 3

    if (points.size < 2) return

    Canvas(
        modifier = modifier.pointerInput(points, zoomLevel) {
            detectTapGestures { offset ->
                if (offset.y >= size.height - 44.dp.toPx()) {
                    val stepWidth = size.width / (points.size - 1).coerceAtLeast(1)
                    val index = (offset.x / stepWidth).roundToInt().coerceIn(0, points.lastIndex)
                    val clickedPoint = points[index]
                    val iconRes = WeatherIcon.getIconResource(clickedPoint.condition)
                    val targetView = WeatherIcon.resolveIconHome(iconRes)
                    onViewModeChange(targetView)
                }
            }
        }
    ) {
        val windowStart = start
        val windowEnd = cutoff
        val windowSpan = (windowEnd - windowStart).coerceAtLeast(1L).toFloat()

        val zoneId = ZoneId.systemDefault()
        val actualSeries = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = hourly,
            observations = observations,
            centerTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(center), zoneId),
            displaySourceId = displaySourceId,
            userLat = latitude,
            userLon = longitude,
            backHours = backHours.toLong(),
            forwardHours = forwardHours.toLong(),
            contextLookbackHours = ACTUALS_CONTEXT_LOOKBACK_HOURS,
            contextLookaheadHours = ACTUALS_CONTEXT_LOOKAHEAD_HOURS,
            now = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId),
            zoneId = zoneId,
            smoothedForecasts = null,
        )
        val transitionMs = currentObservedAt
            ?: actualSeries.points.lastOrNull { it.isObservedActual }?.timeMs
            ?: actualSeries.points.lastOrNull { it.isActual }?.timeMs
        val actualLinePoints = actualSeries.points
            .filter { point ->
                point.isActual &&
                    point.actualTemp != null &&
                    transitionMs != null &&
                    point.timeMs <= transitionMs
            }

        val lastActualPoint = actualSeries.points.lastOrNull { it.isActual && it.actualTemp != null }
        val appliedDelta = if (lastActualPoint != null) {
            lastActualPoint.actualTemp!! - lastActualPoint.forecastTemp
        } else {
            0f
        }

        val rawForecastTemps = points.map { it.temperature }
        val forecastTemps = com.weatherwidget.shared.util.TemperatureInterpolator.smoothValuesPreservingAllExtrema(rawForecastTemps, smoothIterations)
        val expectedTemps = forecastTemps.map { it + appliedDelta }
        val allTemps = forecastTemps + actualLinePoints.mapNotNull { it.actualTemp } + expectedTemps
        val rawMin = allTemps.minOrNull() ?: 0f
        val rawMax = allTemps.maxOrNull() ?: 100f
        val fMin = forecastTemps.minOrNull() ?: 0f
        val fMax = forecastTemps.maxOrNull() ?: 100f
        val pad = ((rawMax - rawMin) * 0.25f).coerceAtLeast(2f)
        val minTemp = rawMin - pad
        val maxTemp = rawMax + pad
        val range = (maxTemp - minTemp).coerceAtLeast(1f)

        val w = size.width
        val h = size.height
        val n = points.size

        fun xAtTime(t: Long): Float = ((t - windowStart).toFloat() / windowSpan * w).coerceIn(0f, w)
        fun xAt(i: Int): Float = xAtTime(points[i].dateTime)
        fun yAt(t: Float): Float = h * (1f - (t - minTemp) / range)

        val coords = points.mapIndexed { i, _ -> Offset(xAt(i), yAt(forecastTemps[i])) }
        val expectedCoords = points.mapIndexed { i, _ -> Offset(xAt(i), yAt(forecastTemps[i] + appliedDelta)) }

        fun getCurveYAtX(x: Float): Float {
            if (coords.isEmpty()) return 0f
            if (x <= coords.first().x) return coords.first().y
            if (x >= coords.last().x) return coords.last().y
            val nextIdx = coords.indexOfFirst { it.x > x }
            if (nextIdx == -1 || nextIdx == 0) return coords.last().y
            val before = coords[nextIdx - 1]
            val after = coords[nextIdx]
            val t = (x - before.x) / (after.x - before.x)
            return before.y + (after.y - before.y) * t
        }

        drawCloudAndPrecipOverlays(points, ::xAt)

        // Gradient fill always spans the full window under the expected curve
        drawFill(expectedCoords, minTemp, maxTemp, range)

        // Lines:
        // 1. Forecast Line segments (Dashed and colored by weather condition)
        if (coords.size >= 2) {
            val tangents = computeTangents(coords)
            for (i in 0 until coords.size - 1) {
                val p = points[i + 1]
                val localZdt = Instant.ofEpochMilli(p.dateTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val sunInfo = com.weatherwidget.util.SunPositionUtils.getSunInfo(localZdt, latitude, longitude)
                val flags = WeatherIcon.getConditionFlags(p.condition, isNight = sunInfo.isNight).copy(
                    isTwilight = sunInfo.phase == com.weatherwidget.util.SunPhase.TWILIGHT
                )
                val segmentColor = forecastColor(flags)
                val segmentPath = Path().apply {
                    moveTo(coords[i].x, coords[i].y)
                    val cp1x = coords[i].x + tangents[i].x / 3f
                    val cp1y = coords[i].y + tangents[i].y / 3f
                    val cp2x = coords[i + 1].x - tangents[i + 1].x / 3f
                    val cp2y = coords[i + 1].y - tangents[i + 1].y / 3f
                    cubicTo(cp1x, cp1y, cp2x, cp2y, coords[i + 1].x, coords[i + 1].y)
                }
                drawPath(
                    path = segmentPath,
                    color = segmentColor,
                    style = Stroke(
                        width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()))
                    )
                )
            }
        }

        // 2. Ghost Line (Shifted forecast curve representing expected path)
        val transitionX = lastActualPoint?.let { xAtTime(it.timeMs) }
        if (transitionX != null && abs(appliedDelta) >= 0.1f) {
            clipRect(left = transitionX, top = 0f, right = w, bottom = h) {
                val expectedPath = buildCurve(expectedCoords)
                drawPath(
                    path = expectedPath,
                    color = Color.White.copy(alpha = 0.22f),
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 4.dp.toPx()))
                    )
                )
            }
        }

        // 3. Actual (Solid Pink) for observations
        if (actualLinePoints.size >= 2) {
            val obsCoords = actualLinePoints.map { point -> Offset(xAtTime(point.timeMs), yAt(point.actualTemp!!)) }
            drawActualLine(obsCoords)
        }

        // "Now" marker: vertical guide (dashed) + target circle
        val nowIdx = points.indexOfByClosestTime(now)
        val markerTemp = currentTemp ?: forecastTemps[nowIdx]
        val markerX = xAtTime(now)
        val markerY = yAt(markerTemp.coerceIn(minTemp, maxTemp))
        if (now in windowStart..windowEnd) {
            drawLine(
                color = Color.White.copy(alpha = 0.36f),
                start = Offset(markerX, 0f),
                end = Offset(markerX, h - 44f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            )
            drawCircle(color = Color.White, radius = 4.5f, center = Offset(markerX, markerY))
            drawCircle(color = tempToColor(markerTemp), radius = 2.5f, center = Offset(markerX, markerY))
        }

        // Peak labels (Hi / Lo / Now) anchored to forecast extremes
        val highIdx = forecastTemps.indexOf(fMax)
        val lowIdx = forecastTemps.indexOf(fMin)
        val labels = mutableListOf<Triple<Int, String, Color>>()
        labels.add(Triple(highIdx, "${fMax.roundToInt()}°", Color.White))
        labels.add(Triple(lowIdx, "${fMin.roundToInt()}°", Color.White))
        if (now in windowStart..windowEnd && nowIdx != highIdx && nowIdx != lowIdx) {
            labels.add(Triple(nowIdx, "${forecastTemps[nowIdx].roundToInt()}°", Color.White.copy(alpha = 0.8f)))
        }

        val drawnLabels = mutableListOf<Rect>()
        labels.sortByDescending { it.first == highIdx || it.first == lowIdx }

        for ((idx, text, color) in labels) {
            val point = coords[idx]
            val textLayout = textMeasurer.measure(text, TextStyle(fontSize = 11.sp, color = color))
            val textWidth = textLayout.size.width.toFloat()
            val textHeight = textLayout.size.height.toFloat()
            val isHigh = idx == highIdx
            
            // Curve avoidance logic: sample Y around the label's horizontal range to detect intrusion
            val xStart = point.x - textWidth / 2f
            val xEnd = point.x + textWidth / 2f
            val verticalGap = 4.dp.toPx()
            
            val textTop: Float
            val baselineTop: Float
            if (isHigh) {
                // Placing label ABOVE the curve. Sample minimum Y (physically highest points).
                val curveMinY = minOf(getCurveYAtX(xStart), getCurveYAtX(point.x), getCurveYAtX(xEnd))
                val desiredTextBottom = curveMinY - verticalGap
                textTop = desiredTextBottom - textHeight
                baselineTop = point.y - verticalGap - textHeight
            } else {
                // Placing label BELOW the curve. Sample maximum Y (physically lowest points).
                val curveMaxY = maxOf(getCurveYAtX(xStart), getCurveYAtX(point.x), getCurveYAtX(xEnd))
                textTop = curveMaxY + verticalGap
                baselineTop = point.y + verticalGap
            }
            
            val labelRect = Rect(
                offset = Offset(point.x - textWidth / 2f, textTop),
                size = Size(textWidth, textHeight)
            )
            
            if (drawnLabels.none { it.overlaps(labelRect.inflate(4.dp.toPx())) }) {
                drawText(textLayout, topLeft = labelRect.topLeft)
                drawnLabels.add(labelRect)
                
                // Draw leader line if label has been shifted significantly (e.g., > 1.5px)
                val shift = abs(textTop - baselineTop)
                if (shift > 1.5f) {
                    val leaderLineStart = Offset(point.x, point.y)
                    val leaderLineEnd = if (isHigh) {
                        Offset(point.x, textTop + textHeight)
                    } else {
                        Offset(point.x, textTop)
                    }
                    drawLine(
                        color = Color.White.copy(alpha = 0.35f),
                        start = leaderLineStart,
                        end = leaderLineEnd,
                        strokeWidth = 0.5.dp.toPx()
                    )
                }
            }
        }

        drawDayLabels(
            leftDate = Instant.ofEpochMilli(windowStart).atZone(ZoneId.systemDefault()).toLocalDate(),
            rightDate = Instant.ofEpochMilli(windowEnd).atZone(ZoneId.systemDefault()).toLocalDate(),
            textMeasurer = textMeasurer,
            occupied = drawnLabels,
        )

        // Bottom strip: weather icons + hour labels
        val labelInterval = if (zoomLevel == "NARROW") {
            1
        } else {
            if (w <= NARROW_WIDTH_PX) NARROW_WIDE_LABEL_INTERVAL else WIDE_LABEL_INTERVAL
        }
        for (i in points.indices) {
            val hourFromStart = ((points[i].dateTime - windowStart) / 3_600_000L).toInt()
            if (hourFromStart % labelInterval != 0) continue
            val p = points[i]
            val x = xAt(i)
            painters[i]?.let { painter ->
                val iconSize = 18.dp.toPx()
                val localZdt = Instant.ofEpochMilli(p.dateTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val sunInfo = com.weatherwidget.util.SunPositionUtils.getSunInfo(localZdt, latitude, longitude)
                val flags = WeatherIcon.getConditionFlags(p.condition, isNight = sunInfo.isNight).copy(
                    isTwilight = sunInfo.phase == com.weatherwidget.util.SunPhase.TWILIGHT
                )
                val filter = if (!flags.isRainy && !flags.isMixed) {
                    val tint = when {
                        flags.isNight -> Color(0xFFBBBBBB)
                        flags.isTwilight -> Color(0xFFFFA726)
                        flags.isSunny -> Color(0xFFFFD60A)
                        else -> Color(0xFFBBBBBB)
                    }
                    ColorFilter.tint(tint)
                } else {
                    null
                }
                translate(x - iconSize / 2f, h - 38f) {
                    with(painter) { draw(size = Size(iconSize, iconSize), colorFilter = filter) }
                }
            }
            val time = Instant.ofEpochMilli(p.dateTime)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
            val timeStr = formatHourLabel(time.hour)
            val timeLayout = textMeasurer.measure(timeStr, TextStyle(fontSize = 9.sp, color = Color.Gray))
            drawText(timeLayout, topLeft = Offset(x - timeLayout.size.width / 2f, h - 14f))
        }
    }
}

private fun DrawScope.drawDayLabels(
    leftDate: LocalDate,
    rightDate: LocalDate,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    occupied: MutableList<Rect>,
) {
    val today = LocalDate.now()
    val dates = listOf(0f to leftDate, size.width to rightDate)
    dates.forEach { (edgeX, date) ->
        val isToday = date == today
        val color = if (isToday) Color.Yellow.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.45f)
        val text = date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
        val layout = textMeasurer.measure(text, TextStyle(fontSize = 10.sp, color = color))
        val x = edgeX.coerceIn(layout.size.width / 2f, size.width - layout.size.width / 2f)
        val candidates = listOf(8f, size.height * 0.48f, size.height - 48f)
        val y = candidates.firstOrNull { top ->
            val rect = Rect(
                offset = Offset(x - layout.size.width / 2f, top),
                size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
            )
            occupied.none { it.overlaps(rect.inflate(4f)) }
        } ?: candidates.last()
        val rect = Rect(
            offset = Offset(x - layout.size.width / 2f, y),
            size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
        )
        drawText(layout, topLeft = rect.topLeft)
        occupied.add(rect)
    }
}

private fun formatHourLabel(hour: Int): String {
    val hour12 = when (val h = hour % 12) {
        0 -> 12
        else -> h
    }
    val suffix = if (hour < 12) "a" else "p"
    return "$hour12$suffix"
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

private fun buildColorStops(minTemp: Float, maxTemp: Float, range: Float): Array<Pair<Float, Color>> {
    fun posOf(t: Float): Float = ((maxTemp - t) / range).coerceIn(0f, 1f)
    return buildList {
        add(0f to tempToColor(maxTemp))
        add(1f to tempToColor(minTemp))
        for (t in listOf(HOT_THRESHOLD, MILD_TEMP, COLD_THRESHOLD)) {
            if (t > minTemp && t < maxTemp) add(posOf(t) to tempToColor(t))
        }
    }.sortedBy { it.first }.distinctBy { (it.first * 1000).toInt() }.toTypedArray()
}

private fun computeTangents(coords: List<Offset>): List<Offset> {
    if (coords.size < 2) return coords.map { Offset.Zero }
    return coords.indices.map { i ->
        when (i) {
            0 -> Offset(
                (coords[1].x - coords[0].x) * 0.5f,
                (coords[1].y - coords[0].y) * 0.5f
            )
            coords.size - 1 -> Offset(
                (coords[i].x - coords[i - 1].x) * 0.5f,
                (coords[i].y - coords[i - 1].y) * 0.5f
            )
            else -> {
                val dxPrev = coords[i].x - coords[i - 1].x
                val dxNext = coords[i + 1].x - coords[i].x
                val dx = (dxPrev + dxNext) * 0.5f
                var dy = (coords[i + 1].y - coords[i - 1].y) * 0.5f

                val delta1 = coords[i].y - coords[i - 1].y
                val delta2 = coords[i + 1].y - coords[i].y
                if (delta1 == 0f || delta2 == 0f || (delta1 > 0 && delta2 < 0) || (delta1 < 0 && delta2 > 0)) {
                    dy = 0f
                }
                
                val maxSafeDx = dxPrev.coerceAtMost(dxNext) * 1.5f
                if (dx > maxSafeDx && maxSafeDx > 0) {
                    val scale = maxSafeDx / dx
                    Offset(maxSafeDx, dy * scale)
                } else {
                    Offset(dx, dy)
                }
            }
        }
    }
}

private fun buildCurve(coords: List<Offset>): Path = Path().apply {
    if (coords.isEmpty()) return@apply
    moveTo(coords[0].x, coords[0].y)
    if (coords.size > 1) {
        val tangents = computeTangents(coords)
        for (i in 0 until coords.size - 1) {
            val cp1x = coords[i].x + tangents[i].x / 3f
            val cp1y = coords[i].y + tangents[i].y / 3f
            val cp2x = coords[i + 1].x - tangents[i + 1].x / 3f
            val cp2y = coords[i + 1].y - tangents[i + 1].y / 3f
            cubicTo(cp1x, cp1y, cp2x, cp2y, coords[i + 1].x, coords[i + 1].y)
        }
    }
}

private fun DrawScope.drawFill(coords: List<Offset>, minTemp: Float, maxTemp: Float, range: Float) {
    val h = size.height
    val fill = Path().apply {
        addPath(buildCurve(coords))
        lineTo(coords.last().x, h)
        lineTo(coords.first().x, h)
        close()
    }
    val stops = buildColorStops(minTemp, maxTemp, range)
    val fillStops = stops.map { (pos, color) -> pos to color.copy(alpha = 0.40f * (1f - pos)) }.toTypedArray()
    drawPath(fill, brush = Brush.verticalGradient(colorStops = fillStops, startY = 0f, endY = h))
}

private fun DrawScope.drawCurveLine(
    coords: List<Offset>,
    minTemp: Float,
    maxTemp: Float,
    range: Float,
    dashed: Boolean,
    alpha: Float = 1f,
) {
    val h = size.height
    val pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(16f, 8f)) else null
    drawPath(
        buildCurve(coords),
        brush = Brush.verticalGradient(
            colorStops = buildColorStops(minTemp, maxTemp, range).map { (pos, color) -> 
                pos to color.copy(alpha = alpha) 
            }.toTypedArray(),
            startY = 0f,
            endY = h,
        ),
        style = Stroke(width = 3f, pathEffect = pathEffect),
    )
}

private fun DrawScope.drawActualLine(coords: List<Offset>) {
    drawPath(
        buildCurve(coords),
        color = COLOR_ACTUAL,
        style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
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
