package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import java.time.format.TextStyle as JavaTextStyle

private val COLOR_PRECP_CURVE = Color(0xFF5AC8FA)
private val COLOR_PRECP_FILL_START = Color(0xFF5AC8FA).copy(alpha = 0.27f)
private val COLOR_PRECP_FILL_END = Color.Transparent
private val COLOR_RAIN_AMOUNT = Color(0xFFFFFFFF)
private val COLOR_ACTUAL_RAIN_AMOUNT = Color(0xFFFF9F0A)
private val COLOR_DAY_NIGHT_DIVIDER = Color.White.copy(alpha = 0.4f)

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

private const val WIDE_BACK_HOURS = 12
private const val WIDE_FORWARD_HOURS = 12
private const val WIDE_LABEL_INTERVAL = 4
private const val NARROW_WIDE_LABEL_INTERVAL = 6
private const val NARROW_WIDTH_PX = 420f

@Composable
fun PrecipitationGraph(
    hourly: List<HourlyForecast>,
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

    val watermarkPainter = painterResource("drawable/ic_weather_rain.xml")

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

        val rawProbs = points.map { it.precipProbability?.toFloat() ?: 0f }
        val smoothedProbs = com.weatherwidget.shared.util.TemperatureInterpolator.smoothValuesPreservingAllExtrema(rawProbs, smoothIterations)
        
        val maxProb = smoothedProbs.maxOrNull() ?: 0f
        val yScaleMax = (maxProb * 1.15f).coerceAtLeast(10f).coerceAtMost(100f)

        val w = size.width
        val h = size.height

        val graphTop = 38.dp.toPx()
        val footerIconSize = 18.dp.toPx()
        val bottomInset = 4.dp.toPx()
        val graphBottom = h - footerIconSize - bottomInset
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)
        val stepWidth = w / (points.size - 1).coerceAtLeast(1)

        fun xAtTime(t: Long): Float = ((t - windowStart).toFloat() / windowSpan * w).coerceIn(0f, w)
        fun xAt(i: Int): Float = xAtTime(points[i].dateTime)
        fun yAt(prob: Float): Float {
            val clamped = prob.coerceIn(0f, 100f)
            return graphBottom - graphHeight * (clamped / yScaleMax)
        }

        val coords = points.mapIndexed { i, _ -> Offset(xAt(i), yAt(smoothedProbs[i])) }

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

        // Draw Fill Path
        val fillPath = Path().apply {
            addPath(buildCurve(coords))
            lineTo(coords.last().x, graphBottom)
            lineTo(coords.first().x, graphBottom)
            close()
        }
        val fillBrush = Brush.verticalGradient(
            colors = listOf(COLOR_PRECP_FILL_START, COLOR_PRECP_FILL_END),
            startY = graphTop,
            endY = graphBottom
        )
        drawPath(fillPath, brush = fillBrush)

        // Draw Curve Line
        val curveStroke = if (zoomLevel == "NARROW") 2.dp.toPx() else 3.dp.toPx()
        drawPath(
            path = buildCurve(coords),
            color = COLOR_PRECP_CURVE,
            style = Stroke(width = curveStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Draw Day/Night boundary dividers at 8AM / 8PM
        val zoneId = ZoneId.systemDefault()
        val dayNightBoundaryXs = mutableListOf<Float>()
        if (zoomLevel == "WIDE") {
            for (i in 1..points.lastIndex) {
                val ldt1 = LocalDateTime.ofInstant(Instant.ofEpochMilli(points[i - 1].dateTime), zoneId)
                val ldt2 = LocalDateTime.ofInstant(Instant.ofEpochMilli(points[i].dateTime), zoneId)
                val isDay1 = ldt1.hour in 8 until 20
                val isDay2 = ldt2.hour in 8 until 20
                if (isDay1 != isDay2) {
                    val boundaryX = stepWidth * i
                    dayNightBoundaryXs.add(boundaryX)
                    drawLine(
                        color = COLOR_DAY_NIGHT_DIVIDER,
                        start = Offset(boundaryX, graphTop),
                        end = Offset(boundaryX, graphBottom),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                    )
                }
            }
        }

        // Draw Now vertical dashed guide line
        val nowIdx = points.indexOfByClosestTime(now)
        val markerProb = smoothedProbs[nowIdx]
        val markerX = xAtTime(now)
        val markerY = yAt(markerProb)
        if (now in windowStart..windowEnd) {
            drawLine(
                color = Color.White.copy(alpha = 0.36f),
                start = Offset(markerX, graphTop),
                end = Offset(markerX, graphBottom),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            )
            drawCircle(color = Color.White, radius = 4.5f, center = Offset(markerX, markerY))
            drawCircle(color = COLOR_PRECP_CURVE, radius = 2.5f, center = Offset(markerX, markerY))
            
            // NOW Label
            val nowLayout = textMeasurer.measure("NOW", TextStyle(fontSize = 8.sp, color = Color.White.copy(alpha = 0.6f)))
            drawText(nowLayout, topLeft = Offset(markerX - nowLayout.size.width / 2f, graphTop + 2.dp.toPx()))
        }

        // Extrema Label Placement
        val labelSignal = smoothedProbs.map { it.roundToInt().coerceIn(0, 100) }
        val globalMaxVal = labelSignal.maxOrNull() ?: -1
        val globalMinVal = labelSignal.minOrNull() ?: -1

        val globalMaxIdx = labelSignal.indexOfFirst { it == globalMaxVal }
        val globalMinIdx = labelSignal.indexOfFirst { it == globalMinVal }

        // Find soft dips
        val softDipCandidates = mutableListOf<Int>()
        var jIdx = 0
        while (jIdx < labelSignal.size) {
            val prob = labelSignal[jIdx]
            if (prob <= 0 || prob > 85) {
                jIdx++
                continue
            }
            var runEnd = jIdx
            while (runEnd < labelSignal.lastIndex && labelSignal[runEnd + 1] == prob) {
                runEnd++
            }
            val left = (jIdx - 4).coerceAtLeast(0)
            val right = (runEnd + 4).coerceAtMost(labelSignal.lastIndex)
            if (left < jIdx && right > runEnd) {
                val leftMax = (left until jIdx).maxOfOrNull { labelSignal[it] } ?: prob
                val rightMax = ((runEnd + 1)..right).maxOfOrNull { labelSignal[it] } ?: prob
                if (leftMax >= prob + 15 && rightMax >= prob + 15) {
                    softDipCandidates.add(jIdx + (runEnd - jIdx) / 2)
                }
            }
            jIdx = runEnd + 1
        }

        val candidates = mutableListOf<Int>()
        if (globalMaxIdx >= 0 && globalMaxVal > 0) candidates.add(globalMaxIdx)
        if (globalMinIdx >= 0 && globalMinIdx != globalMaxIdx) candidates.add(globalMinIdx)
        if (0 !in candidates) candidates.add(0)
        if (points.lastIndex !in candidates && points.isNotEmpty()) candidates.add(points.lastIndex)
        candidates.addAll(softDipCandidates)
        candidates.sortBy { it }

        val finalCandidates = candidates.distinct()
        val drawnLabels = mutableListOf<Rect>()

        for (index in finalCandidates) {
            if (index !in labelSignal.indices) continue
            val probVal = labelSignal[index]
            val labelText = "$probVal%"
            
            val textLayout = textMeasurer.measure(labelText, TextStyle(fontSize = 11.sp, color = Color.White))
            val textWidth = textLayout.size.width.toFloat()
            val textHeight = textLayout.size.height.toFloat()
            
            val centerX = coords[index].x
            val pointY = coords[index].y

            val isPeak = index == globalMaxIdx || (index > 0 && index < labelSignal.lastIndex &&
                labelSignal[index] > labelSignal[index - 1] && labelSignal[index] > labelSignal[index + 1])
            val isEndLabelCandidate = index == points.lastIndex
            val isRisingAtEnd = isEndLabelCandidate && index > 0 && coords[index].y < coords[index - 1].y - 2f
            val isFallingFromLeftEdge = index == 0 && points.size > 1 && coords[1].y > coords[0].y + 2f
            val preferAbove = isPeak || isRisingAtEnd || isFallingFromLeftEdge

            val attempts = if (preferAbove) listOf(true, false) else listOf(false, true)
            var placed = false

            for (placeAbove in attempts) {
                val gapPx = 6.dp.toPx()
                val textTop = if (placeAbove) {
                    val curveMinY = minOf(getCurveYAtX(centerX - textWidth / 2f), pointY, getCurveYAtX(centerX + textWidth / 2f))
                    curveMinY - gapPx - textHeight
                } else {
                    val curveMaxY = maxOf(getCurveYAtX(centerX - textWidth / 2f), pointY, getCurveYAtX(centerX + textWidth / 2f))
                    curveMaxY + gapPx
                }

                val x = centerX.coerceIn(textWidth / 2f, w - textWidth / 2f)
                val bounds = Rect(offset = Offset(x - textWidth / 2f, textTop), size = Size(textWidth, textHeight))

                val safeBottom = graphBottom - 10.dp.toPx()
                if (bounds.top < 0f || bounds.bottom > safeBottom) continue

                val overlaps = drawnLabels.any { it.overlaps(bounds.inflate(4.dp.toPx())) }
                if (!overlaps) {
                    drawText(textLayout, topLeft = bounds.topLeft)
                    drawnLabels.add(bounds)
                    placed = true
                    
                    // Draw leader line if shifted significantly
                    val baselineTop = if (placeAbove) pointY - gapPx - textHeight else pointY + gapPx
                    val shift = abs(textTop - baselineTop)
                    if (shift > 1.5f) {
                        val leaderLineStart = Offset(centerX, pointY)
                        val leaderLineEnd = if (placeAbove) Offset(centerX, textTop + textHeight) else Offset(centerX, textTop)
                        drawLine(
                            color = Color.White.copy(alpha = 0.35f),
                            start = leaderLineStart,
                            end = leaderLineEnd,
                            strokeWidth = 0.5.dp.toPx()
                        )
                    }
                    break
                }
            }
        }

        // --- Rain Amount Layout & Positioning (Forecast & Observed Actuals) ---
        val actualPrecipByHour = observations.associate { obs ->
            val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(obs.timestamp), zoneId)
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            ldt to (obs.precipAmountMm ?: 0f)
        }

        val isNarrow = zoomLevel == "NARROW"
        val (rainPeriods, actualRainPeriods) = if (isNarrow) {
            perHourRainPeriods(points, stepWidth) { it.precipAmountMm } to
            perHourRainPeriods(points, stepWidth) { actualPrecipByHour[LocalDateTime.ofInstant(Instant.ofEpochMilli(it.dateTime), zoneId)] }
        } else {
            val segments = selectDayNightSegments(points, observations)
            val pred = segments.mapNotNull { it.toRainPeriod(points, stepWidth) { h -> h.precipAmountMm } }
            val act = segments.mapNotNull { it.toRainPeriod(points, stepWidth) { h -> actualPrecipByHour[LocalDateTime.ofInstant(Instant.ofEpochMilli(h.dateTime), zoneId)] } }
            pred to act
        }

        val rainPlacements = calculateRainAmountPlacements(
            rainPeriods = rainPeriods,
            widthPx = w,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            initialCollisionBounds = drawnLabels,
            labelPrefix = if (isNarrow) "" else "Pred ",
            textMeasurer = textMeasurer,
            textStyle = TextStyle(fontSize = 10.sp, color = COLOR_RAIN_AMOUNT, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            dpToPx = 1.dp.toPx()
        )

        val actualRainPlacements = calculateRainAmountPlacements(
            rainPeriods = actualRainPeriods,
            widthPx = w,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            initialCollisionBounds = drawnLabels + rainPlacements.map { it.bounds },
            labelPrefix = if (isNarrow) "" else "Act ",
            textMeasurer = textMeasurer,
            textStyle = TextStyle(fontSize = 10.sp, color = COLOR_ACTUAL_RAIN_AMOUNT, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            dpToPx = 1.dp.toPx()
        )

        // Draw Rain Amount Labels
        for (p in rainPlacements) {
            val textLayout = textMeasurer.measure(p.text, TextStyle(fontSize = 10.sp, color = COLOR_RAIN_AMOUNT, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            drawText(textLayout, topLeft = p.bounds.topLeft)
            drawnLabels.add(p.bounds)
        }
        for (p in actualRainPlacements) {
            val textLayout = textMeasurer.measure(p.text, TextStyle(fontSize = 10.sp, color = COLOR_ACTUAL_RAIN_AMOUNT, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            drawText(textLayout, topLeft = p.bounds.topLeft)
            drawnLabels.add(p.bounds)
        }

        // Draw Watermark Rain Icon (centered)
        var watermarkPlaced = false
        val watermarkIconSize = 44.dp.toPx()
        val candidateCenters = listOf(coords.size / 2, coords.size / 3, 2 * coords.size / 3)
        for (center in candidateCenters) {
            val curveX = coords[center].x
            val curveY = coords[center].y
            for (fraction in listOf(0.5f, 0.65f, 0.35f)) {
                val centerY = graphTop + (curveY - graphTop) * fraction
                val bounds = Rect(
                    offset = Offset(curveX - watermarkIconSize / 2f, centerY - watermarkIconSize / 2f),
                    size = Size(watermarkIconSize, watermarkIconSize)
                )
                val fitsAboveCurve = bounds.top >= 0f && bounds.bottom < curveY - 2.dp.toPx()
                val overlapsLabels = drawnLabels.any { it.overlaps(bounds) }
                if (fitsAboveCurve && !overlapsLabels) {
                    translate(bounds.left, bounds.top) {
                        with(watermarkPainter) {
                            draw(
                                size = Size(watermarkIconSize, watermarkIconSize),
                                alpha = 0.08f
                            )
                        }
                    }
                    watermarkPlaced = true
                    break
                }
            }
            if (watermarkPlaced) break
        }

        // Draw Day Labels at bottom
        drawDayLabels(
            leftDate = Instant.ofEpochMilli(windowStart).atZone(ZoneId.systemDefault()).toLocalDate(),
            rightDate = Instant.ofEpochMilli(windowEnd).atZone(ZoneId.systemDefault()).toLocalDate(),
            textMeasurer = textMeasurer,
            occupied = drawnLabels,
        )

        // Draw Bottom Strip: icons + hour labels
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

private data class RainPeriod(
    val startIndex: Int,
    val endIndex: Int,
    val totalAmountMm: Float,
    val anchorX: Float? = null,
)

private data class DayNightSegment(
    val startIndex: Int,
    val endIndex: Int,
    val isDay: Boolean,
)

private fun dayNightRuns(hours: List<HourlyForecast>): List<DayNightSegment> {
    if (hours.isEmpty()) return emptyList()
    val runs = mutableListOf<DayNightSegment>()
    var start = 0
    val zoneId = ZoneId.systemDefault()
    val firstLdt = LocalDateTime.ofInstant(Instant.ofEpochMilli(hours[0].dateTime), zoneId)
    var currentIsDay = firstLdt.hour in 8 until 20
    for (i in 1..hours.lastIndex) {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(hours[i].dateTime), zoneId)
        val isDay = ldt.hour in 8 until 20
        if (isDay != currentIsDay) {
            runs.add(DayNightSegment(start, i - 1, currentIsDay))
            start = i
            currentIsDay = isDay
        }
    }
    runs.add(DayNightSegment(start, hours.lastIndex, currentIsDay))
    return runs
}

private fun selectDayNightSegments(hours: List<HourlyForecast>, observations: List<ObservationReading>): List<DayNightSegment> {
    val runs = dayNightRuns(hours)
    val zoneId = ZoneId.systemDefault()
    val actualPrecipByHour = observations.associate { obs ->
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(obs.timestamp), zoneId)
            .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        ldt to (obs.precipAmountMm ?: 0f)
    }

    fun combinedTotal(seg: DayNightSegment): Float {
        return (seg.startIndex..seg.endIndex).sumOf { idx ->
            val forecast = hours[idx]
            val forecastAmount = forecast.precipAmountMm ?: 0f
            val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(forecast.dateTime), zoneId)
            val actualAmount = actualPrecipByHour[ldt] ?: 0f
            (forecastAmount + actualAmount).toDouble()
        }.toFloat()
    }

    val wettestDay = runs.filter { it.isDay }
        .maxByOrNull { combinedTotal(it) }
        ?.takeIf { combinedTotal(it) > 0f }
    val wettestNight = runs.filterNot { it.isDay }
        .maxByOrNull { combinedTotal(it) }
        ?.takeIf { combinedTotal(it) > 0f }
    return listOfNotNull(wettestDay, wettestNight).sortedBy { it.startIndex }
}

private fun DayNightSegment.toRainPeriod(
    hours: List<HourlyForecast>,
    hourWidth: Float,
    amountFor: (HourlyForecast) -> Float?,
): RainPeriod? {
    val total = (startIndex..endIndex).sumOf { idx ->
        (amountFor(hours[idx]) ?: 0f).toDouble()
    }.toFloat()
    if (total <= 0f) return null
    val centerX = hourWidth * (startIndex + endIndex) / 2f
    return RainPeriod(
        startIndex = startIndex,
        endIndex = endIndex,
        totalAmountMm = total,
        anchorX = centerX,
    )
}

private fun perHourRainPeriods(
    hours: List<HourlyForecast>,
    hourWidth: Float,
    amountFor: (HourlyForecast) -> Float?,
): List<RainPeriod> {
    val limit = minOf(4, hours.size)
    val periods = mutableListOf<RainPeriod>()
    for (i in 0 until limit) {
        val amount = amountFor(hours[i]) ?: continue
        if (amount <= 0f) continue
        periods.add(
            RainPeriod(
                startIndex = i,
                endIndex = i,
                totalAmountMm = amount,
                anchorX = hourWidth * i,
            ),
        )
    }
    return periods
}

private fun formatPrecipAmount(amountMm: Float): String {
    val country = Locale.getDefault().country.uppercase(Locale.US)
    return if (country == "US" || country == "GB") {
        formatInches(amountMm / 25.4f)
    } else {
        formatMillimeters(amountMm)
    }
}

private fun formatInches(amountInches: Float): String {
    val precision = when {
        amountInches < 0.01f -> 3
        amountInches < 0.1f -> 3
        amountInches < 1f -> 2
        else -> 1
    }
    val formatted = String.format(Locale.US, "%.${precision}f", amountInches)
        .trimEnd('0')
        .trimEnd('.')
    return "${formatted.removePrefix("0")}in"
}

private fun formatMillimeters(amountMm: Float): String {
    val precision = if (amountMm < 10f) 1 else 0
    val formatted = String.format(Locale.US, "%.${precision}f", amountMm)
        .trimEnd('0')
        .trimEnd('.')
    return "${formatted.removePrefix("0")}mm"
}

private data class RainAmountPlacement(
    val text: String,
    val x: Float,
    val y: Float,
    val bounds: Rect,
)

private fun calculateRainAmountPlacements(
    rainPeriods: List<RainPeriod>,
    widthPx: Float,
    graphTop: Float,
    graphBottom: Float,
    graphHeight: Float,
    initialCollisionBounds: List<Rect>,
    labelPrefix: String = "",
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    textStyle: TextStyle,
    dpToPx: Float,
): List<RainAmountPlacement> {
    val placements = mutableListOf<RainAmountPlacement>()
    val rainCollisionBounds = initialCollisionBounds.toMutableList()
    
    val xFractions = listOf(0.18f, 0.35f, 0.50f, 0.65f, 0.82f)
    val yFractions = listOf(0.25f, 0.45f, 0.65f, 0.82f)
    val rainPadPx = 4f * dpToPx

    for (period in rainPeriods) {
        val amountText = labelPrefix + formatPrecipAmount(period.totalAmountMm)
        val textLayout = textMeasurer.measure(amountText, textStyle)
        val textWidth = textLayout.size.width.toFloat()
        val textHeight = textLayout.size.height.toFloat()

        var bestX = 0f
        var bestY = 0f
        var bestBounds = Rect.Zero
        var bestOverlapArea = Float.MAX_VALUE

        val candidateXs = period.anchorX?.let { listOf(it) }
            ?: xFractions.map { widthPx * it }

        outer@ for (yFrac in yFractions) {
            for (rawX in candidateXs) {
                val cx = rawX.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)
                val cy = graphTop + graphHeight * yFrac
                val candidateBounds = Rect(
                    left = cx - textWidth / 2f,
                    top = cy - textHeight / 2f,
                    right = cx + textWidth / 2f,
                    bottom = cy + textHeight / 2f
                )
                if (candidateBounds.top < graphTop || candidateBounds.bottom > graphBottom) continue

                val paddedBounds = candidateBounds.inflate(rainPadPx)
                val overlapping = rainCollisionBounds.filter { it.overlaps(paddedBounds) }
                if (overlapping.isEmpty()) {
                    bestX = cx
                    bestY = cy
                    bestBounds = candidateBounds
                    bestOverlapArea = 0f
                    break@outer
                }
                
                val overlapArea = overlapping.sumOf { existing ->
                    val intersect = existing.intersect(paddedBounds)
                    if (intersect.width > 0 && intersect.height > 0) {
                        (intersect.width * intersect.height).toDouble()
                    } else 0.0
                }.toFloat()
                
                if (overlapArea < bestOverlapArea) {
                    bestOverlapArea = overlapArea
                    bestX = cx
                    bestY = cy
                    bestBounds = candidateBounds
                }
            }
        }

        if (bestOverlapArea != Float.MAX_VALUE) {
            placements.add(RainAmountPlacement(amountText, bestX, bestY, bestBounds))
            rainCollisionBounds.add(bestBounds.inflate(rainPadPx))
        }
    }
    return placements
}
