package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.weatherwidget.shared.graph.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

private val COLOR_PRECP_CURVE = Color(0xFF5AC8FA)
private val COLOR_PRECP_FILL_START = Color(0xFF5AC8FA).copy(alpha = 0.27f)
private val COLOR_PRECP_FILL_END = Color.Transparent
private val COLOR_RAIN_AMOUNT = Color(0xFFFFFFFF)
private val COLOR_ACTUAL_RAIN_AMOUNT = Color(0xFFFF9F0A)
// Kept subtle on desktop (Compose renders crisper than Android, where the same line is barely
// visible); Android uses #66FFFFFF (~0.4 alpha) and is untouched.
private val COLOR_DAY_NIGHT_DIVIDER = Color.White.copy(alpha = 0.2f)

private fun forecastColor(flags: com.weatherwidget.shared.util.WeatherConditionResolver.ConditionFlags): Color {
    val argb = com.weatherwidget.shared.util.WeatherColors.forecastColor(flags.isSunny, flags.isRainy, flags.isMixed, flags.isNight, flags.isTwilight)
    return Color(argb)
}


@Composable
fun PrecipitationGraph(
    hourly: List<HourlyForecast>,
    observations: List<ObservationReading> = emptyList(),
    displaySourceId: String = "NWS",
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    modifier: Modifier = Modifier,
    centerOffsetHours: Int = 0,
    zoomFactor: Float = DesktopGraphUtils.DEFAULT_ZOOM_FACTOR,
    scale: Float = 1f,
    onViewModeChange: (String) -> Unit = {},
    onZoomScroll: (deltaZoom: Float, centerOffset: Int) -> Unit = { _, _ -> },
    onPan: (deltaHours: Int) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val setup = rememberHourlyGraphSetup(hourly, centerOffsetHours, zoomFactor) ?: return
    val now = setup.now
    val dragHours = setup.dragHours
    val totalSpanHours = setup.totalSpanHours
    val start = setup.start
    val cutoff = setup.cutoff
    val points = setup.points
    val painters = setup.painters
    val smoothIterations = setup.smoothIterations

    val watermarkPainter = painterResource("drawable/ic_weather_rain.xml")

    Canvas(
        modifier = modifier.hourlyGraphFooterTapInput(
            start = start,
            cutoff = cutoff,
            nowMs = now,
            spanHours = totalSpanHours,
            dragHours = dragHours,
            points = points,
            zoomFactor = zoomFactor,
            scale = scale,
            onViewModeChange = onViewModeChange,
            onZoomScroll = onZoomScroll,
            onPan = onPan,
        )
    ) {
        val windowStart = start
        val windowEnd = cutoff

        val rawProbs = points.map { it.precipProbability?.toFloat() ?: 0f }
        val smoothedProbs = com.weatherwidget.shared.util.TemperatureInterpolator.smoothValuesPreservingAllExtrema(rawProbs, smoothIterations)
        
        val maxProb = smoothedProbs.maxOrNull() ?: 0f
        val yScaleMax = (maxProb * 1.15f).coerceAtLeast(10f).coerceAtMost(100f)

        // Plot bounds + x-axis mapping shared with the cloud/temperature graphs.
        val geo = hourlyGraphCanvasGeometry(points, textMeasurer, scale, dragHours.value)
        val w = geo.w
        val h = geo.h
        val graphTop = geo.graphTop
        val graphBottom = geo.graphBottom
        val graphHeight = geo.graphHeight
        val footer = geo.footer
        val xAtTime = geo.xAtTime
        val xAt = geo.xAt
        val stepWidth = w / (points.size - 1).coerceAtLeast(1)
        fun yAt(prob: Float): Float {
            val clamped = prob.coerceIn(0f, 100f)
            return graphBottom - graphHeight * (clamped / yScaleMax)
        }

        val coords = points.mapIndexed { i, _ -> Offset(xAt(i), yAt(smoothedProbs[i])) }

        // Draw Fill Path
        val fillPath = Path().apply {
            if (coords.isNotEmpty()) {
                addPath(buildCurve(coords))
                lineTo(coords.last().x, graphBottom)
                lineTo(coords.first().x, graphBottom)
                close()
            }
        }
        val fillBrush = Brush.verticalGradient(
            colors = listOf(COLOR_PRECP_FILL_START, COLOR_PRECP_FILL_END),
            startY = graphTop,
            endY = graphBottom
        )
        drawPath(fillPath, brush = fillBrush)

        val markerX = xAtTime(now)

        // Draw Now vertical dashed guide line - EARLY for lowest z-order (shared helper).
        if (now in windowStart..windowEnd) {
            drawNowLine(markerX, graphTop, graphHeight, scale)
        }

        // Draw Curve Line
        val curveStroke = if (totalSpanHours <= 8) 2.dp.toPx() * scale else 3.dp.toPx() * scale
        drawPath(
            path = buildCurve(coords),
            color = COLOR_PRECP_CURVE,
            style = Stroke(width = curveStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Draw Day/Night boundary dividers at 8AM / 8PM
        val zoneId = ZoneId.systemDefault()
        if (totalSpanHours >= 12) {
            for (i in 1..points.lastIndex) {
                val ldt1 = LocalDateTime.ofInstant(Instant.ofEpochMilli(points[i - 1].dateTime), zoneId)
                val ldt2 = LocalDateTime.ofInstant(Instant.ofEpochMilli(points[i].dateTime), zoneId)
                val isDay1 = ldt1.hour in 8 until 20
                val isDay2 = ldt2.hour in 8 until 20
                if (isDay1 != isDay2) {
                    // Use the same time->x mapping as the curve/labels (xAt) so the divider stays
                    // locked to the curve under drag/zoom instead of drifting (stepWidth was index-only).
                    val boundaryX = xAt(i)
                    drawLine(
                        color = COLOR_DAY_NIGHT_DIVIDER,
                        start = Offset(boundaryX, graphTop),
                        end = Offset(boundaryX, graphBottom),
                        strokeWidth = 1.dp.toPx() * scale,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx() * scale, 3.dp.toPx() * scale))
                    )
                }
            }
        }

        // Value (%) labels (peak / dip / start / end) via the shared ValueLabelEngine — the same
        // engine used by the cloud graph and the Android renderers. Compose draws top-left anchored,
        // so we treat the measured box bottom as the baseline (ascent = -height, descent = 0).
        val labelSignal = smoothedProbs.map { it.roundToInt().coerceIn(0, 100) }
        val labelStyle = TextStyle(fontSize = (11 * scale).sp, color = Color.White)
        val labelHeight = textMeasurer.measure("0%", labelStyle).size.height.toFloat()
        val placements = ValueLabelEngine.computePlacements(
            labelSignal = labelSignal,
            points = coords.map { ValueLabelEngine.GraphPoint(it.x, it.y) },
            geometry = ValueLabelEngine.Geometry(graphTop, graphBottom, graphHeight, w, h),
            config = ValueLabelEngine.Config.precip(),
            measureText = { textMeasurer.measure(it, labelStyle).size.width.toFloat() },
            textAscent = -labelHeight,
            textDescent = 0f,
            dpToPx = { it.dp.toPx() * scale },
        )
        val drawnLabels = mutableListOf<Rect>()
        for (p in placements) {
            val r = Rect(p.box.left, p.box.top, p.box.right, p.box.bottom)
            drawText(textMeasurer.measure(p.text, labelStyle), topLeft = r.topLeft)
            drawnLabels.add(r)
        }

        // Resolving rain periods & drawing rain amount labels
        val forecastRainPeriods = selectDayNightSegments(points, emptyList())
            .mapNotNull { it.toRainPeriod(points, stepWidth) { f -> f.precipAmountMm } }
        val actualRainPeriods = selectDayNightSegments(points, observations)
            .mapNotNull { it.toRainPeriod(points, stepWidth) { f -> f.precipAmountMm } }

        val rainPlacements = calculateRainAmountPlacements(
            rainPeriods = forecastRainPeriods,
            widthPx = w,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            initialCollisionBounds = drawnLabels,
            labelPrefix = "",
            textMeasurer = textMeasurer,
            textStyle = TextStyle(fontSize = (10 * scale).sp, color = COLOR_RAIN_AMOUNT, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            dpToPx = 1.dp.toPx() * scale
        )
        val actualRainPlacements = calculateRainAmountPlacements(
            rainPeriods = actualRainPeriods,
            widthPx = w,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            initialCollisionBounds = drawnLabels + rainPlacements.map { it.bounds },
            labelPrefix = "Actual: ",
            textMeasurer = textMeasurer,
            textStyle = TextStyle(fontSize = (10 * scale).sp, color = COLOR_ACTUAL_RAIN_AMOUNT, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            dpToPx = 1.dp.toPx() * scale
        )

        // Draw Rain Amount Labels
        for (p in rainPlacements) {
            val textLayout = textMeasurer.measure(p.text, TextStyle(fontSize = (10 * scale).sp, color = COLOR_RAIN_AMOUNT, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            drawText(textLayout, topLeft = p.bounds.topLeft)
            drawnLabels.add(p.bounds)
        }
        for (p in actualRainPlacements) {
            val textLayout = textMeasurer.measure(p.text, TextStyle(fontSize = (10 * scale).sp, color = COLOR_ACTUAL_RAIN_AMOUNT, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            drawText(textLayout, topLeft = p.bounds.topLeft)
            drawnLabels.add(p.bounds)
        }

        // Draw Watermark Rain Icon (centered)
        var watermarkPlaced = false
        val watermarkIconSize = 44.dp.toPx() * scale
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
                val fitsAboveCurve = bounds.top >= 0f && bounds.bottom < curveY - 2.dp.toPx() * scale
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

        // Shared tail: edge day labels (suppressed in date mode) + footer strip + NOW label on top.
        drawDayLabelsFooterAndNow(
            points = points,
            painters = painters,
            totalSpanHours = totalSpanHours,
            latitude = latitude,
            longitude = longitude,
            footer = footer,
            widthPx = w,
            heightPx = h,
            textMeasurer = textMeasurer,
            scale = scale,
            now = now,
            markerX = markerX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            windowStart = windowStart,
            windowEnd = windowEnd,
            drawnLabels = drawnLabels,
            xAt = xAt,
        )
    }
}

private fun buildCurve(coords: List<Offset>): Path = DesktopGraphUtils.buildCurve(coords)

private fun computeTangents(coords: List<Offset>): List<Offset> = DesktopGraphUtils.computeTangents(coords)

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

private fun formatPrecipAmount(amountMm: Float): String =
    com.weatherwidget.shared.util.DailyRainLabels.formatPrecipAmount(amountMm)

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
