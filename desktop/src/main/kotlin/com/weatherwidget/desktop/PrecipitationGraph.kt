package com.weatherwidget.desktop

import com.weatherwidget.shared.observations.NwsBlend
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.graph.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Rain-period types now live in :shared alongside the selection logic. */
private typealias RainPeriod = RainPeriodSelection.RainPeriod

private val COLOR_PRECP_CURVE = Color(0xFF5AC8FA)
/** The 15-minute nowcast bars: the header rain %'s blue, opaque enough to read over the fill. */
private val COLOR_NOWCAST_BAND = Color(0xB34FC3F7)
private val COLOR_PRECP_FILL_START = Color(0xFF5AC8FA).copy(alpha = 0.27f)
private val COLOR_PRECP_FILL_END = Color.Transparent
private val COLOR_RAIN_AMOUNT = Color(0xFFFFFFFF)
private val COLOR_ACTUAL_RAIN_AMOUNT = Color(0xFFFF9F0A)
// Kept subtle on desktop (Compose renders crisper than Android, where the same line is barely
// visible); Android uses #66FFFFFF (~0.4 alpha) and is untouched.
private val COLOR_DAY_NIGHT_DIVIDER = Color.White.copy(alpha = 0.2f)




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
    onViewModeChange: (ViewMode) -> Unit = {},
    onToggleZoom: (clickedOffset: Int) -> Unit = {},
    onZoomScroll: (deltaZoom: Float, centerOffset: Int) -> Unit = { _, _ -> },
    onPan: (deltaHours: Int) -> Unit = {},
    onOpenSimLoop: () -> Unit = {},
    onOpenRadar: () -> Unit = {},
    /** Open-Meteo's next 2 h at 15 min (`:shared` NowcastDisplay.bandSlots); empty draws no band. */
    nowcastSlots: List<com.weatherwidget.shared.nowcast.NowcastSlot> = emptyList(),
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
    // Where the canvas drew the "Sim loop" and "Radar" pills (px), so the clickable overlays can sit on them.
    var simLoopPill by remember { mutableStateOf<Rect?>(null) }
    var radarPill by remember { mutableStateOf<Rect?>(null) }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxSize().hourlyGraphTapInput(
            start = start,
            cutoff = cutoff,
            nowMs = now,
            spanHours = totalSpanHours,
            dragHours = dragHours,
            points = points,
            zoomFactor = zoomFactor,
            scale = scale,
            onViewModeChange = onViewModeChange,
            onToggleZoom = onToggleZoom,
            onZoomScroll = onZoomScroll,
            onPan = onPan,
        )
    ) {
        val windowStart = start
        val windowEnd = cutoff

        val rawProbs = points.map { it.precipProbability?.toFloat() ?: 0f }
        val smoothedProbs = com.weatherwidget.shared.graph.SeriesSmoothing.smoothValuesPreservingAllExtrema(rawProbs, smoothIterations)
        
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

        // The 15-minute nowcast: bars along the plot's bottom, over the fill, under every label
        // (`:shared` NowcastBand, the same geometry as Android's).
        if (nowcastSlots.isNotEmpty() && points.isNotEmpty()) {
            val t0 = points.first().dateTime
            val x0 = xAtTime(t0)
            val hourPx = xAtTime(t0 + 60 * 60 * 1000L) - x0
            val bandMax = graphHeight * com.weatherwidget.shared.nowcast.NowcastBand.BAND_HEIGHT_FRACTION
            for (bar in com.weatherwidget.shared.nowcast.NowcastBand.bars(nowcastSlots, t0, hourPx)) {
                val top = graphBottom - bandMax * bar.heightFraction
                drawRect(
                    color = COLOR_NOWCAST_BAND,
                    topLeft = Offset(x0 + bar.leftX, top),
                    size = androidx.compose.ui.geometry.Size(bar.rightX - bar.leftX, graphBottom - top),
                )
            }
        }

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

        // Resolving rain periods & drawing rain amount labels. Both series come from ONE shared call
        // (the same RainPeriodSelection Android uses), because the desktop copy this replaced summed
        // the forecast field for the actual series and had no now-gate — so "Actual: " printed the
        // forecast number, at anchors that could sit entirely in the future. Observed 2026-09-01 on
        // Silurian; see plans/260901-share-rain-period-selection.md.
        val rainHours = buildRainHours(
            points = points,
            observations = actualPrecipRowsForSource(observations, displaySourceId),
            now = now,
        )
        val selectedRain = RainPeriodSelection.selectPeriods(
            hours = rainHours,
            mode = RainPeriodSelection.Mode.DAY_NIGHT,
            hourWidth = stepWidth,
        )
        val forecastRainPeriods = selectedRain.forecast
        val actualRainPeriods = selectedRain.actual

        val rainPlacements = calculateRainAmountPlacements(
            rainPeriods = forecastRainPeriods,
            widthPx = w,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            initialCollisionBounds = drawnLabels,
            labelPrefix = "Pred ",
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
            labelPrefix = "Act ",
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
                    drawnLabels.add(bounds)
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

        // "Radar" then "Sim loop" pills in the plot's empty space — same shared rule as Android's bitmap.
        val pxPerDp = 1.dp.toPx() * scale
        val pillStyle = TextStyle(fontSize = (11 * scale).sp, color = Color.White, fontWeight = FontWeight.Bold)
        val pillText = textMeasurer.measure(SIM_LOOP_LABEL, pillStyle)
        val radarText = textMeasurer.measure(RADAR_LABEL, pillStyle)
        val plotRect = GraphRect(0f, graphTop, w, graphBottom)
        // Open-Meteo shown → its own rain map (global); any other source → WeatherNext's rule.
        val simLoopOffered = displaySourceId == com.weatherwidget.data.model.WeatherSource.OPEN_METEO.id ||
            com.weatherwidget.data.remote.weathernext.WeatherNextAvailability.showPill(
                latitude, longitude,
            )
        // Radar is observed and source-independent: offered wherever the popup has a location.
        val radarOffered = latitude != 0.0 || longitude != 0.0
        val pills = SimLoopPill.placeRadarAndSimLoop(
            plot = plotRect,
            curvePoints = coords.map { it.x to it.y },
            drawnBounds = drawnLabels.map { GraphRect(it.left, it.top, it.right, it.bottom) },
            vetoBounds = (if (now in windowStart..windowEnd) nowLineVetoBounds(markerX, graphTop, graphHeight, scale) else emptyList()) +
                navArrowVetoBounds(plotRect, scale),
            radarWidthPx = if (radarOffered) SimLoopPill.pillWidthPx(radarText.size.width.toFloat(), pxPerDp) else null,
            simLoopWidthPx = if (simLoopOffered) SimLoopPill.pillWidthPx(pillText.size.width.toFloat(), pxPerDp) else null,
            density = pxPerDp,
        )
        fun drawPill(placement: SimLoopPill.Placement?, text: androidx.compose.ui.text.TextLayoutResult): Rect? {
            val pillRect = placement?.box?.let { Rect(it.left, it.top, it.right, it.bottom) } ?: return null
            val radius = CornerRadius(pillRect.height / 2f)
            drawRoundRect(SIM_LOOP_FILL, pillRect.topLeft, pillRect.size, radius)
            drawRoundRect(SIM_LOOP_STROKE, pillRect.topLeft, pillRect.size, radius, style = Stroke(width = pxPerDp))
            drawText(
                text,
                topLeft = Offset(pillRect.center.x - text.size.width / 2f, pillRect.center.y - text.size.height / 2f),
            )
            return pillRect
        }
        val radarRect = drawPill(pills.radar, radarText)
        val pillRect = drawPill(pills.simLoop, pillText)
        if (pillRect != simLoopPill) simLoopPill = pillRect
        if (radarRect != radarPill) radarPill = radarRect
    }

    for ((pill, onOpen) in listOf(simLoopPill to onOpenSimLoop, radarPill to onOpenRadar)) {
        if (pill == null) continue
        // Clickable area a few dp past the drawn pill; the canvas below keeps its own tap handling.
        // The shared placement keeps the pills ≥ CLEARANCE_DP (6 dp) apart, so 3 dp margins never overlap.
        val density = LocalDensity.current
        val padPx = with(density) { (SimLoopPill.CLEARANCE_DP / 2f).dp.toPx() } * scale
        val hit = pill.inflate(padPx)
        with(density) {
            Box(
                Modifier
                    .offset { IntOffset(hit.left.roundToInt(), hit.top.roundToInt()) }
                    .size(hit.width.toDp(), hit.height.toDp())
                    .clickable { onOpen() },
            )
        }
    }
    }
}

private const val SIM_LOOP_LABEL = "🌧 Sim loop"
private const val RADAR_LABEL = "📡 Radar"
private val SIM_LOOP_FILL = Color(0x465AC8FA)
private val SIM_LOOP_STROKE = Color(0x965AC8FA)

private fun buildCurve(coords: List<Offset>): Path = DesktopGraphUtils.buildCurve(coords)

private fun computeTangents(coords: List<Offset>): List<Offset> = DesktopGraphUtils.computeTangents(coords)

/**
 * The observation rows that may legitimately be shown as this source's own measured precipitation.
 *
 * A source with no historical precipitation product contributes nothing: Silurian documents
 * `include_past` as forecast output, so relabelling it "Actual" would be a lie about provenance.
 */
internal fun actualPrecipRowsForSource(
    observations: List<ObservationReading>,
    displaySourceId: String,
): List<ObservationReading> {
    val source = com.weatherwidget.data.model.WeatherSource.fromId(displaySourceId)
    if (!source.historicalDataKind.preservesHistoricalPrecipitation) return emptyList()
    return observations.filter { observation ->
        when (source) {
            com.weatherwidget.data.model.WeatherSource.NWS ->
                observation.api == source.id && observation.stationId != NwsBlend.STATION_ID
            com.weatherwidget.data.model.WeatherSource.TOMORROW_IO ->
                observation.api == source.id &&
                    com.weatherwidget.shared.actuals.TomorrowIoActuals.isAllowedStation(observation.stationId)
            else ->
                observation.api == source.id &&
                    observation.stationId ==
                    com.weatherwidget.shared.actuals.HistoricalActualsBackfill.syntheticStationId(source.id)
        }
    }
}

/**
 * Maps the forecast curve plus this source's observation rows onto the shared row type.
 *
 * The now-gate lives here, and it is the whole point: an hour at or after [now] has not happened, so
 * its actual amount is null no matter what the observation list holds. Without it the "Actual: "
 * label anchors on a future day/night run. Android applies the same gate in `PrecipViewHandler`.
 */
internal fun buildRainHours(
    points: List<HourlyForecast>,
    observations: List<ObservationReading>,
    /** Epoch millis; matches `HourlyGraphSetup.now`. */
    now: Long,
): List<RainPeriodSelection.RainHour> {
    val zoneId = ZoneId.systemDefault()
    val nowLdt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId)
    val actualByHour = mutableMapOf<LocalDateTime, Float>()
    for (obs in observations) {
        val amount = obs.precipAmountMm ?: continue
        val hour = LocalDateTime.ofInstant(Instant.ofEpochMilli(obs.timestamp), zoneId)
            .truncatedTo(ChronoUnit.HOURS)
        // Several readings can land in one hour; the hourly total is their sum.
        actualByHour[hour] = (actualByHour[hour] ?: 0f) + amount
    }
    return points.map { forecast ->
        val hour = LocalDateTime.ofInstant(Instant.ofEpochMilli(forecast.dateTime), zoneId)
        RainPeriodSelection.RainHour(
            dateTime = hour,
            precipAmountMm = forecast.precipAmountMm,
            actualPrecipAmountMm = if (hour.isBefore(nowLdt)) {
                actualByHour[hour.truncatedTo(ChronoUnit.HOURS)]
            } else {
                null
            },
        )
    }
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
    val sharedBounds = initialCollisionBounds.map {
        com.weatherwidget.shared.graph.GraphRect(it.left, it.top, it.right, it.bottom)
    }
    val sharedPlacements = com.weatherwidget.shared.graph.RainAmountLabelPlacer.calculatePlacements(
        rainPeriods = rainPeriods,
        widthPx = widthPx,
        graphTop = graphTop,
        graphBottom = graphBottom,
        graphHeight = graphHeight,
        initialCollisionBounds = sharedBounds,
        labelPrefix = labelPrefix,
        measureText = { textMeasurer.measure(it, textStyle).size.width.toFloat() },
        getTextBounds = {
            val h = textMeasurer.measure(it, textStyle).size.height.toFloat()
            Pair(-h / 2f, h / 2f)
        },
        paddingPx = 4f * dpToPx,
    )
    return sharedPlacements.map {
        RainAmountPlacement(
            text = it.text,
            x = it.x,
            y = it.y,
            bounds = Rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom),
        )
    }
}
