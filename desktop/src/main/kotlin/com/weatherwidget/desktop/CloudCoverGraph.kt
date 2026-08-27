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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.*
import com.weatherwidget.shared.graph.CloudLayerGlyphPlacer
import com.weatherwidget.shared.graph.CloudSeriesBuilder
import com.weatherwidget.shared.graph.LayerVertex
import com.weatherwidget.shared.observations.ActualsProviderResolver
import kotlin.math.roundToInt

// Shared palette (CloudCoverGraphPalette) — the Android renderer draws the identical ARGBs. The
// colour-design rationale (complementary 343/163 hues, asymmetric saturation vs lightness) is
// documented there. Local vals keep the Compose Color typing.
private val COLOR_CLOUD_CURVE = Color(CloudCoverGraphPalette.CURVE_FORECAST)
private val COLOR_CLOUD_ACTUAL = Color(CloudCoverGraphPalette.CURVE_ACTUAL)

private const val DOMINANT_VALUE_LABEL_SP = 13f
private const val DOMINANT_STATION_LABEL_SP = 7f
private const val DOMINANT_TIME_LABEL_SP = 11f

private val COLOR_CLOUD_LABEL_FORECAST = Color(CloudCoverGraphPalette.LABEL_FORECAST)

private val COLOR_CLOUD_FILL_START = Color(CloudCoverGraphPalette.CURVE_FORECAST).copy(alpha = 0.22f)
private val COLOR_CLOUD_FILL_END = Color.Transparent

private fun forecastColor(flags: com.weatherwidget.shared.util.WeatherConditionResolver.ConditionFlags): Color {
    val argb = com.weatherwidget.shared.util.WeatherColors.forecastColor(flags.isSunny, flags.isRainy, flags.isMixed, flags.isNight, flags.isTwilight)
    return Color(argb)
}

/**
 * What the free-label search saw, for tests.
 *
 * The cloud graph draws to a Canvas and publishes nothing to the semantics tree, so a placement
 * defect here is invisible to a Compose UI test — which is how the actuals-source annotation came to
 * be drawn straight through the mid/high glyph trails (2026-08-27). The geometry is covered by pure
 * shared tests; what needs a rendered frame is whether this composable actually *hands* the trails
 * to the search. [layerGlyphBounds] is that obstacle list and [actualsSourcePlacement] is what came
 * back, so a test can assert the second clears the first.
 */
data class CloudGraphPlacementDebug(
    val layerGlyphBounds: List<GraphRect>,
    val actualsSourcePlacement: DominantStationLabel.Placement?,
)

/** The exact x-axis domain the cloud Canvas draws, bounded at NOW for actual history. */
internal fun cloudActualPlotRange(points: List<HourlyForecast>, nowMs: Long): LongRange? {
    if (points.isEmpty()) return null
    val startMs = points.first().dateTime
    val endMs = minOf(points.last().dateTime, nowMs)
    return if (startMs <= endMs) startMs..endMs else null
}


@Composable
fun CloudCoverGraph(
    hourly: List<HourlyForecast>,
    /**
     * Day-ago cloud predictions by top-of-hour epoch ms. When present for an elapsed hour, the
     * dashed curve shows that prediction and the solid curve shows what actually happened; when
     * absent, both collapse to the live value and no accuracy claim is implied.
     */
    priorDayCloudForecast: Map<Long, Int> = emptyMap(),
    retroCloudActual: Map<Long, Int> = emptyMap(),
    priorDayBandForecast: Map<Long, com.weatherwidget.shared.graph.CloudBands> = emptyMap(),
    retroCloudBands: Map<Long, com.weatherwidget.shared.graph.CloudBands> = emptyMap(),
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
    /** Test seam; see [CloudGraphPlacementDebug]. Null in production, so the work is never done. */
    onPlacementDebug: ((CloudGraphPlacementDebug) -> Unit)? = null,
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
        modifier = modifier.hourlyGraphTapInput(
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

        // The LIVE/FUTURE curve draws the TOTAL, matching what the frozen forecast and actual
        // curves carry. All three go through VisibleCloudCover so none of them can drift into
        // answering a different question from the others.
        val rawCloudValues = points.map {
            with(com.weatherwidget.shared.util.VisibleCloudCover) { it.visibleCloudCover() }?.toFloat() ?: 0f
        }
        val smoothedClouds = com.weatherwidget.shared.graph.SeriesSmoothing.smoothValuesPreservingAllExtrema(rawCloudValues, smoothIterations)
        
        // The forecast stays hourly because Open-Meteo Previous Runs is hourly-only. The actual is
        // independent and may be 15-minute: never zip it to these forecast vertices or the newest
        // three quarters disappear and the graph looks an hour stale.
        val series = CloudSeriesBuilder.build(
            points, priorDayCloudForecast, retroCloudActual, now,
            priorBands = priorDayBandForecast,
            retroBands = retroCloudBands,
        )
        val frozenByTime = series.filter { it.isFrozen }.associate { it.timeMs to it.forecastCover!! }
        // `rememberHourlyGraphSetup` includes one pre-roll hour and the x geometry maps that first
        // point to the left edge. Clip to that plotted domain, not the minute-bearing nominal start
        // (e.g. 09:50), or valid 09:00–09:45 quarter-hour actuals disappear while 09:00 is visible.
        val actualRange = cloudActualPlotRange(points, now)
        val actualPoints = actualRange?.let { range ->
            CloudActualSeries.points(
                values = retroCloudActual,
                startMs = range.first,
                endMs = range.last,
            )
        }.orEmpty()

        // The forecast curve swaps in the frozen prediction wherever one exists.
        val forecastValues = points.mapIndexed { i, p ->
            frozenByTime[p.dateTime]?.toFloat() ?: rawCloudValues[i]
        }
        val smoothedForecast = com.weatherwidget.shared.graph.SeriesSmoothing
            .smoothValuesPreservingAllExtrema(forecastValues, smoothIterations)

        // Scale must clear EVERY plotted series or the tallest draws off the top. The mid/high
        // layers routinely reach 100% on days the low layer never leaves the axis, so they belong
        // in this max alongside the forecast and actual curves.
        // Keyed by time, not zipped by index: the series drops hours with no visible cloud, so
        // index alignment with `points` (which drives xAt) is not guaranteed.
        val seriesByTime = series.associateBy { it.timeMs }
        // Resolved by CloudSeriesBuilder, NOT read off the live row: for an elapsed hour that row
        // has already been retro-corrected, so it carries the actual wearing the forecast's grey.
        val midCovers = points.map { seriesByTime[it.dateTime]?.forecastBands?.mid }
        val highCovers = points.map { seriesByTime[it.dateTime]?.forecastBands?.high }
        val frozenBands = points.map { seriesByTime[it.dateTime]?.isFrozenBands == true }
        // Only the observed values that will actually be DRAWN feed the scale.
        val actualMidCovers = CloudLayerGlyphPlacer.divergentActuals(
            forecast = midCovers,
            actual = points.map { seriesByTime[it.dateTime]?.actualBands?.mid },
            frozen = frozenBands,
        )
        val actualHighCovers = CloudLayerGlyphPlacer.divergentActuals(
            forecast = highCovers,
            actual = points.map { seriesByTime[it.dateTime]?.actualBands?.high },
            frozen = frozenBands,
        )
        val layerValues =
            (midCovers + highCovers + actualMidCovers + actualHighCovers)
                .filterNotNull().map { it.toFloat() }
        val visibleMax = (smoothedForecast + actualPoints.map { it.cover.toFloat() } + layerValues)
            .maxOrNull()?.coerceIn(0f, 100f) ?: 0f
        val topScale = (visibleMax + 12f).coerceIn(85f, 100f)

        // Plot bounds + x-axis mapping shared with the precip/temperature graphs.
        val geo = hourlyGraphCanvasGeometry(points, textMeasurer, scale, dragHours.value)
        val w = geo.w
        val h = geo.h
        val graphTop = geo.graphTop
        val graphBottom = geo.graphBottom
        val graphHeight = geo.graphHeight
        val footer = geo.footer
        val xAtTime = geo.xAtTime
        val xAt = geo.xAt
        fun yAt(cover: Float): Float {
            val clamped = cover.coerceIn(0f, 100f)
            return graphBottom - graphHeight * (clamped / topScale)
        }

        val coords = points.mapIndexed { i, _ -> Offset(xAt(i), yAt(smoothedForecast[i])) }
        // Straight, timestamp-positioned segments through the provider history. No smoothing: it
        // would invent values, and no bridging across missing intervals.
        val actualCoordSegments = CloudActualSeries.segments(actualPoints).map { segment ->
            segment.map { Offset(xAtTime(it.timeMs), yAt(it.cover.toFloat())) }
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

        // --- Mid/high layer glyph curves ---
        // Each layer is a curve whose line is made of repeated tiny letters -- `m` and `h`. Shared
        // placement math with the Android widget so the two cannot drift; only the text drawing is
        // platform code. Skipped entirely when neither band has anything, which is most days.
        //
        // The ink boxes escape this block because the free-floating actuals-source annotation below
        // has to treat the trails as obstacles; without them it reads a plot full of `h`s as open
        // air. Empty on the common day, which is also when the annotation has the whole plot.
        val layerGlyphBounds = mutableListOf<GraphRect>()
        if (CloudLayerGlyphPlacer.hasVisibleCover(midCovers) ||
            CloudLayerGlyphPlacer.hasVisibleCover(highCovers) ||
            CloudLayerGlyphPlacer.hasVisibleCover(actualMidCovers) ||
            CloudLayerGlyphPlacer.hasVisibleCover(actualHighCovers)
        ) {
            val glyphStyle = TextStyle(
                fontSize = (CloudLayerGlyphPlacer.GLYPH_SIZE_DP * scale).sp,
                color = COLOR_CLOUD_CURVE,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            val glyphStepPx = CloudLayerGlyphPlacer.GLYPH_STEP_DP.dp.toPx() * scale
            val nudgePx = CloudLayerGlyphPlacer.GLYPH_SIZE_DP.dp.toPx() * scale * 0.55f
            // The curve each trail is drawn against, and the highest band BELOW the one being
            // placed. Forecast trails answer to the forecast curve, observed trails to the actual
            // curve; see CloudLayerGlyphPlacer.coincidenceWithTotal.
            val forecastTotals = points.map { seriesByTime[it.dateTime]?.forecastCover }
            val actualTotals = points.map { seriesByTime[it.dateTime]?.actualCover }
            val lows = points.map { it.cloudCoverLow }
            val highLowerBands = points.indices.map { index ->
                listOfNotNull(lows.getOrNull(index), midCovers.getOrNull(index)).maxOrNull()
            }
            val noLowerBand = List<Int?>(points.size) { null }
            fun layerVertices(
                cover: List<Int?>,
                other: List<Int?>,
                totals: List<Int?>,
                lowerBands: List<Int?>,
            ) = cover.mapIndexed { index, value ->
                LayerVertex(
                    x = xAt(index),
                    y = yAt((value ?: 0).toFloat()),
                    cover = value,
                    otherCover = other.getOrNull(index),
                    totalCover = totals.getOrNull(index),
                    lowerBandCover = lowerBands.getOrNull(index),
                )
            }
            val layerGlyphs =
                CloudLayerGlyphPlacer.place(
                    vertices = layerVertices(midCovers, highCovers, forecastTotals, lows),
                    glyph = CloudLayerGlyphPlacer.MID_GLYPH,
                    stepPx = glyphStepPx,
                    phaseFraction = CloudLayerGlyphPlacer.MID_PHASE,
                    nudgePx = nudgePx,
                ) +
                    CloudLayerGlyphPlacer.place(
                        vertices = layerVertices(highCovers, midCovers, forecastTotals, highLowerBands),
                        glyph = CloudLayerGlyphPlacer.HIGH_GLYPH,
                        stepPx = glyphStepPx,
                        phaseFraction = CloudLayerGlyphPlacer.HIGH_PHASE,
                        nudgePx = -nudgePx,
                    )
            // Quarter-step phases keep the observed trails off the forecast ones' x positions.
            val actualGlyphStyle = glyphStyle.copy(color = COLOR_CLOUD_ACTUAL)
            val actualLayerGlyphs =
                CloudLayerGlyphPlacer.place(
                    vertices = layerVertices(actualMidCovers, actualHighCovers, actualTotals, noLowerBand),
                    glyph = CloudLayerGlyphPlacer.MID_GLYPH,
                    stepPx = glyphStepPx,
                    phaseFraction = CloudLayerGlyphPlacer.MID_ACTUAL_PHASE,
                    nudgePx = nudgePx,
                ) +
                    CloudLayerGlyphPlacer.place(
                        vertices = layerVertices(actualHighCovers, actualMidCovers, actualTotals, noLowerBand),
                        glyph = CloudLayerGlyphPlacer.HIGH_GLYPH,
                        stepPx = glyphStepPx,
                        phaseFraction = CloudLayerGlyphPlacer.HIGH_ACTUAL_PHASE,
                        nudgePx = -nudgePx,
                    )
            fun drawGlyphs(glyphs: List<com.weatherwidget.shared.graph.LayerGlyph>, style: TextStyle) {
                glyphs.forEach { glyph ->
                    // The placer returns the glyph's visual centre; drawText takes its top-left.
                    val measured = textMeasurer.measure(glyph.glyph.toString(), style)
                    drawText(
                        measured,
                        topLeft = Offset(
                            glyph.x - measured.size.width / 2f,
                            glyph.y - measured.size.height / 2f,
                        ),
                    )
                }
            }
            drawGlyphs(layerGlyphs, glyphStyle)
            drawGlyphs(actualLayerGlyphs, actualGlyphStyle)
            // Sized from the same dp figure that drives `nudgePx`, not from a Compose measurement:
            // the Android renderer cannot reach a `TextMeasurer`, and the two font stacks need not
            // agree on the width of a 6.5dp bold `m`. See CloudLayerGlyphPlacer.GLYPH_BOX_*_RATIO.
            layerGlyphBounds += CloudLayerGlyphPlacer.glyphBounds(
                // Both trails, or the free-floating annotation reads the pink one as open air.
                glyphs = layerGlyphs + actualLayerGlyphs,
                glyphSizePx = CloudLayerGlyphPlacer.GLYPH_SIZE_DP.dp.toPx() * scale,
            )
        }

        val markerX = xAtTime(now)

        // Draw Now vertical dashed guide line - EARLY for lowest z-order (shared helper).
        if (now in windowStart..windowEnd) {
            drawNowLine(markerX, graphTop, graphHeight, scale)
        }

        // Draw Curve Line. ALWAYS dashed: the dashes mean "this is a forecast", not "there is an
        // actual to compare it against". Gating them on data availability previously left the
        // Android widget with no dash whenever its actual series was empty.
        val curveStroke = if (totalSpanHours <= 8) 2.dp.toPx() * scale else 3.dp.toPx() * scale
        drawPath(
            path = buildCurve(coords),
            color = COLOR_CLOUD_CURVE,
            style = Stroke(
                width = curveStroke,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(6f * scale, 5f * scale),
                ),
            )
        )

        // The actual, on top: solid, brighter, unsmoothed.
        actualCoordSegments.filter { it.size >= 2 }.forEach { actualCoords ->
            val actualPath = Path().apply {
                moveTo(actualCoords.first().x, actualCoords.first().y)
                actualCoords.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(
                path = actualPath,
                color = COLOR_CLOUD_ACTUAL,
                style = Stroke(width = curveStroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        // Value (%) labels (peak / dip / start / end) via the shared ValueLabelEngine — the same
        // engine used by the precip graph and the Android renderers. Compose draws top-left anchored,
        // so we treat the measured box bottom as the baseline (ascent = -height, descent = 0).
        val labelSignal = smoothedForecast.map { it.roundToInt().coerceIn(0, 100) }
        val labelStyle = TextStyle(fontSize = (11 * scale).sp, color = COLOR_CLOUD_LABEL_FORECAST)
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

        // Second pass for the actual curve. Without it the most informative number on the graph —
        // the depth of the burn-off dip — is the one value nobody can read. The already-placed
        // forecast boxes go in as obstacles so the two passes cannot overlap each other.
        //
        // Known gap: the engine takes one curve, so this pass avoids the actual curve and the
        // forecast's LABELS, but not the forecast LINE itself. Acceptable while the frozen curve
        // sits near the top of the plot and the actual's extrema sit well below it; revisit if the
        // engine grows a multi-curve collision input.
        if (actualCoordSegments.any { it.size >= 2 }) {
            val actualSignal = actualPoints.map { it.cover }
            val actualGraphPoints = actualPoints.map {
                ValueLabelEngine.GraphPoint(xAtTime(it.timeMs), yAt(it.cover.toFloat()))
            }
            val actualStyle = TextStyle(fontSize = (11 * scale).sp, color = COLOR_CLOUD_ACTUAL)
            ValueLabelEngine.computePlacements(
                labelSignal = actualSignal,
                points = actualGraphPoints,
                geometry = ValueLabelEngine.Geometry(graphTop, graphBottom, graphHeight, w, h),
                config = ValueLabelEngine.Config.cloud(),
                measureText = { textMeasurer.measure(it, actualStyle).size.width.toFloat() },
                textAscent = -labelHeight,
                textDescent = 0f,
                dpToPx = { it.dp.toPx() * scale },
                drawnIconBounds = drawnLabels.map { GraphRect(it.left, it.top, it.right, it.bottom) },
            ).filter { p ->
                // Same rule as the Android renderer: where the curves agree they overlap on screen,
                // so a second label just prints the same number twice.
                val actualTime = actualPoints[p.index].timeMs
                val left = points.indices.indexOfLast { points[it].dateTime <= actualTime }
                    .coerceAtLeast(0)
                val right = (left + 1).coerceAtMost(points.lastIndex)
                val span = (points[right].dateTime - points[left].dateTime).coerceAtLeast(1L)
                val fraction = ((actualTime - points[left].dateTime).toFloat() / span)
                    .coerceIn(0f, 1f)
                val forecastAtTime = smoothedForecast[left] +
                    (smoothedForecast[right] - smoothedForecast[left]) * fraction
                kotlin.math.abs(actualSignal[p.index] - forecastAtTime.roundToInt()) >=
                    CloudCoverGraphPalette.ACTUAL_LABEL_MIN_DIVERGENCE
            }.forEach { p ->
                val r = Rect(p.box.left, p.box.top, p.box.right, p.box.bottom)
                drawText(textMeasurer.measure(p.text, actualStyle), topLeft = r.topLeft)
                drawnLabels.add(r)
            }
        }

        // Actuals source API label for alternative actuals (e.g. Silurian, or Open-Meteo with alternative provider)
        // Only shown when the visible window actually contains observed cloud data; future-only
        // views (no actual points) suppress the annotation.
        val displaySource = WeatherSource.fromDisplaySource(displaySourceId)
        val actualsProviderId = ActualsProviderResolver.providerIdFor(displaySource)
        val dominantSpanHours = (windowEnd - windowStart) / 3_600_000L
        val hasActualsInWindow = actualPoints.isNotEmpty()
        val dominantLabel = if (actualsProviderId != displaySource.id && hasActualsInWindow) {
            val provider = WeatherSource.fromId(actualsProviderId)
            DominantStationLabel.plainLabelText(
                localizedText = "Actual cloud cover data from ${provider.displayName}",
            )
        } else {
            null
        }
        val dominantText = dominantLabel?.fullText
        var actualsSourcePlacement: DominantStationLabel.Placement? = null
        if (dominantLabel != null && dominantText != null && dominantSpanHours <= DominantStationLabel.MAX_HOURS_SPAN) {
            val dominantStyle = TextStyle(
                fontSize = (DOMINANT_STATION_LABEL_SP * scale).sp,
                color = COLOR_CLOUD_ACTUAL,
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.Black.copy(alpha = 0.7f),
                    offset = Offset(0f, 1f * scale),
                    blurRadius = 2f * scale,
                ),
            )
            val annotated = buildAnnotatedString {
                dominantLabel.segments.forEach { segment ->
                    when (segment.part) {
                        DominantStationLabel.Part.TEMPERATURE ->
                            withStyle(SpanStyle(fontSize = (DOMINANT_VALUE_LABEL_SP * scale).sp)) {
                                append(segment.text)
                            }
                        DominantStationLabel.Part.TIME ->
                            withStyle(SpanStyle(fontSize = (DOMINANT_TIME_LABEL_SP * scale).sp)) {
                                append(segment.text)
                            }
                        DominantStationLabel.Part.STATION,
                        DominantStationLabel.Part.AT,
                        DominantStationLabel.Part.AMPM -> append(segment.text)
                    }
                }
            }
            val measured = textMeasurer.measure(annotated, dominantStyle)
            val dominantPlot = GraphRect(0f, graphTop, w, footer.graphBottom(h, scale))
            val dominantPadPx = 2f * scale
            val dominantMetrics = GraphEmptySpaceFinder.Metrics(
                width = measured.size.width.toFloat(),
                ascent = -measured.size.height.toFloat(),
                descent = 0f,
            )
            val actualFlatCoords = actualCoordSegments.flatten()
            val placement = DominantStationLabel.place(
                text = dominantText,
                spanHours = dominantSpanHours,
                plot = dominantPlot,
                drawnBounds = drawnLabels.map { GraphRect(it.left, it.top, it.right, it.bottom) } +
                    layerGlyphBounds,
                curveYsAt = { x ->
                    buildList {
                        if (coords.isNotEmpty() && x in coords.first().x..coords.last().x) {
                            val idx = coords.indices.indexOfLast { coords[it].x <= x }
                                .coerceIn(0, coords.lastIndex - 1)
                            val span = (coords[idx + 1].x - coords[idx].x).coerceAtLeast(1f)
                            val fraction = ((x - coords[idx].x) / span).coerceIn(0f, 1f)
                            add(coords[idx].y + (coords[idx + 1].y - coords[idx].y) * fraction)
                        }
                        if (actualFlatCoords.isNotEmpty() && x <= actualFlatCoords.last().x) {
                            val idx = actualFlatCoords.indices.indexOfLast { actualFlatCoords[it].x <= x }
                            if (idx in 0 until actualFlatCoords.lastIndex) {
                                val span = (actualFlatCoords[idx + 1].x - actualFlatCoords[idx].x).coerceAtLeast(1f)
                                val fraction = ((x - actualFlatCoords[idx].x) / span).coerceIn(0f, 1f)
                                add(actualFlatCoords[idx].y + (actualFlatCoords[idx + 1].y - actualFlatCoords[idx].y) * fraction)
                            } else if (idx == actualFlatCoords.lastIndex) {
                                add(actualFlatCoords.last().y)
                            }
                        }
                    }
                },
                metrics = dominantMetrics,
                padPx = dominantPadPx,
                vetoBounds = if (markerX in 0f..w) listOf(GraphRect(markerX - 4f * scale, graphTop, markerX + 4f * scale, graphTop + graphHeight)) else emptyList(),
            )
            if (placement != null) {
                val topLeft = Offset(placement.box.left, placement.box.top)
                drawText(measured, topLeft = topLeft)
                drawnLabels.add(Rect(topLeft, Size(measured.size.width.toFloat(), measured.size.height.toFloat())))
            }
            actualsSourcePlacement = placement
        }
        // Emitted outside the gate above, so a test can tell "searched and found nowhere" from
        // "never got as far as searching".
        onPlacementDebug?.invoke(CloudGraphPlacementDebug(layerGlyphBounds.toList(), actualsSourcePlacement))

        // Draw Cloud Watermark in emptiest region — shared candidate search, local placement.
        val candidateCenters = CloudWatermarkPlacement.candidateCenters(smoothedClouds)

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
            xAt = xAt,
        )
    }
}

private fun computeTangents(coords: List<Offset>): List<Offset> = DesktopGraphUtils.computeTangents(coords)

private fun buildCurve(coords: List<Offset>): Path = DesktopGraphUtils.buildCurve(coords)
