package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.weatherwidget.shared.graph.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import java.time.format.TextStyle as JavaTextStyle

private val COLOR_CLOUD_CURVE = Color(0xFFAAAAAA)
private val COLOR_CLOUD_FILL_START = Color(0xFF8E99A4).copy(alpha = 0.22f)
private val COLOR_CLOUD_FILL_END = Color.Transparent

private fun forecastColor(flags: com.weatherwidget.shared.util.WeatherConditionResolver.ConditionFlags): Color {
    val argb = com.weatherwidget.shared.util.WeatherColors.forecastColor(flags.isSunny, flags.isRainy, flags.isMixed, flags.isNight, flags.isTwilight)
    return Color(argb)
}


@Composable
fun CloudCoverGraph(
    hourly: List<HourlyForecast>,
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
    val now = System.currentTimeMillis()
    val dragHours = remember { mutableStateOf(0f) }
    val center = now + (centerOffsetHours + dragHours.value.roundToInt()) * 3_600_000L

    val backHours = DesktopGraphUtils.backHoursFor(zoomFactor)
    val forwardHours = DesktopGraphUtils.forwardHoursFor(zoomFactor)
    val totalSpanHours = backHours + forwardHours

    val start = center - backHours * 3_600_000L
    val cutoff = center + forwardHours * 3_600_000L

    val points = remember(hourly, start, cutoff) {
        hourly.filter { it.dateTime in (start - 3_600_000L)..cutoff }
            .sortedBy { it.dateTime }
            .ifEmpty { hourly.sortedBy { it.dateTime }.take(backHours + forwardHours + 1) }
    }

    val painters: List<Painter> = points.map { painterResource(WeatherIcon.getIconResource(it.condition)) }

    val smoothIterations = DesktopGraphUtils.smoothIterationsFor(totalSpanHours)

    if (points.size < 2) return

    val watermarkPainter = painterResource("drawable/ic_weather_mostly_cloudy.xml")

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
            .pointerInput(points, zoomFactor, scale) {
            detectTapGestures { offset ->
                if (offset.y >= size.height - 44.dp.toPx() * scale) {
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

        val rawCloudValues = points.map { it.cloudCover?.toFloat() ?: 0f }
        val smoothedClouds = com.weatherwidget.shared.util.TemperatureInterpolator.smoothValuesPreservingAllExtrema(rawCloudValues, smoothIterations)
        
        val visibleMax = smoothedClouds.maxOrNull()?.coerceIn(0f, 100f) ?: 0f
        val topScale = (visibleMax + 12f).coerceIn(85f, 100f)

        val w = size.width
        val h = size.height

        val graphTop = 38.dp.toPx() * scale
        // Footer band sized to the actual label so hour labels sit flush at the bottom (shared with
        // the temperature/precip graphs via hourlyFooter).
        val footer = hourlyFooter(textMeasurer, scale)
        val graphBottom = footer.graphBottom(h, scale)
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        // Map by the actual data span (first..last point) rather than the window, so the rightmost
        // hourly point lands on the right edge and the curve fills the full width (matches the
        // temperature/precip graphs; fixes the gap on the far right when data stops short of
        // windowEnd). NOW/labels route through xAtTime, so they stay aligned; windowStart/windowEnd
        // remain the visibility gate below.
        val dataStart = points.first().dateTime
        val dataEnd = points.last().dateTime
        val dataSpan = (dataEnd - dataStart).coerceAtLeast(1L).toFloat()
        val dragResidualPx = DesktopGraphUtils.dragResidualPx(dragHours.value, w * 3_600_000f / dataSpan)
        fun xAtTime(t: Long): Float = (((t - dataStart).toFloat() / dataSpan * w) + dragResidualPx).coerceIn(-w, 2 * w)
        fun xAt(i: Int): Float = xAtTime(points[i].dateTime)
        fun yAt(cover: Float): Float {
            val clamped = cover.coerceIn(0f, 100f)
            return graphBottom - graphHeight * (clamped / topScale)
        }

        val coords = points.mapIndexed { i, _ -> Offset(xAt(i), yAt(smoothedClouds[i])) }

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
            colors = listOf(COLOR_CLOUD_FILL_START, COLOR_CLOUD_FILL_END),
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
            color = COLOR_CLOUD_CURVE,
            style = Stroke(width = curveStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Value (%) labels (peak / dip / start / end) via the shared ValueLabelEngine — the same
        // engine used by the precip graph and the Android renderers. Compose draws top-left anchored,
        // so we treat the measured box bottom as the baseline (ascent = -height, descent = 0).
        val labelSignal = smoothedClouds.map { it.roundToInt().coerceIn(0, 100) }
        val labelStyle = TextStyle(fontSize = (11 * scale).sp, color = Color.White)
        val labelHeight = textMeasurer.measure("0%", labelStyle).size.height.toFloat()
        val placements = ValueLabelEngine.computePlacements(
            labelSignal = labelSignal,
            points = coords.map { ValueLabelEngine.GraphPoint(it.x, it.y) },
            geometry = ValueLabelEngine.Geometry(graphTop, graphBottom, graphHeight, w, h),
            config = ValueLabelEngine.Config.cloud(),
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

        // Draw Cloud Watermark in emptiest region
        val windowSize = (points.size / 5).coerceIn(3, 6)
        val candidateCenters = (0..points.size - windowSize)
            .map { start ->
                val avg = (start until start + windowSize).map { smoothedClouds[it] }.average().toFloat()
                val center = start + windowSize / 2
                val edgeDistance = minOf(center, points.lastIndex - center)
                Triple(center, avg, edgeDistance)
            }
            .sortedWith(compareBy<Triple<Int, Float, Int>> { it.second }.thenByDescending { it.third })
            .map { it.first }
            .distinct()

        var watermarkPlaced = false
        val watermarkIconSize = 48.dp.toPx()
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

        // Draw Missing Data Diagnostic if needed
        val expectedTotalPoints = totalSpanHours + 1
        if (points.size < expectedTotalPoints) {
            val msg = if (points.isEmpty()) "Cloud data unavailable" else "Cloud data missing for ${expectedTotalPoints - points.size} of $expectedTotalPoints hrs"
            val textLayout = textMeasurer.measure(
                text = msg,
                style = TextStyle(
                    fontSize = 10.sp,
                    color = Color(0xFFDDC8CFD8)
                )
            )
            drawText(
                textLayoutResult = textLayout,
                topLeft = Offset(w / 2f - textLayout.size.width / 2f, h / 2f - textLayout.size.height / 2f)
            )
        }

        // Multi-day spans label the footer with dates instead of times; suppress the redundant
        // interior edge day-labels then (parity with the temperature graph).
        if (!DesktopGraphUtils.isDateMode(totalSpanHours)) {
            drawDayLabels(
                leftDate = Instant.ofEpochMilli(windowStart).atZone(ZoneId.systemDefault()).toLocalDate(),
                rightDate = Instant.ofEpochMilli(windowEnd).atZone(ZoneId.systemDefault()).toLocalDate(),
                textMeasurer = textMeasurer,
                occupied = drawnLabels,
                scale = scale,
            )
        }

        // Bottom strip (hour/date labels + weather icons) — shared with the temperature/precip graphs.
        drawHourlyFooterStrip(points, painters, totalSpanHours, latitude, longitude, footer, w, h, textMeasurer, scale, ::xAt)

        // NOW label drawn last (on top), collision-aware against the placed labels.
        if (now in windowStart..windowEnd) {
            drawNowLabel(markerX, graphTop, graphHeight, scale, textMeasurer, drawnLabels)
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
        val layout = textMeasurer.measure(text, TextStyle(fontSize = (10 * scale).sp, color = color))
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

private fun computeTangents(coords: List<Offset>): List<Offset> = DesktopGraphUtils.computeTangents(coords)

private fun buildCurve(coords: List<Offset>): Path = DesktopGraphUtils.buildCurve(coords)
