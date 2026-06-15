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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.pointerInput
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.graph.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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

private fun forecastColor(flags: com.weatherwidget.shared.util.WeatherConditionResolver.ConditionFlags): Color {
    val argb = com.weatherwidget.shared.util.WeatherColors.forecastColor(flags.isSunny, flags.isRainy, flags.isMixed, flags.isNight, flags.isTwilight)
    return Color(argb)
}

private const val COLD_THRESHOLD = 50f
private const val MILD_TEMP = 70f
private const val HOT_THRESHOLD = 90f
private const val ACTUALS_CONTEXT_LOOKBACK_HOURS = 144L
private const val ACTUALS_CONTEXT_LOOKAHEAD_HOURS = 60L
private const val AGE_LABEL_MAX_HOURS_SPAN = 12L
// Temperature value labels (on-curve highs/lows + now/current temp) are 10% larger than the 14sp
// time/axis labels for readability. Hour-of-day and day labels keep 14sp.
private const val TEMP_VALUE_LABEL_SP = 15.4f // 14sp + 10%

/**
 * Fetch-dot staleness age, mirroring Android's `TemperatureGraphStyle.formatAgeLabel`: show the age
 * for any non-negative value, but only when the visible window is narrow enough (≤12h span) that
 * freshness is meaningful — so it appears in the zoomed-in view and hides in the wide 24h view.
 * Returns null when it should not be drawn. Format matches Android: "17m", "1h 5m".
 */
private fun formatAgeLabel(ageMinutes: Long, spanHours: Long): String? {
    if (ageMinutes < 0) return null
    if (spanHours > AGE_LABEL_MAX_HOURS_SPAN) return null
    return if (ageMinutes >= 60) {
        "${ageMinutes / 60}h${if (ageMinutes % 60 > 0) " ${ageMinutes % 60}m" else ""}"
    } else {
        "${ageMinutes}m"
    }
}

internal data class HourWindow(val startMs: Long, val endMs: Long)

internal fun temperatureGraphHourWindow(
    centerMs: Long,
    backHours: Int,
    forwardHours: Int,
    zoneId: ZoneId = ZoneId.systemDefault()
): HourWindow {
    val center = LocalDateTime.ofInstant(Instant.ofEpochMilli(centerMs), zoneId)
    val truncated = center.truncatedTo(ChronoUnit.HOURS)
    val alignedCenter = if (center.minute >= 30) truncated.plusHours(1) else truncated
    val startMs = alignedCenter.minusHours(backHours.toLong()).atZone(zoneId).toInstant().toEpochMilli()
    val endMs = alignedCenter.plusHours(forwardHours.toLong()).atZone(zoneId).toInstant().toEpochMilli()
    return HourWindow(startMs, endMs)
}

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
    zoomFactor: Float = DesktopGraphUtils.DEFAULT_ZOOM_FACTOR,
    scale: Float = 1f,
    onViewModeChange: (String) -> Unit = {},
    onToggleZoom: (Int) -> Unit = {},
    onZoomScroll: (deltaZoom: Float, centerOffset: Int) -> Unit = { _, _ -> },
    onPan: (deltaHours: Int) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val now = System.currentTimeMillis()
    // Live, uncommitted horizontal pan (hours). Whole-hour part shifts the data window; the fractional
    // part becomes a sub-hour pixel slide in xAtTime. Committed to config once, on drag release.
    val dragHours = remember { mutableStateOf(0f) }
    val center = now + (centerOffsetHours + dragHours.value.roundToInt()) * 3_600_000L

    val backHours = DesktopGraphUtils.backHoursFor(zoomFactor)
    val forwardHours = DesktopGraphUtils.forwardHoursFor(zoomFactor)
    val totalSpanHours = backHours + forwardHours

    val zoneId = ZoneId.systemDefault()
    val window = remember(center, backHours, forwardHours, zoneId) {
        temperatureGraphHourWindow(center, backHours, forwardHours, zoneId)
    }
    val start = window.startMs
    val cutoff = window.endMs

    val points = remember(hourly, start, cutoff) {
        hourly.filter { it.dateTime in start..cutoff }
            .sortedBy { it.dateTime }
            .ifEmpty { hourly.sortedBy { it.dateTime }.take(backHours + forwardHours + 1) }
    }

    // One painter per point. Icon spacing is decided by the hour-label filter in the bottom strip
    // (drawn at every labeled hour), NOT by index spacing here — index spacing never aligns with the
    // clock-hour labels in WIDE, which left every label without its day-night icon.
    val painters: List<Painter> = points.map { painterResource(WeatherIcon.getIconResource(it.condition)) }

    val smoothIterations = DesktopGraphUtils.smoothIterationsFor(totalSpanHours)

    if (points.size < 2) return

    Canvas(
        modifier = modifier
            .hourlyPanZoomInput(
                start = start,
                cutoff = cutoff,
                nowMs = now,
                spanHours = totalSpanHours,
                dragHours = dragHours,
                onZoomScroll = onZoomScroll,
                onPanCommit = onPan,
            )
            .pointerInput(points, zoomFactor, scale, start, cutoff) {
                detectTapGestures { offset ->
                    if (offset.y >= size.height - 44.dp.toPx() * scale) {
                        // Bottom strip: switch the view to the tapped hour's condition home view.
                        val stepWidth = size.width / (points.size - 1).coerceAtLeast(1)
                        val index = (offset.x / stepWidth).roundToInt().coerceIn(0, points.lastIndex)
                        val clickedPoint = points[index]
                        val iconRes = WeatherIcon.getIconResource(clickedPoint.condition)
                        val targetView = WeatherIcon.resolveIconHome(iconRes)
                        onViewModeChange(targetView)
                    } else {
                        // Graph body: quick zoom in/out toggle centered on the tapped hour.
                        val clickedTimeMs = start + (offset.x / size.width.toFloat()) * (cutoff - start)
                        val clickedOffset = ((clickedTimeMs - now) / 3_600_000f).roundToInt()
                        onToggleZoom(clickedOffset)
                    }
                }
            }
    ) {
        val windowStart = start
        val windowEnd = cutoff

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
        val rawRange = (rawMax - rawMin).coerceAtLeast(1f)
        val topBuffer = (rawRange * 0.1f).coerceAtLeast(3f)
        val bottomBuffer = (rawRange * 0.03f).coerceAtLeast(2.5f)
        val minTemp = rawMin - bottomBuffer
        val maxTemp = rawMax + topBuffer
        val range = (maxTemp - minTemp).coerceAtLeast(1f)

        val w = size.width
        val h = size.height
        val n = points.size

        // Bottom strip (hour labels + day-night icons): size the reserve to the actual label so it
        // sits tight against the canvas bottom (shared with the precip/cloud graphs via hourlyFooter).
        val footer = hourlyFooter(textMeasurer, scale)
        // Curve runs high to the top; the footer band (+gap) clears the trough off the hour labels.
        // Top still allows a little overlap (peak label on top).
        val top = 2f * scale
        val graphHeight = (footer.graphBottom(h, scale) - top).coerceAtLeast(1f)

        // Map by the actual data span (first..last point) rather than the window, so the rightmost
        // hourly point lands on the right edge and the curve fills the full width (matches Android's
        // index-based hour spacing). NOW/fetch-dot/labels all route through xAtTime, so they stay
        // aligned. windowStart/windowEnd remain the gating window for visibility checks.
        val dataStart = points.first().dateTime
        val dataEnd = points.last().dateTime
        val dataSpan = (dataEnd - dataStart).coerceAtLeast(1L).toFloat()
        // Sub-hour drag slide: data steps on whole hours (via the window), this fills the fraction.
        val dragResidualPx = DesktopGraphUtils.dragResidualPx(dragHours.value, w * 3_600_000f / dataSpan)
        fun xAtTime(t: Long): Float = (((t - dataStart).toFloat() / dataSpan * w) + dragResidualPx).coerceIn(-w, 2 * w)
        fun xAt(i: Int): Float = xAtTime(points[i].dateTime)
        fun yAt(t: Float): Float = top + graphHeight * (1f - (t - minTemp) / range)

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

        drawCloudAndPrecipOverlays(points, scale, ::xAt)

        // Gradient fill always spans the full window under the expected curve
        drawFill(expectedCoords, minTemp, maxTemp, range, scale)

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
                        width = 1.5f * scale,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx() * scale, 4.dp.toPx() * scale))
                    )
                )
            }
        }

        // 2. Ghost Line (Shifted forecast curve representing expected path)
        val transitionX = lastActualPoint?.let { xAtTime(it.timeMs) }
        if (transitionX != null && abs(appliedDelta) >= 0.1f) {
            clipRect(left = transitionX, top = 0f, right = w, bottom = footer.graphBottom(h, scale)) {
                val expectedPath = buildCurve(expectedCoords)
                drawPath(
                    path = expectedPath,
                    color = Color.White.copy(alpha = 0.22f),
                    style = Stroke(
                        width = 1.2.dp.toPx() * scale,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f * scale, 4.dp.toPx() * scale))
                    )
                )
            }
        }

        // 3. Actual (Solid Pink) for observations
        if (actualLinePoints.size >= 2) {
            val obsCoords = actualLinePoints.map { point -> Offset(xAtTime(point.timeMs), yAt(point.actualTemp!!)) }
            drawActualLine(obsCoords, scale)
        }

        val markerX = xAtTime(now)

        // NOW Indicator - Line (drawn early so it's behind labels; shared helper).
        if (now in windowStart..windowEnd) {
            drawNowLine(markerX, top, graphHeight, scale)
        }

        // Peak labels (Hi / Lo / Now) anchored to forecast extremes
        val highIdx = forecastTemps.indexOf(fMax)
        val lowIdx = forecastTemps.indexOf(fMin)
        val endIdx = points.lastIndex

        val drawnLabels = mutableListOf<Rect>()
        // Fetch-dot value/age rects, treated as HARD obstacles by the label engine so a colliding
        // label (e.g. a valley forecast LOW) flips above the curve instead of drawing over the pink
        // dot value. See plans/samsung-clash-of-labels-*.md.
        val fetchDotHardBounds = mutableListOf<Rect>()

        // Fetch dot and value/age labels
        val tMs = transitionMs
        val fetchDotPoint = if (tMs != null) actualSeries.points.firstOrNull { it.timeMs == tMs && it.actualTemp != null } else null
        var fetchDotXVal: Float? = null
        var fetchDotYVal: Float? = null

        if (tMs != null && fetchDotPoint != null && tMs in windowStart..windowEnd) {
            val fetchDotX = xAtTime(tMs)
            val fetchDotY = yAt(fetchDotPoint.actualTemp!!)
            fetchDotXVal = fetchDotX
            fetchDotYVal = fetchDotY
            
            val tempVal = fetchDotPoint.actualTemp!!
            val tempText = if (tempVal % 1.0f == 0f) "${tempVal.roundToInt()}°" else String.format(Locale.US, "%.1f°", tempVal)
            val valueTextLayout = textMeasurer.measure(
                tempText,
                TextStyle(fontSize = (TEMP_VALUE_LABEL_SP * scale).sp, color = COLOR_ACTUAL, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            )
            val valueWidth = valueTextLayout.size.width.toFloat()
            val valueHeight = valueTextLayout.size.height.toFloat()
            val sideGap = 4.dp.toPx() * scale
            val dotRadius = 4.5f * scale
            
            val rightX = fetchDotX + dotRadius + sideGap
            val rightY = fetchDotY - valueHeight / 2f
            
            val leftX = fetchDotX - dotRadius - sideGap - valueWidth
            val leftY = fetchDotY - valueHeight / 2f
            
            val aboveX = fetchDotX - valueWidth / 2f
            val aboveY = fetchDotY - dotRadius - sideGap - valueHeight
            
            val rect = when {
                rightX + valueWidth <= w -> Rect(Offset(rightX, rightY), Size(valueWidth, valueHeight))
                leftX >= 0 -> Rect(Offset(leftX, leftY), Size(valueWidth, valueHeight))
                else -> Rect(Offset(aboveX.coerceIn(0f, w - valueWidth), aboveY), Size(valueWidth, valueHeight))
            }
            
            drawShadowedText(textMeasurer, valueTextLayout, rect.topLeft, scale)
            drawnLabels.add(rect)
            fetchDotHardBounds.add(rect)
            
            // Staleness age label (mirrors Android: any non-negative age, but only in a ≤12h window
            // so it shows in the zoomed-in view and hides in the wide 24h view). Drawn in the actual
            // line's pink (#FF3366) with a dark shadow, placed above the dot like Android — flipping
            // below only on collision / if it would clip off the top.
            val ageMinutes = (now - tMs) / 60000L
            val spanHours = (windowEnd - windowStart) / 3_600_000L
            val ageLabelText = formatAgeLabel(ageMinutes, spanHours)
            if (ageLabelText != null) {
                val ageTextLayout = textMeasurer.measure(
                    ageLabelText,
                    TextStyle(
                        fontSize = (9 * scale).sp,
                        color = COLOR_ACTUAL,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.7f),
                            offset = Offset(0f, 1f * scale),
                            blurRadius = 2f * scale
                        )
                    )
                )
                val ageWidth = ageTextLayout.size.width.toFloat()
                val ageHeight = ageTextLayout.size.height.toFloat()
                val padding = 4.dp.toPx() * scale

                val aboveY = fetchDotY - dotRadius - padding - ageHeight
                val aboveRect = Rect(Offset(fetchDotX - ageWidth / 2f, aboveY), Size(ageWidth, ageHeight))
                val collisionAbove = drawnLabels.any { it.overlaps(aboveRect.inflate(2.dp.toPx() * scale)) } || (aboveY < 0f)

                val finalAgeRect = if (collisionAbove) {
                    val belowY = fetchDotY + dotRadius + padding
                    val belowRect = Rect(Offset(fetchDotX - ageWidth / 2f, belowY), Size(ageWidth, ageHeight))
                    val collisionBelow = drawnLabels.any { it.overlaps(belowRect.inflate(2.dp.toPx() * scale)) } || (belowY + ageHeight > footer.graphBottom(h, scale))
                    if (collisionBelow) aboveRect else belowRect
                } else {
                    aboveRect
                }

                drawText(ageTextLayout, topLeft = finalAgeRect.topLeft)
                drawnLabels.add(finalAgeRect)
                fetchDotHardBounds.add(finalAgeRect)
            }
        }

        val hourDataList = points.mapIndexed { idx, p ->
            val actualPoint = actualSeries.points.find { it.timeMs == p.dateTime }
            val isAct = actualPoint?.isActual ?: false
            val actTemp = actualPoint?.actualTemp
            val temp = forecastTemps[idx]
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(p.dateTime), zoneId)
            HourData(
                dateTime = dt,
                temperature = temp,
                label = formatHourLabel(dt.hour),
                isActual = isAct,
                actualTemperature = actTemp
            )
        }

        val originalPointsList = points.mapIndexed { idx, p ->
            val actualPoint = actualSeries.points.find { it.timeMs == p.dateTime }
            val actTemp = actualPoint?.actualTemp ?: (forecastTemps[idx] + appliedDelta)
            xAt(idx) to yAt(actTemp)
        }
        val forecastPointsList = points.mapIndexed { idx, _ ->
            xAt(idx) to yAt(forecastTemps[idx])
        }
        val actualVisiblePointsList = actualLinePoints.map { point ->
            xAtTime(point.timeMs) to yAt(point.actualTemp!!)
        }

        val effectiveActualEndIndex = if (transitionMs != null) {
            points.indexOfLast { it.dateTime <= transitionMs }
        } else {
            -1
        }
        val fetchTime = transitionMs?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zoneId)
        }

        val textStyle = TextStyle(fontSize = (TEMP_VALUE_LABEL_SP * scale).sp)
        val textHeight = textMeasurer.measure("80°", textStyle).size.height.toFloat()
        val metricsAdapter = object : LabelTextMetrics {
            override val ascent: Float = -textHeight
            override val descent: Float = 0f
            override val height: Float = textHeight
            override fun width(text: String, isFuture: Boolean): Float {
                return textMeasurer.measure(text, textStyle).size.width.toFloat()
            }
        }

        val placements = TemperatureLabelEngine.computePlacements(
            hours = hourDataList,
            widthPx = w.toInt(),
            heightPx = h.toInt(),
            density = scale,
            originalPoints = originalPointsList,
            forecastPoints = forecastPointsList,
            actualVisiblePoints = actualVisiblePointsList,
            transitionX = transitionMs?.let { xAtTime(it) },
            fetchDotX = fetchDotXVal,
            lastObservedTemp = fetchDotPoint?.actualTemp,
            observedAt = transitionMs,
            effectiveActualEndIndex = effectiveActualEndIndex,
            fetchTime = fetchTime,
            numColumns = points.size,
            tempToY = { yAt(it) },
            metrics = metricsAdapter,
            drawnIconBounds = drawnLabels.map { GraphRect(it.left, it.top, it.right, it.bottom) },
            reservedHardBounds = fetchDotHardBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) }
        )

        for (label in placements) {
            val color = when (label.role) {
                TemperatureRole.HIGH, TemperatureRole.LOW -> Color.White
                TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW -> Color.White
                TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW -> Color.White
                TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW, TemperatureRole.ACTUAL_END -> COLOR_ACTUAL
                TemperatureRole.LOCAL -> Color.White
                TemperatureRole.START, TemperatureRole.END -> Color.White.copy(alpha = 0.6f)
                else -> Color.White
            }
            val textLayout = textMeasurer.measure(label.text, TextStyle(fontSize = (TEMP_VALUE_LABEL_SP * scale).sp, color = color))
            val textWidth = textLayout.size.width.toFloat()
            val textHeight = textLayout.size.height.toFloat()

            val textTop = label.baselineY - textHeight
            val labelRect = Rect(
                offset = Offset(label.x - textWidth / 2f, textTop),
                size = Size(textWidth, textHeight)
            )

            drawShadowedText(textMeasurer, textLayout, labelRect.topLeft, scale)
            drawnLabels.add(labelRect)

            if (label.drawLeaderLine) {
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(label.x, label.leaderFromY),
                    end = Offset(label.x, label.leaderToY),
                    strokeWidth = 0.5.dp.toPx() * scale
                )
            }
        }

        // Wide multi-day spans label the footer with dates instead of times; the interior edge
        // day-of-week labels are then redundant, so only draw them at narrower (time-label) zooms.
        if (!DesktopGraphUtils.isDateMode(totalSpanHours)) {
            drawDayLabels(
                leftDate = Instant.ofEpochMilli(windowStart).atZone(ZoneId.systemDefault()).toLocalDate(),
                rightDate = Instant.ofEpochMilli(windowEnd).atZone(ZoneId.systemDefault()).toLocalDate(),
                textMeasurer = textMeasurer,
                occupied = drawnLabels,
                scale = scale,
            )
        }

        // Bottom strip (hour/date labels + weather icons) — shared with the precip/cloud graphs.
        drawHourlyFooterStrip(points, painters, totalSpanHours, latitude, longitude, footer, w, h, textMeasurer, scale, ::xAt)

        // Draw fetch dot (circle rings) if it exists
        if (fetchDotXVal != null && fetchDotYVal != null && fetchDotPoint != null) {
            val dotRadius = 4.5f * scale
            val outerRadius = dotRadius + 0.75f * scale
            drawCircle(color = Color.Black.copy(alpha = 0.26f), radius = outerRadius, center = Offset(fetchDotXVal, fetchDotYVal))
            drawCircle(color = Color.White, radius = dotRadius, center = Offset(fetchDotXVal, fetchDotYVal))
            drawCircle(color = tempToColor(fetchDotPoint.actualTemp!!), radius = dotRadius - 1.5f * scale, center = Offset(fetchDotXVal, fetchDotYVal))
        }

        // NOW label drawn last (on top), collision-aware against the placed labels (shared helper).
        if (now in windowStart..windowEnd) {
            drawNowLabel(markerX, top, graphHeight, scale, textMeasurer, drawnLabels)
        }
    }
}

private fun DrawScope.drawDayLabels(
    leftDate: LocalDate,
    rightDate: LocalDate,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    occupied: MutableList<Rect>,
    scale: Float,
) {
    val today = LocalDate.now()
    val dates = listOf(0f to leftDate, size.width to rightDate)
    dates.forEach { (edgeX, date) ->
        val isToday = date == today
        val color = if (isToday) Color.Yellow.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.45f)
        val text = date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
        val layout = textMeasurer.measure(text, TextStyle(fontSize = (14 * scale).sp, color = color))
        val x = edgeX.coerceIn(layout.size.width / 2f, size.width - layout.size.width / 2f)
        val candidates = listOf(8f * scale, size.height * 0.48f, size.height - 48f * scale)
        val y = candidates.firstOrNull { top ->
            val rect = Rect(
                offset = Offset(x - layout.size.width / 2f, top),
                size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
            )
            occupied.none { it.overlaps(rect.inflate(4f * scale)) }
        } ?: candidates.last()
        val rect = Rect(
            offset = Offset(x - layout.size.width / 2f, y),
            size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
        )
        drawText(layout, topLeft = rect.topLeft)
        occupied.add(rect)
    }
}

private fun formatHourLabel(hour: Int): String = DesktopGraphUtils.formatHourLabel(hour)

private fun DrawScope.drawCloudAndPrecipOverlays(points: List<HourlyForecast>, scale: Float, xAt: (Int) -> Float) {
    if (points.isEmpty()) return
    val bandBottom = size.height - 44f * scale
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
            val barHeight = (precipSignal * 18f * scale).coerceAtLeast(2f * scale)
            drawLine(
                color = Color(0xFF4FC3F7).copy(alpha = 0.35f + 0.45f * precipSignal),
                start = Offset(xAt(i), bandBottom - barHeight),
                end = Offset(xAt(i), bandBottom),
                strokeWidth = 2.5f * scale,
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

private fun computeTangents(coords: List<Offset>): List<Offset> = DesktopGraphUtils.computeTangents(coords)

private fun buildCurve(coords: List<Offset>): Path = DesktopGraphUtils.buildCurve(coords)

private fun DrawScope.drawFill(coords: List<Offset>, minTemp: Float, maxTemp: Float, range: Float, scale: Float) {
    val h = size.height
    val graphBottom = h - 44f * scale
    val fill = Path().apply {
        if (coords.isNotEmpty()) {
            addPath(buildCurve(coords))
            lineTo(coords.last().x, graphBottom)
            lineTo(coords.first().x, graphBottom)
            close()
        }
    }
    val stops = buildColorStops(minTemp, maxTemp, range)
    val fillStops = stops.map { (pos, color) -> pos to color.copy(alpha = 0.40f * (1f - pos)) }.toTypedArray()
    drawPath(fill, brush = Brush.verticalGradient(colorStops = fillStops, startY = 0f, endY = graphBottom))
}

private fun DrawScope.drawCurveLine(
    coords: List<Offset>,
    minTemp: Float,
    maxTemp: Float,
    range: Float,
    dashed: Boolean,
    scale: Float,
    alpha: Float = 1f,
) {
    val h = size.height
    val graphBottom = h - 44f * scale
    val pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(16f * scale, 8f * scale)) else null
    drawPath(
        buildCurve(coords),
        brush = Brush.verticalGradient(
            colorStops = buildColorStops(minTemp, maxTemp, range).map { (pos, color) -> 
                pos to color.copy(alpha = alpha) 
            }.toTypedArray(),
            startY = 0f,
            endY = graphBottom,
        ),
        style = Stroke(width = 3f * scale, pathEffect = pathEffect),
    )
}

private fun DrawScope.drawActualLine(coords: List<Offset>, scale: Float) {
    drawPath(
        buildCurve(coords),
        color = COLOR_ACTUAL,
        style = Stroke(width = 1.5f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun List<HourlyForecast>.indexOfByClosestTime(targetTime: Long): Int = DesktopGraphUtils.run { indexOfByClosestTime(targetTime) }
