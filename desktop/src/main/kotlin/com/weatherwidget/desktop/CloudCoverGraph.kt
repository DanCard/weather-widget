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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
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

    val iconSpacing = if (points.size > 24) 4 else if (points.size > 12) 3 else 2
    val painters = mutableListOf<Painter?>()
    for (i in points.indices) {
        painters.add(if (i % iconSpacing == 0) painterResource(WeatherIcon.getIconResource(points[i].condition)) else null)
    }

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
        val windowSpan = (windowEnd - windowStart).coerceAtLeast(1L).toFloat()

        val rawCloudValues = points.map { it.cloudCover?.toFloat() ?: 0f }
        val smoothedClouds = com.weatherwidget.shared.util.TemperatureInterpolator.smoothValuesPreservingAllExtrema(rawCloudValues, smoothIterations)
        
        val visibleMax = smoothedClouds.maxOrNull()?.coerceIn(0f, 100f) ?: 0f
        val topScale = (visibleMax + 12f).coerceIn(85f, 100f)

        val w = size.width
        val h = size.height

        val graphTop = 38.dp.toPx() * scale
        val footerIconSize = 18.dp.toPx() * scale
        val bottomInset = 4.dp.toPx() * scale
        val graphBottom = h - footerIconSize - bottomInset
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        val dragResidualPx = DesktopGraphUtils.dragResidualPx(dragHours.value, w * 3_600_000f / windowSpan)
        fun xAtTime(t: Long): Float = (((t - windowStart).toFloat() / windowSpan * w) + dragResidualPx).coerceIn(-w, 2 * w)
        fun xAt(i: Int): Float = xAtTime(points[i].dateTime)
        fun yAt(cover: Float): Float {
            val clamped = cover.coerceIn(0f, 100f)
            return graphBottom - graphHeight * (clamped / topScale)
        }

        val coords = points.mapIndexed { i, _ -> Offset(xAt(i), yAt(smoothedClouds[i])) }

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

        // Draw Curve Line
        val curveStroke = if (totalSpanHours <= 8) 2.dp.toPx() * scale else 3.dp.toPx() * scale
        drawPath(
            path = buildCurve(coords),
            color = COLOR_CLOUD_CURVE,
            style = Stroke(width = curveStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Draw Now vertical dashed guide line
        val nowIdx = points.indexOfByClosestTime(now)
        val markerCloud = smoothedClouds[nowIdx]
        val markerX = xAtTime(now)
        val markerY = yAt(markerCloud)
        if (now in windowStart..windowEnd) {
            drawLine(
                color = Color.White.copy(alpha = 0.36f),
                start = Offset(markerX, graphTop),
                end = Offset(markerX, graphBottom),
                strokeWidth = 1.dp.toPx() * scale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx() * scale, 3.dp.toPx() * scale))
            )
            drawCircle(color = Color.White, radius = 4.5f * scale, center = Offset(markerX, markerY))
            drawCircle(color = COLOR_CLOUD_CURVE, radius = 2.5f * scale, center = Offset(markerX, markerY))
        }

        // Extrema Label Placement
        val labelSignal = smoothedClouds.map { it.roundToInt().coerceIn(0, 100) }
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
        if (globalMaxIdx >= 0) candidates.add(globalMaxIdx)
        if (globalMinIdx >= 0 && globalMinIdx != globalMaxIdx) candidates.add(globalMinIdx)
        if (0 !in candidates) candidates.add(0)
        if (points.lastIndex !in candidates && points.isNotEmpty()) candidates.add(points.lastIndex)
        candidates.addAll(softDipCandidates)
        candidates.sortBy { it }

        // Filter dense labels roughly
        val finalCandidates = candidates.distinct()

        val drawnLabels = mutableListOf<Rect>()
        for (index in finalCandidates) {
            if (index !in labelSignal.indices) continue
            val cloudPct = labelSignal[index]
            val labelText = "$cloudPct%"
            
            val textLayout = textMeasurer.measure(labelText, TextStyle(fontSize = (11 * scale).sp, color = Color.White))
            val textWidth = textLayout.size.width.toFloat()
            val textHeight = textLayout.size.height.toFloat()
            
            val centerX = coords[index].x
            val pointY = coords[index].y

            val isPeak = index == globalMaxIdx || (index > 0 && index < labelSignal.lastIndex &&
                labelSignal[index] > labelSignal[index - 1] && labelSignal[index] > labelSignal[index + 1])
            val isEndLabelCandidate = index == points.lastIndex
            val isRisingAtEnd = isEndLabelCandidate && index > 0 && coords[index].y < coords[index - 1].y - 2f * scale
            val isFallingFromLeftEdge = index == 0 && points.size > 1 && coords[1].y > coords[0].y + 2f * scale
            val preferAbove = isPeak || isRisingAtEnd || isFallingFromLeftEdge

            val attempts = if (preferAbove) listOf(true, false) else listOf(false, true)
            var placed = false

            for (placeAbove in attempts) {
                val gapPx = 6.dp.toPx() * scale
                val textTop = if (placeAbove) {
                    val curveMinY = minOf(getCurveYAtX(centerX - textWidth / 2f), pointY, getCurveYAtX(centerX + textWidth / 2f))
                    curveMinY - gapPx - textHeight
                } else {
                    val curveMaxY = maxOf(getCurveYAtX(centerX - textWidth / 2f), pointY, getCurveYAtX(centerX + textWidth / 2f))
                    curveMaxY + gapPx
                }

                val x = centerX.coerceIn(textWidth / 2f, w - textWidth / 2f)
                val bounds = Rect(offset = Offset(x - textWidth / 2f, textTop), size = Size(textWidth, textHeight))

                val safeBottom = graphBottom - 10.dp.toPx() * scale
                if (bounds.top < 0f || bounds.bottom > safeBottom) continue

                val overlaps = drawnLabels.any { it.overlaps(bounds.inflate(4.dp.toPx() * scale)) }
                if (!overlaps) {
                    drawText(textLayout, topLeft = bounds.topLeft)
                    drawnLabels.add(bounds)
                    placed = true
                    
                    // Draw leader line if shifted significantly
                    val baselineTop = if (placeAbove) pointY - gapPx - textHeight else pointY + gapPx
                    val shift = abs(textTop - baselineTop)
                    if (shift > 1.5f * scale) {
                        val leaderLineStart = Offset(centerX, pointY)
                        val leaderLineEnd = if (placeAbove) Offset(centerX, textTop + textHeight) else Offset(centerX, textTop)
                        drawLine(
                            color = Color.White.copy(alpha = 0.35f),
                            start = leaderLineStart,
                            end = leaderLineEnd,
                            strokeWidth = 0.5.dp.toPx() * scale
                        )
                    }
                    break
                }
            }
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

        // Draw Day Labels at bottom
        drawDayLabels(
            leftDate = Instant.ofEpochMilli(windowStart).atZone(ZoneId.systemDefault()).toLocalDate(),
            rightDate = Instant.ofEpochMilli(windowEnd).atZone(ZoneId.systemDefault()).toLocalDate(),
            textMeasurer = textMeasurer,
            occupied = drawnLabels,
            scale = scale,
        )

        // Draw Bottom Strip: icons + hour labels
        val labelInterval = DesktopGraphUtils.labelIntervalFor(totalSpanHours)
        for (i in points.indices) {
            val p = points[i]
            val localZdt = Instant.ofEpochMilli(p.dateTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
            if (localZdt.hour % labelInterval != 0) continue
            val x = xAt(i)
            
            val time = Instant.ofEpochMilli(p.dateTime)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
            val timeStr = formatHourLabel(time.hour)
            
            val textLayout = textMeasurer.measure(timeStr, TextStyle(fontSize = (9 * scale).sp, color = Color.Gray))
            val textW = textLayout.size.width.toFloat()
            val textH = textLayout.size.height.toFloat()
            
            val yOffset = h - 22f * scale
            val textY = yOffset - textH / 2f
            
            val isLast = i == points.lastIndex || (x + (textW + 14.dp.toPx() * scale) / 2f > w)
            
            if (!isLast && painters[i] != null) {
                val iconSize = 12.dp.toPx() * scale
                val gap = 2.dp.toPx() * scale
                val totalW = textW + gap + iconSize
                
                val startX = x - totalW / 2f
                val textTopLeft = Offset(startX.coerceAtLeast(4f * scale), textY)
                drawText(textLayout, topLeft = textTopLeft)
                
                val iconLeft = startX + textW + gap
                val iconTop = yOffset - iconSize / 2f
                
                painters[i]?.let { painter ->
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
                    translate(iconLeft, iconTop) {
                        with(painter) { draw(size = Size(iconSize, iconSize), colorFilter = filter) }
                    }
                }
            } else {
                val textTopLeft = Offset(x - textW / 2f, textY)
                drawText(textLayout, topLeft = textTopLeft)
            }
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

private fun formatHourLabel(hour: Int): String = DesktopGraphUtils.formatHourLabel(hour)

private fun computeTangents(coords: List<Offset>): List<Offset> = DesktopGraphUtils.computeTangents(coords)

private fun buildCurve(coords: List<Offset>): Path = DesktopGraphUtils.buildCurve(coords)

private fun List<HourlyForecast>.indexOfByClosestTime(targetTime: Long): Int = DesktopGraphUtils.run { indexOfByClosestTime(targetTime) }
