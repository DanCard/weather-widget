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
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

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
    val setup = rememberHourlyGraphSetup(hourly, centerOffsetHours, zoomFactor) ?: return
    val now = setup.now
    val dragHours = setup.dragHours
    val totalSpanHours = setup.totalSpanHours
    val start = setup.start
    val cutoff = setup.cutoff
    val points = setup.points
    val painters = setup.painters
    val smoothIterations = setup.smoothIterations

    val watermarkPainter = painterResource("drawable/ic_weather_mostly_cloudy.xml")

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
            xAt = ::xAt,
        )
    }
}

private fun computeTangents(coords: List<Offset>): List<Offset> = DesktopGraphUtils.computeTangents(coords)

private fun buildCurve(coords: List<Offset>): Path = DesktopGraphUtils.buildCurve(coords)
