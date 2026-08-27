package com.weatherwidget.widget

import com.weatherwidget.shared.graph.CloudCoverGraphPalette
import com.weatherwidget.shared.graph.CloudLayerGlyphPlacer
import com.weatherwidget.shared.graph.LayerVertex
import com.weatherwidget.shared.graph.CloudActualSeries
import com.weatherwidget.shared.graph.CloudWatermarkPlacement
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.shared.graph.GraphEmptySpaceFinder
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourlyGraphDefaults
import com.weatherwidget.shared.graph.ValueLabelEngine
import com.weatherwidget.shared.graph.HourlyTimelineGeometry
import com.weatherwidget.shared.graph.SeriesSmoothing
import com.weatherwidget.shared.graph.TimedCloudCover
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import com.weatherwidget.R
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

object CloudCoverGraphRenderer {

    private const val TAG = "CloudCoverGraph"
    // Retained for shouldAllowBottomOverflow (unit-tested); candidate/placement tuning now lives in
    // the shared ValueLabelEngine.Config.cloud().
    private const val LOW_CLOUD_BELOW_OVERFLOW_MAX_PERCENT = 55

    private const val GRAPH_TOP_PADDING_DP = 38f
    private const val GRAPH_BOTTOM_PADDING_DP = 3f
    private const val TOP_SCALE_HEADROOM_PERCENT = 12f
    private const val MIN_DYNAMIC_TOP_SCALE_PERCENT = 85f
    private const val MAX_DYNAMIC_TOP_SCALE_PERCENT = 100f
    private val WATERMARK_VERT_FRACTIONS = listOf(0.5f, 0.65f, 0.35f)
    private const val WATERMARK_ICON_CURVE_GAP_DP = 2f

    // Shared palette (CloudCoverGraphPalette) — the desktop composable draws the same ARGBs.
    private val COLOR_CLOUD_GRADIENT_START = CloudCoverGraphPalette.FILL_START
    private val COLOR_CLOUD_GRADIENT_END = CloudCoverGraphPalette.FILL_END
    private const val COLOR_MISSING_DIAG_TEXT = "#DDC8CFD8"
    private const val COLOR_MISSING_DIAG_SHADOW = "#CC000000"
    private const val COLOR_MISSING_DIAG_REASON_TEXT = "#AAB0B6BE"

    private const val MISSING_DIAG_TEXT_SIZE_DP = 9f
    private const val MISSING_DIAG_REASON_TEXT_SIZE_DP = 7.5f
    private const val MISSING_DIAG_MIN_LABEL_SCALE = 0.85f
    private const val MISSING_DIAG_LINE_SPACING = 1.15f
    private const val MISSING_DIAG_SHADOW_RADIUS_DP = 3f
    private const val MISSING_DIAG_SHADOW_DY_DP = 1f

    data class CloudHourData(
        val dateTime: LocalDateTime,
        /**
         * The FORECAST value: the day-ago prediction for past hours (when one is stored), the live
         * value otherwise. Named without a suffix for source compatibility with the single-curve era.
         */
        val cloudCover: Int, // 0-100
        /**
         * What actually happened, for past hours only: the live row after later runs retro-corrected
         * it. Null for the current and future hours — nothing has happened yet.
         */
        val actualCloudCover: Int? = null,
        /** False when no day-ago prediction existed and [cloudCover] fell back to the live value. */
        val isFrozenForecast: Boolean = false,
        /**
         * Mid- and high-layer cloud FORECAST cover, drawn as `m`/`h` glyph curves (see
         * [com.weatherwidget.shared.graph.CloudLayerGlyphPlacer]). Open-Meteo-only: no other source
         * reports the bands. Null where the source does not report them.
         *
         * Resolved the way [cloudCover] is — the frozen day-ago snapshot for elapsed hours, the
         * live row otherwise. Reading the live row directly for a past hour drew the
         * already-retro-corrected value, i.e. the actual, in the forecast's grey.
         */
        val midCover: Int? = null,
        val highCover: Int? = null,
        /**
         * What the bands actually did, for past hours only. Drawn as a second pair of glyph trails
         * in the actual's pink, and only where the hour has a genuine frozen band prediction that
         * the actual diverges from — see [CloudLayerGlyphPlacer.divergentActuals].
         *
         * Open-Meteo only: it is the sole source that forecasts the bands, so it is the sole source
         * where an observed band has anything to be compared against.
         */
        val actualMidCover: Int? = null,
        val actualHighCover: Int? = null,
        /** True when [midCover]/[highCover] are a stored day-ago prediction, not the live row. */
        val isFrozenBands: Boolean = false,
        val label: String,
        val iconRes: Int? = null,
        val isNight: Boolean = false,
        val isTwilight: Boolean = false,
        val isSunBoundary: Boolean = false,
        val isSunny: Boolean = false,
        val isRainy: Boolean = false,
        val isMixed: Boolean = false,
        val isCurrentHour: Boolean = false,
        val showLabel: Boolean = true,
        val isDateLabel: Boolean = false,
    )

    data class LabelPlacementDebug(
        val index: Int,
        val cloudCover: Int,
        val placedAbove: Boolean,
        val isGlobalMax: Boolean,
        val isGlobalMin: Boolean,
    )

    data class DayLabelPlacementDebug(
        val side: String,       // "LEFT" or "RIGHT"
        val dayText: String,
        val date: LocalDate,
        val x: Float,
        val y: Float,
        val placement: String,  // "TOP", "MIDDLE", "BOTTOM"
        val isToday: Boolean,
    )

    data class WatermarkPlacementDebug(
        val placed: Boolean,
        val candidateCenterIndex: Int? = null,
    )

    @androidx.annotation.VisibleForTesting
    internal data class VerticalScaleDebug(
        val visibleMax: Float,
        val topScale: Float,
    )

    private fun ensurePaints(context: Context, tallGraph: Boolean, labelScale: Float) =
        CloudCoverGraphStyle.ensurePaints(context, tallGraph, labelScale)

    fun renderGraph(
        context: Context,
        hours: List<CloudHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 1,
        /** Native-timestamp actual/history points; may be denser than [hours]. */
        actualSeries: List<TimedCloudCover> = emptyList(),
        hourLabelSpacingDp: Float = HourlyGraphDefaults.DEFAULT_HOUR_LABEL_SPACING_DP,
        // Total number of hours in the visible window and how many lack cloud cover data.
        // Used to render an in-graph "data missing" diagnostic when the upstream feed has
        // gaps, so the user sees the gap honestly instead of guessing whether the sky was
        // clear or the fetch failed. When totalHours is 0 these are ignored.
        missingHours: Int = 0,
        totalHours: Int = 0,
        // Number of grid columns available in the widget. Used to inject a middle
        // label on wide widgets when only edges are labeled.
        numColumns: Int = 0,
        // Compact human description of which hours are missing, e.g., "7a–8p" or
        // "9a, 11p". Optional: when null, the diagnostic falls back to the count.
        missingDescription: String? = null,
        // Short upstream reason (e.g., "NWS gridpoints fetch failed"). Renders as a
        // dim second line below the main diagnostic when present.
        missingReason: String? = null,
        job: Job? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onWatermarkPlaced: ((WatermarkPlacementDebug) -> Unit)? = null,
        showErrorWatermark: Boolean = false,
        errorSourceLabel: String? = null,
        errorCode: String? = null,
        errorFailureTimeMs: Long? = null,
        dominantStationLabel: DominantStationLabel.LabelText? = null,
        onDominantStationPlaced: ((DominantStationLabel.Placement?) -> Unit)? = null,
        /**
         * The mid/high layer glyph ink boxes, as handed to the free-label search. Exposed so a test
         * can assert the annotation clears them; without it a placement test cannot tell a wired
         * obstacle list from an empty one.
         */
        onLayerGlyphsPlaced: ((List<GraphRect>) -> Unit)? = null,
    ): Bitmap {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) {
            Log.w(TAG, "renderGraph: empty hours list (${widthPx}x${heightPx})")
            if (totalHours > 0) {
                drawMissingDataDiagnostic(
                    context, canvas, widthPx, heightPx,
                    missingHours = totalHours, totalHours = totalHours,
                    missingDescription = missingDescription, missingReason = missingReason,
                    labelScale = 1f,
                )
            }
            if (showErrorWatermark) {
                val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
                GraphFailureWatermarkRenderer.draw(
                    canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity,
                    errorSourceLabel, errorCode, errorFailureTimeMs,
                    failingText = context.getString(R.string.updates_failing),
                    errorCodeText = { code -> GraphFailureWatermarkRenderer.localizedErrorCodeText(context, code) },
                )
            }
            return bitmap
        }

        val density = context.resources.displayMetrics.density
        val heightDp = heightPx / density
        val tallGraph = heightDp >= HourlyGraphDefaults.TALL_GRAPH_HEIGHT_DP
        val labelScale = bitmapScale.coerceAtMost(1f)

        // --- Paints (gray color scheme, cached by density + height band) ---
        val paints = ensurePaints(context, tallGraph, labelScale)

        val topPadding = dpToPx(context, GRAPH_TOP_PADDING_DP * labelScale)
        val hasHourlyIcons = hours.any { it.iconRes != null }
        val showHourlyIcons = hasHourlyIcons && widthPx >= HourlyGraphDefaults.MIN_ICON_GRAPH_WIDTH_PX
        // Inline footer row sized to the hour-label text.
        val footerIconSize = HourlyFooterRenderer.iconSize(paints.hourLabelTextPaint)
        val labelHeight = dpToPx(context, HourlyGraphDefaults.BOTTOM_LABEL_HEIGHT_DP * labelScale)
        val bottomPadding = dpToPx(context, GRAPH_BOTTOM_PADDING_DP * labelScale)
        val bottomInset = dpToPx(context, HourlyGraphDefaults.FOOTER_BOTTOM_INSET_DP)

        val graphTop = topPadding
        val graphBottom =
            if (showHourlyIcons) {
                heightPx - footerIconSize - bottomInset
            } else {
                heightPx - labelHeight - bottomPadding
            }
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        val hourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)
        paints.gradientPaint.shader = LinearGradient(
            0f, graphTop, 0f, graphBottom,
            COLOR_CLOUD_GRADIENT_START,
            COLOR_CLOUD_GRADIENT_END,
            Shader.TileMode.CLAMP,
        )

        // --- Build smooth curve + fill ---
        val points = mutableListOf<Pair<Float, Float>>()
        val rawValues = hours.map { it.cloudCover.coerceIn(0, 100).toFloat() }
        val smoothedValues = SeriesSmoothing.smoothValuesPreservingAllExtrema(rawValues, iterations = smoothIterations)
        // Backward-compatible fallback for callers/tests that still attach actuals to hourly rows.
        // Production passes [actualSeries], whose timestamps may land on quarter hours.
        val zoneId = ZoneId.systemDefault()
        val timedActual = if (actualSeries.isNotEmpty()) {
            actualSeries.sortedBy { it.timeMs }
        } else {
            hours.mapNotNull { hour ->
                hour.actualCloudCover?.let {
                    TimedCloudCover(hour.dateTime.atZone(zoneId).toInstant().toEpochMilli(), it)
                }
            }
        }
        val actualSegments = CloudActualSeries.segments(timedActual)
        val hasActual = actualSegments.any { it.size >= 2 }
        // Scale over EVERY plotted series, or the tallest draws off the top. The mid/high layers
        // routinely reach 100% on days the low layer never leaves the axis (measured 2026-08-27),
        // so leaving them out here put their glyphs above graphTop, in the padding.
        val midCovers = hours.map { it.midCover }
        val highCovers = hours.map { it.highCover }
        // Only the observed values that will actually be DRAWN feed the scale. A suppressed glyph
        // occupies no pixels, so scaling for it would shrink the plot for nothing.
        val actualMidCovers = CloudLayerGlyphPlacer.divergentActuals(
            forecast = midCovers,
            actual = hours.map { it.actualMidCover },
            frozen = hours.map { it.isFrozenBands },
        )
        val actualHighCovers = CloudLayerGlyphPlacer.divergentActuals(
            forecast = highCovers,
            actual = hours.map { it.actualHighCover },
            frozen = hours.map { it.isFrozenBands },
        )
        val layerValues =
            (midCovers + highCovers + actualMidCovers + actualHighCovers)
                .filterNotNull().map { it.toFloat() }
        val verticalScale = computeVerticalScale(
            smoothedValues + timedActual.map { it.cover.toFloat() } + layerValues,
        )
        Log.d(
            TAG,
            "verticalScale: visibleMax=${verticalScale.visibleMax} topScale=${verticalScale.topScale} " +
                "graphTop=$graphTop graphBottom=$graphBottom graphHeight=$graphHeight",
        )

        hours.forEachIndexed { index, _ ->
            val x = hourWidth * index
            val v = smoothedValues[index]
            val y = mapCloudCoverToY(
                cloudCover = v,
                graphBottom = graphBottom,
                graphHeight = graphHeight,
                topScale = verticalScale.topScale,
            )
            points.add(x to y)
        }
        val peakIndex = smoothedValues.indices.maxByOrNull { smoothedValues[it] } ?: -1
        if (peakIndex >= 0) {
            Log.d(
                TAG,
                "peakPoint: idx=$peakIndex value=${smoothedValues[peakIndex]} " +
                    "x=${points[peakIndex].first} y=${points[peakIndex].second} topPaddingPx=$topPadding",
            )
        }

        val (curvePath, fillPath) = AndroidCurvePathBuilder.buildSmoothCurveAndFillPaths(points, graphBottom)

        // Draw Now Line early so it's behind all labels and curves (lowest z-order)
        val nowX = HourlyTimelineGeometry.computeNowX(
            items = hours,
            points = points,
            currentTime = currentTime,
            hourWidth = hourWidth,
            isCurrentHour = { it.isCurrentHour },
            dateTimeOf = { it.dateTime }
        )
        HourlyIndicatorRenderer.drawNowLine(
            canvas = canvas,
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            currentTimePaint = paints.currentTimePaint,
        )

        canvas.drawPath(fillPath, paints.gradientPaint)

        // --- Mid/high layer glyph curves ---
        // Each layer is a curve whose line is made of repeated tiny letters. Drawn under the low
        // forecast curve: the low layer is what "is it cloudy out" means, and the layers are
        // context for it. Skipped entirely on the common day where neither band has anything.
        //
        // The ink boxes escape this block because the free-floating dominant-station label below
        // has to treat the trails as obstacles; without them it reads a plot full of `h`s as open
        // air. Empty on the common day, which is also when that label has the whole plot.
        val layerGlyphBounds = mutableListOf<GraphRect>()
        if (CloudLayerGlyphPlacer.hasVisibleCover(midCovers) ||
            CloudLayerGlyphPlacer.hasVisibleCover(highCovers) ||
            CloudLayerGlyphPlacer.hasVisibleCover(actualMidCovers) ||
            CloudLayerGlyphPlacer.hasVisibleCover(actualHighCovers)
        ) {
            val glyphStepPx = dpToPx(context, CloudLayerGlyphPlacer.GLYPH_STEP_DP * labelScale)
            val nudgePx = paints.layerGlyphPaint.textSize * 0.55f
            fun layerVertices(cover: List<Int?>, other: List<Int?>) =
                cover.mapIndexed { index, value ->
                    LayerVertex(
                        x = hourWidth * index,
                        y = mapCloudCoverToY(
                            cloudCover = (value ?: 0).toFloat(),
                            graphBottom = graphBottom,
                            graphHeight = graphHeight,
                            topScale = verticalScale.topScale,
                        ),
                        cover = value,
                        otherCover = other.getOrNull(index),
                    )
                }
            val layerGlyphs =
                CloudLayerGlyphPlacer.place(
                    vertices = layerVertices(midCovers, highCovers),
                    glyph = CloudLayerGlyphPlacer.MID_GLYPH,
                    stepPx = glyphStepPx,
                    phaseFraction = CloudLayerGlyphPlacer.MID_PHASE,
                    nudgePx = nudgePx,
                ) +
                    CloudLayerGlyphPlacer.place(
                        vertices = layerVertices(highCovers, midCovers),
                        glyph = CloudLayerGlyphPlacer.HIGH_GLYPH,
                        stepPx = glyphStepPx,
                        phaseFraction = CloudLayerGlyphPlacer.HIGH_PHASE,
                        nudgePx = -nudgePx,
                    )
            // Quarter-step phases keep the observed trails off the forecast ones' x positions.
            val actualLayerGlyphs =
                CloudLayerGlyphPlacer.place(
                    vertices = layerVertices(actualMidCovers, actualHighCovers),
                    glyph = CloudLayerGlyphPlacer.MID_GLYPH,
                    stepPx = glyphStepPx,
                    phaseFraction = CloudLayerGlyphPlacer.MID_ACTUAL_PHASE,
                    nudgePx = nudgePx,
                ) +
                    CloudLayerGlyphPlacer.place(
                        vertices = layerVertices(actualHighCovers, actualMidCovers),
                        glyph = CloudLayerGlyphPlacer.HIGH_GLYPH,
                        stepPx = glyphStepPx,
                        phaseFraction = CloudLayerGlyphPlacer.HIGH_ACTUAL_PHASE,
                        nudgePx = -nudgePx,
                    )
            // drawText takes a baseline; the placer returns the glyph's visual centre.
            val baselineOffset =
                -(paints.layerGlyphPaint.ascent() + paints.layerGlyphPaint.descent()) / 2f
            layerGlyphs.forEach { glyph ->
                canvas.drawText(
                    glyph.glyph.toString(), glyph.x, glyph.y + baselineOffset, paints.layerGlyphPaint,
                )
            }
            actualLayerGlyphs.forEach { glyph ->
                canvas.drawText(
                    glyph.glyph.toString(), glyph.x, glyph.y + baselineOffset,
                    paints.layerGlyphActualPaint,
                )
            }
            // Sized from the paint's type size — the same number that drives `nudgePx` — not from
            // `measureText`/`ascent`, which disagree with Compose's measurements on desktop and
            // return stubs under Robolectric. See CloudLayerGlyphPlacer.GLYPH_BOX_*_RATIO.
            layerGlyphBounds += CloudLayerGlyphPlacer.glyphBounds(
                // Both trails, or the free-floating dominant-station label reads the pink one as
                // open air and lands on top of it.
                glyphs = layerGlyphs + actualLayerGlyphs,
                glyphSizePx = paints.layerGlyphPaint.textSize,
            )
            onLayerGlyphsPlaced?.invoke(layerGlyphBounds.toList())
            Log.d(
                TAG,
                "layerGlyphs: midMax=${midCovers.filterNotNull().maxOrNull()} " +
                    "highMax=${highCovers.filterNotNull().maxOrNull()} drawn=${layerGlyphs.size} " +
                    "actualDrawn=${actualLayerGlyphs.size} " +
                    "frozenBands=${hours.count { it.isFrozenBands }}/${hours.size} " +
                    "stepPx=$glyphStepPx topScale=${verticalScale.topScale}",
            )
        }

        // The forecast curve is ALWAYS dashed — dashes mean "this is a forecast", not "there is an
        // actual to compare it against". They used to be gated on `hasFrozen && hasActual`, which
        // made them a signal about data availability: when the Android actual series was empty the
        // gate never opened and no device ever drew a dash.
        val previousDashEffect = paints.curvePaint.pathEffect
        paints.curvePaint.pathEffect = DashPathEffect(
            floatArrayOf(dpToPx(context, 3f), dpToPx(context, 2.5f)), 0f,
        )
        canvas.drawPath(curvePath, paints.curvePaint)
        paints.curvePaint.pathEffect = previousDashEffect

        // The actual, on top: solid, brighter, independently timestamped, and gap-split.
        val actualPoints = mutableListOf<Pair<Float, Float>>()
        val actualValues = mutableListOf<Float>()
        if (hasActual) {
            actualSegments.filter { it.size >= 2 }.forEach { segment ->
                val segmentPoints = segment.mapNotNull { actual ->
                    val localTime = Instant.ofEpochMilli(actual.timeMs).atZone(zoneId).toLocalDateTime()
                    val x = HourlyTimelineGeometry.computeXForTime(
                        targetTime = localTime,
                        items = hours,
                        points = points,
                        hourWidth = hourWidth,
                        dateTimeOf = { it.dateTime },
                    ) ?: return@mapNotNull null
                    val value = actual.cover.coerceIn(0, 100).toFloat()
                    (x to mapCloudCoverToY(
                        cloudCover = value,
                        graphBottom = graphBottom,
                        graphHeight = graphHeight,
                        topScale = verticalScale.topScale,
                    )) to value
                }
                if (segmentPoints.size < 2) return@forEach
                val actualPath = Path().apply {
                    moveTo(segmentPoints.first().first.first, segmentPoints.first().first.second)
                    segmentPoints.drop(1).forEach { lineTo(it.first.first, it.first.second) }
                }
                canvas.drawPath(actualPath, paints.actualCurvePaint)
                actualPoints += segmentPoints.map { it.first }
                actualValues += segmentPoints.map { it.second }
            }
        }

        // --- Hour labels and icons ---
        val minHourLabelSpacing = dpToPx(context, hourLabelSpacingDp)
        val drawnIconBounds = mutableListOf<RectF>()

        val footerPlan = HourlyFooterRenderer.planHourLabels(
            items = hours,
            points = points,
            widthPx = widthPx,
            heightPx = heightPx,
            minHourLabelSpacing = minHourLabelSpacing,
            hourLabelTextPaint = paints.hourLabelTextPaint,
            dpToPx = { dpToPx(context, it) },
            showLabel = { it.showLabel },
            labelText = { it.label },
            iconSize = footerIconSize,
            iconTextGapDp = HourlyFooterRenderer.iconGapDp(numColumns),
            hasIcon = { showHourlyIcons && it.iconRes != null },
            isDateLabel = { it.isDateLabel },
            iconsAvailable = true,
        )
        HourlyFooterRenderer.drawPlan(
            canvas = canvas,
            plan = footerPlan,
            hourLabelTextPaint = paints.hourLabelTextPaint,
        ) { index, iconRect ->
            val hour = hours[index]
            val iconRes = hour.iconRes ?: return@drawPlan
            drawnIconBounds.add(iconRect)
            HourlyFooterRenderer.drawHourIcon(
                context, canvas, iconRes, iconRect,
                isRainy = hour.isRainy, isMixed = hour.isMixed,
                isNight = hour.isNight, isTwilight = hour.isTwilight, isSunny = hour.isSunny,
            )
        }

        // --- Percentage labels (peak / dip / start / end) via the shared ValueLabelEngine ---
        val labelSignal = smoothedValues.map { it.roundToInt().coerceIn(0, 100) }
        val drawnLabelBounds = mutableListOf<RectF>()
        val cloudLabelFm = paints.percentLabelPaint.fontMetrics
        val cloudLabelAscent = if (cloudLabelFm != null && cloudLabelFm.ascent != 0f) cloudLabelFm.ascent else -paints.percentLabelPaint.textSize
        val cloudLabelDescent = if (cloudLabelFm != null && cloudLabelFm.descent != 0f) cloudLabelFm.descent else paints.percentLabelPaint.textSize * 0.15f
        ValueLabelEngine.computePlacements(
            labelSignal = labelSignal,
            points = points.map { ValueLabelEngine.GraphPoint(it.first, it.second) },
            geometry = ValueLabelEngine.Geometry(graphTop, graphBottom, graphHeight, widthPx.toFloat(), heightPx.toFloat()),
            config = ValueLabelEngine.Config.cloud(),
            measureText = { paints.percentLabelPaint.measureText(it) },
            textAscent = cloudLabelAscent,
            textDescent = cloudLabelDescent,
            dpToPx = { dpToPx(context, it) },
            drawnIconBounds = drawnIconBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) },
            numColumns = numColumns,
        ).forEach { p ->
            canvas.drawText(p.text, p.centerX, p.baselineY, paints.percentLabelPaint)
            drawnLabelBounds.add(RectF(p.box.left, p.box.top, p.box.right, p.box.bottom))
            onLabelPlaced?.invoke(
                LabelPlacementDebug(
                    index = p.index,
                    cloudCover = labelSignal[p.index],
                    placedAbove = p.placedAbove,
                    isGlobalMax = p.isGlobalMax,
                    isGlobalMin = p.isGlobalMin,
                ),
            )
        }

        // --- Day labels ---
        val (today, leftDate, rightDate, leftText, rightText) =
            HourlyTimelineGeometry.dayLabelEndpoints(hours.first().dateTime, hours.last().dateTime, currentTime)

        val leftPaint = if (leftDate == today) paints.todayDayLabelPaint else paints.dayLabelTextPaint
        val rightPaint = if (rightDate == today) paints.todayDayLabelPaint else paints.dayLabelTextPaint
        val leftTextWidth = leftPaint.measureText(leftText)
        val rightTextWidth = rightPaint.measureText(rightText)

        HourlyIndicatorRenderer.drawDayLabels(
            canvas = canvas,
            leftDate = leftDate,
            rightDate = rightDate,
            leftText = leftText,
            rightText = rightText,
            leftX = leftTextWidth / 2f,
            rightX = widthPx - rightTextWidth / 2f,
            today = today,
            graphTop = graphTop,
            graphBottom = graphBottom,
            heightPx = heightPx,
            dayLabelTextPaint = paints.dayLabelTextPaint,
            todayDayLabelPaint = paints.todayDayLabelPaint,
            drawnLabelBounds = drawnLabelBounds,
            drawnIconBounds = drawnIconBounds,
            dpToPx = { dpToPx(context, it) },
            onDayLabelPlaced = if (onDayLabelPlaced != null) { side, text, date, x, y, placement, isToday ->
                onDayLabelPlaced.invoke(DayLabelPlacementDebug(side, text, date, x, y, placement, isToday))
            } else null,
        )

        // --- NOW indicator ---
        HourlyIndicatorRenderer.drawNowIndicator(
            canvas = canvas,
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            currentTimePaint = paints.currentTimePaint,
            nowLabelTextPaint = paints.nowLabelTextPaint,
            nowLabelText = context.getString(R.string.forecast_hourly_legend),
            dpToPx = { dpToPx(context, it) },
            drawLine = false,
        )

        // --- Cloud icon in emptiest region ---
        val cloudDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_weather_mostly_cloudy)
        if (cloudDrawable != null && points.size >= 3) {
            val iconSizePx = dpToPx(context, HourlyGraphDefaults.WATERMARK_ICON_SIZE_DP).toInt()
            val iconGap = dpToPx(context, WATERMARK_ICON_CURVE_GAP_DP)
            // Shared emptiest-region search (CloudWatermarkPlacement); only the bounds/overlap
            // placement and drawing stay platform-specific below.
            val candidateCenters = CloudWatermarkPlacement.candidateCenters(smoothedValues)

            var placed = false
            var placedCandidateIndex: Int? = null

            for (candidateCenter in candidateCenters) {
                val curveX = points[candidateCenter].first
                val curveY = points[candidateCenter].second
                val verticalFractions = WATERMARK_VERT_FRACTIONS

                for (fraction in verticalFractions) {
                    val centerY = graphTop + (curveY - graphTop) * fraction
                    val bounds = RectF(
                        curveX - iconSizePx / 2f,
                        centerY - iconSizePx / 2f,
                        curveX + iconSizePx / 2f,
                        centerY + iconSizePx / 2f,
                    )

                    val fitsAboveCurve = bounds.top >= 0f && bounds.bottom < curveY - iconGap
                    val overlapsLabels = drawnLabelBounds.any { RectF.intersects(it, bounds) }
                    val overlapsIcons = drawnIconBounds.any { RectF.intersects(it, bounds) }
                    if (!fitsAboveCurve || overlapsLabels || overlapsIcons) continue

                    cloudDrawable.alpha = HourlyGraphDefaults.WATERMARK_ALPHA
                    cloudDrawable.setBounds(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt(),
                    )
                    cloudDrawable.draw(canvas)
                    placed = true
                    placedCandidateIndex = candidateCenter
                    break
                }

                if (placed) break
            }

            onWatermarkPlaced?.invoke(
                WatermarkPlacementDebug(
                    placed = placed,
                    candidateCenterIndex = placedCandidateIndex,
                ),
            )
        }


        // Second pass: label the actual curve. Without it the most informative number on the graph —
        // how far reality diverged from the forecast — is the one value nobody can read. The
        // forecast's boxes go in as obstacles so the passes cannot collide with each other.
        //
        // Known gap (matches the desktop renderer): the engine takes one curve, so this avoids the
        // actual curve and the forecast's LABELS, but not the forecast LINE.
        if (actualPoints.size >= 2) {
            val actualSignal = actualValues.map { it.roundToInt().coerceIn(0, 100) }
            val actualGraphPoints = actualPoints.map {
                ValueLabelEngine.GraphPoint(it.first, it.second)
            }
            ValueLabelEngine.computePlacements(
                labelSignal = actualSignal,
                points = actualGraphPoints,
                geometry = ValueLabelEngine.Geometry(graphTop, graphBottom, graphHeight, widthPx.toFloat(), heightPx.toFloat()),
                config = ValueLabelEngine.Config.cloud(),
                measureText = { paints.actualPercentLabelPaint.measureText(it) },
                textAscent = cloudLabelAscent,
                textDescent = cloudLabelDescent,
                dpToPx = { dpToPx(context, it) },
                drawnIconBounds = (drawnIconBounds + drawnLabelBounds).map {
                    GraphRect(it.left, it.top, it.right, it.bottom)
                },
                numColumns = numColumns,
            ).filter { p ->
                // Where the two curves agree they are drawn on top of each other, so a second label
                // is the same number twice — that is what put four "100%"s across the top of a flat
                // morning. Label the actual only where it actually says something different.
                val fractionalIndex = (actualPoints[p.index].first / hourWidth)
                    .coerceIn(0f, smoothedValues.lastIndex.toFloat())
                val left = fractionalIndex.toInt().coerceIn(smoothedValues.indices)
                val right = (left + 1).coerceAtMost(smoothedValues.lastIndex)
                val fraction = fractionalIndex - left
                val forecastValue = (smoothedValues[left] +
                    (smoothedValues[right] - smoothedValues[left]) * fraction).roundToInt()
                abs(actualSignal[p.index] - forecastValue) >=
                    CloudCoverGraphPalette.ACTUAL_LABEL_MIN_DIVERGENCE
            }.forEach { p ->
                canvas.drawText(p.text, p.centerX, p.baselineY, paints.actualPercentLabelPaint)
                drawnLabelBounds.add(RectF(p.box.left, p.box.top, p.box.right, p.box.bottom))
            }
        }

        if (dominantStationLabel != null && hours.size >= 2) {
            val spanHours = java.time.Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()
            if (spanHours <= DominantStationLabel.MAX_HOURS_SPAN) {
                val valuePaint = paints.dominantValueTextPaint
                val stationPaint = paints.dominantStationTextPaint
                val timePaint = paints.dominantTimeTextPaint
                val segmentWidths = dominantStationLabel.segments.map { segment ->
                    val paint = when (segment.part) {
                        DominantStationLabel.Part.TEMPERATURE -> valuePaint
                        DominantStationLabel.Part.TIME -> timePaint
                        DominantStationLabel.Part.STATION,
                        DominantStationLabel.Part.AT,
                        DominantStationLabel.Part.AMPM -> stationPaint
                    }
                    paint.measureText(segment.text)
                }
                val totalWidth = segmentWidths.sum()
                val fontAscent = TemperatureGraphStyle.fontAscent(valuePaint)
                val fontDescent = TemperatureGraphStyle.fontDescent(valuePaint)
                val padPx = dpToPx(context, 2f * labelScale)
                val placement = DominantStationLabel.place(
                    text = dominantStationLabel.fullText,
                    spanHours = spanHours,
                    plot = GraphRect(0f, topPadding, widthPx.toFloat(), graphBottom),
                    drawnBounds = drawnLabelBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) } +
                        layerGlyphBounds,
                    curveYsAt = { x ->
                        buildList {
                            if (smoothedValues.size >= 2 && hourWidth > 0f) {
                                val fraction = (x / hourWidth).coerceIn(0f, smoothedValues.lastIndex.toFloat())
                                val idx = fraction.toInt().coerceIn(0, smoothedValues.size - 2)
                                val f = fraction - idx
                                val forecastY = mapCloudCoverToY(
                                    cloudCover = smoothedValues[idx] + (smoothedValues[idx + 1] - smoothedValues[idx]) * f,
                                    graphBottom = graphBottom,
                                    graphHeight = graphHeight,
                                    topScale = verticalScale.topScale,
                                )
                                add(forecastY)
                            }
                            if (actualPoints.isNotEmpty() && x <= actualPoints.last().first + hourWidth * 0.5f) {
                                val actualIdx = actualPoints.indexOfLast { it.first <= x }
                                if (actualIdx >= 0 && actualIdx < actualPoints.lastIndex) {
                                    val p1 = actualPoints[actualIdx]
                                    val p2 = actualPoints[actualIdx + 1]
                                    val span = p2.first - p1.first
                                    if (span > 0f) {
                                        val actualY = p1.second + (p2.second - p1.second) * ((x - p1.first) / span).coerceIn(0f, 1f)
                                        add(actualY)
                                    }
                                } else if (actualIdx == actualPoints.lastIndex) {
                                    add(actualPoints.last().second)
                                }
                            }
                        }
                    },
                    metrics = GraphEmptySpaceFinder.Metrics(
                        width = totalWidth,
                        ascent = fontAscent,
                        descent = fontDescent,
                    ),
                    padPx = padPx,
                    vetoBounds = if (nowX != null) listOf(GraphRect(nowX - 4f, graphTop, nowX + 4f, graphBottom)) else emptyList(),
                )
                if (placement != null) {
                    var x = placement.box.left
                    dominantStationLabel.segments.forEachIndexed { index, segment ->
                        val paint = when (segment.part) {
                            DominantStationLabel.Part.TEMPERATURE -> valuePaint
                            DominantStationLabel.Part.TIME -> timePaint
                            DominantStationLabel.Part.STATION,
                            DominantStationLabel.Part.AT,
                            DominantStationLabel.Part.AMPM -> stationPaint
                        }
                        canvas.drawText(segment.text, x, placement.baselineY, paint)
                        x += segmentWidths[index]
                    }
                    drawnLabelBounds.add(RectF(placement.box.left, placement.box.top, placement.box.right, placement.box.bottom))
                }
                onDominantStationPlaced?.invoke(placement)
            }
        }

        if (missingHours > 0 && totalHours > 0) {
            drawMissingDataDiagnostic(
                context, canvas, widthPx, heightPx,
                missingHours = missingHours, totalHours = totalHours,
                missingDescription = missingDescription, missingReason = missingReason,
                labelScale = labelScale,
            )
        }

        if (showErrorWatermark) {
            val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
            GraphFailureWatermarkRenderer.draw(
                canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity,
                errorSourceLabel, errorCode, errorFailureTimeMs,
                failingText = context.getString(R.string.updates_failing),
                errorCodeText = { code -> GraphFailureWatermarkRenderer.localizedErrorCodeText(context, code) },
            )
        }

        return bitmap
    }

    /**
     * Draws a permanent "Cloud data missing …" indicator centered in the graph. Rendered
     * on every paint where the visible window has gaps so the user can tell the difference
     * between "actually clear" and "feed missing data." When [missingReason] is supplied
     * (typically pulled from recent NwsForecastMapper failure logs), it renders below the
     * main line in a dimmer style.
     */
    private fun drawMissingDataDiagnostic(
        context: Context,
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        missingHours: Int,
        totalHours: Int,
        missingDescription: String?,
        missingReason: String?,
        labelScale: Float,
    ) {
        val mainText = buildMissingDiagnosticText(context, missingHours, totalHours, missingDescription)
        val effectiveScale = labelScale.coerceAtLeast(MISSING_DIAG_MIN_LABEL_SCALE)
        val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(COLOR_MISSING_DIAG_TEXT)
            textSize = dpToPx(context, MISSING_DIAG_TEXT_SIZE_DP * effectiveScale)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(
                dpToPx(context, MISSING_DIAG_SHADOW_RADIUS_DP),
                0f,
                dpToPx(context, MISSING_DIAG_SHADOW_DY_DP),
                Color.parseColor(COLOR_MISSING_DIAG_SHADOW),
            )
        }
        val mainY = heightPx / 2f + mainPaint.textSize / 2f
        canvas.drawText(mainText, widthPx / 2f, mainY, mainPaint)

        if (!missingReason.isNullOrBlank()) {
            val reasonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_MISSING_DIAG_REASON_TEXT)
                textSize = dpToPx(context, MISSING_DIAG_REASON_TEXT_SIZE_DP * effectiveScale)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(
                    dpToPx(context, MISSING_DIAG_SHADOW_RADIUS_DP),
                    0f,
                    dpToPx(context, MISSING_DIAG_SHADOW_DY_DP),
                    Color.parseColor(COLOR_MISSING_DIAG_SHADOW),
                )
            }
            val reasonY = mainY + mainPaint.textSize * MISSING_DIAG_LINE_SPACING
            canvas.drawText("($missingReason)", widthPx / 2f, reasonY, reasonPaint)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun buildMissingDiagnosticText(
        context: Context,
        missingHours: Int,
        totalHours: Int,
        missingDescription: String?,
    ): String {
        if (missingHours >= totalHours) {
            return context.getString(R.string.cloud_data_unavailable)
        }
        if (missingDescription.isNullOrBlank()) {
            return if (missingHours == 1) {
                context.getString(R.string.cloud_data_missing_for_one, missingHours, totalHours)
            } else {
                context.getString(R.string.cloud_data_missing_for_many, missingHours, totalHours)
            }
        }
        return if (missingHours == 1) {
            context.getString(R.string.cloud_data_missing_at, missingDescription)
        } else {
            context.getString(R.string.cloud_data_missing_range, missingDescription, missingHours, totalHours)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun computeVerticalScale(values: List<Float>): VerticalScaleDebug {
        val visibleMax = values.maxOrNull()?.coerceIn(0f, 100f) ?: 0f
        val topScale =
            (visibleMax + TOP_SCALE_HEADROOM_PERCENT)
                .coerceIn(MIN_DYNAMIC_TOP_SCALE_PERCENT, MAX_DYNAMIC_TOP_SCALE_PERCENT)
        return VerticalScaleDebug(
            visibleMax = visibleMax,
            topScale = topScale,
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun mapCloudCoverToY(
        cloudCover: Float,
        graphBottom: Float,
        graphHeight: Float,
        topScale: Float,
    ): Float {
        val clampedValue = cloudCover.coerceIn(0f, MAX_DYNAMIC_TOP_SCALE_PERCENT)
        val safeTopScale = topScale.coerceIn(MIN_DYNAMIC_TOP_SCALE_PERCENT, MAX_DYNAMIC_TOP_SCALE_PERCENT)
        return graphBottom - graphHeight * (clampedValue / safeTopScale)
    }

    private fun dpToPx(context: Context, dp: Float): Float =
        CloudCoverGraphStyle.dpToPx(context, dp)

    @androidx.annotation.VisibleForTesting
    internal fun shouldAllowBottomOverflow(
        cloudPct: Int,
        placeAbove: Boolean,
        isFallbackAttempt: Boolean,
    ): Boolean =
        !placeAbove &&
            !isFallbackAttempt &&
            cloudPct <= LOW_CLOUD_BELOW_OVERFLOW_MAX_PERCENT

    @androidx.annotation.VisibleForTesting
    internal fun shouldAllowIconOverlap(
        cloudPct: Int,
        placeAbove: Boolean,
        isFallbackAttempt: Boolean,
    ): Boolean = false
}
