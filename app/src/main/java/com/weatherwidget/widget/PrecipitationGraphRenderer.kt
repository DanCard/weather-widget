package com.weatherwidget.widget

import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourlyGraphDefaults
import com.weatherwidget.shared.graph.ValueLabelEngine
import com.weatherwidget.shared.graph.HourlyTimelineGeometry
import com.weatherwidget.shared.graph.SeriesSmoothing
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import com.weatherwidget.R
import com.weatherwidget.shared.graph.DayNightHours
import com.weatherwidget.shared.graph.RainPeriodSelection
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalDate
import com.weatherwidget.widget.handlers.formatPrecipAmount
import kotlin.math.abs
import kotlin.math.roundToInt

// Rain-period selection lives in :shared so Android and desktop cannot drift. These aliases keep the
// Android call sites reading as before; the types and the logic are the shared ones.
// See RainPeriodSelection and plans/260901-share-rain-period-selection.md.
internal typealias RainPeriod = RainPeriodSelection.RainPeriod
internal typealias DayNightSegment = RainPeriodSelection.DayNightSegment
/** How rain-amount labels are aggregated across the visible window. Shared with desktop. */
internal typealias RainLabelMode = RainPeriodSelection.Mode

object PrecipitationGraphRenderer {

    private const val TAG = "PrecipGraphRenderer"

    private const val COLOR_CURVE_FILL_TOP = "#445AC8FA"
    private const val COLOR_CURVE_FILL_BOTTOM = "#005AC8FA"

    private const val GRAPH_TOP_PADDING_DP = 44f
    private const val FAR_OUT_DATA_HOURS_THRESHOLD = 72L
    private const val Y_SCALE_HEADROOM_FACTOR = 1.15f
    private const val Y_SCALE_MIN = 10f
    private const val RAIN_AMOUNT_PADDING_DP = 4f
    // NARROW zoom shows per-hour labels for the first few columns; the rightmost column sits at the
    // clipped window edge, so cap at the first 4.
    private const val PER_HOUR_MAX_COLUMNS = 4

    data class PrecipHourData(
        val dateTime: LocalDateTime,
        val precipProbability: Int, // 0-100
        val label: String, // "12a", "1p", "2p"
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
        val precipAmountMm: Float? = null,
        val actualPrecipAmountMm: Float? = null,
    )

    data class LabelPlacementDebug(
        val index: Int,
        val hourLabel: String,
        val probability: Int,
        val placedAbove: Boolean,
        val isGlobalMax: Boolean,
        val isGlobalMin: Boolean,
        val reason: String = "",
        val isPeak: Boolean = false,
        val isValley: Boolean = false,
        val isSoftDip: Boolean = false,
        val firstLabelBelowRuleApplied: Boolean = false,
        val elevatedPeakRuleApplied: Boolean = false,
        val dipBelowRuleApplied: Boolean = false,
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
        val x: Float,
        val y: Float,
        val xFrac: Float,
        val yFrac: Float,
    )

    data class NowLabelPlacementDebug(
        val x: Float,
        val baselineY: Float,
        val bounds: PrecipRect,
    )

    data class PrecipRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun intersects(other: PrecipRect): Boolean {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top
        }

        fun toRectF(): RectF = RectF(left, top, right, bottom)

        companion object {
            fun fromRectF(rect: RectF): PrecipRect = PrecipRect(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    data class ProbabilityLabelPlacement(
        val index: Int,
        val text: String,
        val x: Float,
        val baselineY: Float,
        val bounds: PrecipRect,
        val debug: LabelPlacementDebug
    )

    data class RainAmountPlacement(
        val text: String,
        val x: Float,
        val y: Float,
        val bounds: PrecipRect,
        val overlapArea: Float
    )


    data class TextMeasurer(
        val measureProbabilityText: (String) -> Float,
        val getProbabilityTextBounds: (String) -> Pair<Float, Float>,
        val measureRainAmountText: (String) -> Float,
        val getRainAmountTextBounds: (String) -> Pair<Float, Float>,
        val measureActualRainAmountText: (String) -> Float,
        val getActualRainAmountTextBounds: (String) -> Pair<Float, Float>,
        val dpToPx: (Float) -> Float,
        val measureNowText: (String) -> Float,
        val getNowTextBounds: (String) -> Pair<Float, Float>,
        val measureDayText: (String, Boolean) -> Float,
        val getDayTextBounds: (Boolean) -> Pair<Float, Float>,
    )

    data class PrecipGraphLayout(
        val points: List<Pair<Float, Float>>,
        val probabilityPlacements: List<ProbabilityLabelPlacement>,
        val rainAmountPlacements: List<RainAmountPlacement>,
        val actualRainAmountPlacements: List<RainAmountPlacement>,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val hourWidth: Float,
        val nowX: Float?,
        val labelSignal: List<Int>,
        val nowLabelPlacement: NowLabelPlacementDebug? = null,
        val dayLabelPlacements: List<DayLabelPlacementDebug> = emptyList(),
        val watermarkPlacement: WatermarkPlacementDebug? = null,
        val iconBounds: List<PrecipRect> = emptyList(),
        val dayNightBoundaryXs: List<Float> = emptyList(),
    )

    fun calculateLayout(
        hours: List<PrecipHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 2,
        rainAmountWindowHours: Int = 0,
        rainLabelMode: RainLabelMode = RainLabelMode.WINDOW_TOTAL,
        showHourlyIcons: Boolean,
        footerIconSize: Float = 0f,
        footerIconBounds: List<PrecipRect>? = null,
        textMeasurer: TextMeasurer,
        onDebugLog: ((String) -> Unit)? = null,
        nowLabelText: String = "NOW",
    ): PrecipGraphLayout {
        val labelScale = bitmapScale.coerceAtMost(1f)
        val topPadding = textMeasurer.dpToPx(GRAPH_TOP_PADDING_DP * labelScale)
        val labelHeight = textMeasurer.dpToPx(HourlyGraphDefaults.BOTTOM_LABEL_HEIGHT_DP * labelScale)
        val footerBottomInset = textMeasurer.dpToPx(HourlyGraphDefaults.FOOTER_BOTTOM_INSET_DP)

        val graphTop = topPadding
        val graphBottom =
            if (showHourlyIcons) {
                heightPx - footerIconSize - footerBottomInset
            } else {
                heightPx - labelHeight
            }
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)
        val hourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)

        val points = mutableListOf<Pair<Float, Float>>()
        val rawProbs = hours.map { it.precipProbability.coerceIn(0, 100).toFloat() }
        val isFarOutData = hours.isNotEmpty() && abs(
            Duration.between(hours[hours.size / 2].dateTime, currentTime).toHours()
        ) > FAR_OUT_DATA_HOURS_THRESHOLD

        val probs = if (isFarOutData) {
            SeriesSmoothing.smoothValuesPreservingExtrema(
                rawProbs,
                iterations = smoothIterations,
                preserveGlobalMax = true,
                preserveGlobalMin = false,
                preserveStart = true,
                preserveEnd = true,
            )
        } else {
            SeriesSmoothing.smoothValuesPreservingAllExtrema(rawProbs, iterations = smoothIterations)
        }

        val rawMax = probs.maxOrNull() ?: 0f
        val yScaleMax = (rawMax * Y_SCALE_HEADROOM_FACTOR).coerceAtLeast(Y_SCALE_MIN).coerceAtMost(100f)

        hours.forEachIndexed { index, _ ->
            val x = hourWidth * index
            val prob = probs[index]
            val y = graphBottom - graphHeight * (prob / yScaleMax)
            points.add(x to y)
        }

        val nowX = HourlyTimelineGeometry.computeNowX(
            items = hours,
            points = points,
            currentTime = currentTime,
            hourWidth = hourWidth,
            isCurrentHour = { it.isCurrentHour },
            dateTimeOf = { it.dateTime },
        )

        val labelSignal = probs.map { it.roundToInt().coerceIn(0, 100) }
        // Candidate selection + placement is owned by the shared ValueLabelEngine; only this
        // renderer-specific "first labeled positive" index is computed here and passed in.
        val firstLabeledPositive = hours.indexOfFirst { it.precipProbability > 0 && it.showLabel }

        // Pre-calculate icon bounds for collision detection. Icons now sit in the inline footer
        // band at the very bottom (see HourlyFooterRenderer), so reserve that band.
        val drawnIconBounds = footerIconBounds?.toMutableList() ?: mutableListOf()
        if (footerIconBounds == null && showHourlyIcons) {
            val iconTop = heightPx - footerIconSize - footerBottomInset
            val iconBottom = heightPx - footerBottomInset
            hours.forEachIndexed { index, hour ->
                if (hour.iconRes != null) {
                    val x = hourWidth * index
                    val clampedX = x.coerceIn(footerIconSize / 2f, widthPx - footerIconSize / 2f)
                    val iconX = clampedX - footerIconSize / 2f
                    drawnIconBounds.add(PrecipRect(iconX, iconTop, iconX + footerIconSize, iconBottom))
                }
            }
        }

        val (probAscent, probDescent) = textMeasurer.getProbabilityTextBounds("0%")
        val probabilityPlacements = ValueLabelEngine.computePlacements(
            labelSignal = labelSignal,
            points = points.map { ValueLabelEngine.GraphPoint(it.first, it.second) },
            geometry = ValueLabelEngine.Geometry(graphTop, graphBottom, graphHeight, widthPx.toFloat(), heightPx.toFloat()),
            config = ValueLabelEngine.Config.precip(),
            measureText = textMeasurer.measureProbabilityText,
            textAscent = probAscent,
            textDescent = probDescent,
            dpToPx = textMeasurer.dpToPx,
            drawnIconBounds = drawnIconBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) },
            firstLabeledPositive = firstLabeledPositive,
        ).map { p ->
            ProbabilityLabelPlacement(
                index = p.index,
                text = p.text,
                x = p.centerX,
                baselineY = p.baselineY,
                bounds = PrecipRect(p.box.left, p.box.top, p.box.right, p.box.bottom),
                debug = LabelPlacementDebug(
                    index = p.index,
                    hourLabel = hours[p.index].label,
                    probability = labelSignal[p.index],
                    placedAbove = p.placedAbove,
                    isGlobalMax = p.isGlobalMax,
                    isGlobalMin = p.isGlobalMin,
                    reason = p.reason,
                    isPeak = p.isPeak,
                    isValley = p.isValley,
                    isSoftDip = p.isSoftDip,
                    firstLabelBelowRuleApplied = p.isFirstRising && !p.placedAbove,
                ),
            )
        }

        // One shared call yields both series, so the forecast and actual totals cannot be sourced
        // from the same field by accident — the desktop defect fixed on 2026-09-01.
        val rainHours = hours.toRainHours()
        val selected = RainPeriodSelection.selectPeriods(
            hours = rainHours,
            mode = rainLabelMode,
            hourWidth = hourWidth,
            windowHours = rainAmountWindowHours,
        )
        val rainPeriods = selected.forecast
        val actualRainPeriods = selected.actual
        val dayNightBoundaryXs = if (rainLabelMode == RainLabelMode.DAY_NIGHT) {
            RainPeriodSelection.computeDayNightBoundaryXs(rainHours, hourWidth)
        } else {
            emptyList()
        }
        val rainCollisionBounds = probabilityPlacements.map { it.bounds }.toMutableList()

        val nowLabelPlacement = if (nowX != null) {
            val nowText = nowLabelText
            val nowTextWidth = textMeasurer.measureNowText(nowText)
            val (nowTextAscent, nowTextDescent) = textMeasurer.getNowTextBounds(nowText)
            HourlyIndicatorRenderer.computeNowLabelBounds(
                nowX = nowX,
                graphTop = graphTop,
                graphHeight = graphHeight,
                textWidth = nowTextWidth,
                fontAscent = nowTextAscent,
                fontDescent = nowTextDescent,
                drawnBounds = rainCollisionBounds.map { it.toRectF() },
                dpToPx = textMeasurer.dpToPx,
            )?.let {
                val bounds = PrecipRect.fromRectF(it.bounds)
                rainCollisionBounds.add(bounds)
                NowLabelPlacementDebug(x = nowX, baselineY = it.labelY, bounds = bounds)
            }
        } else {
            null
        }

        val rainPlacements = calculateRainAmountPlacements(
            rainPeriods = rainPeriods,
            geometry = GraphGeometry(widthPx, heightPx, graphTop, graphBottom, graphHeight),
            initialCollisionBounds = rainCollisionBounds,
            labelPrefix = "Pred ",
            measureText = textMeasurer.measureRainAmountText,
            getTextBounds = textMeasurer.getRainAmountTextBounds,
            dpToPx = textMeasurer.dpToPx
        )
        val actualRainPlacements = calculateRainAmountPlacements(
            rainPeriods = actualRainPeriods,
            geometry = GraphGeometry(widthPx, heightPx, graphTop, graphBottom, graphHeight),
            initialCollisionBounds = rainCollisionBounds + rainPlacements.map { it.bounds },
            labelPrefix = "Act ",
            measureText = textMeasurer.measureActualRainAmountText,
            getTextBounds = textMeasurer.getActualRainAmountTextBounds,
            dpToPx = textMeasurer.dpToPx
        )

        val overlayBounds = (
            probabilityPlacements.map { it.bounds } +
                rainPlacements.map { it.bounds } +
                actualRainPlacements.map { it.bounds }
            ).toMutableList()
        nowLabelPlacement?.let { overlayBounds.add(it.bounds) }

        val (today, leftDate, rightDate, leftText, rightText) =
            HourlyTimelineGeometry.dayLabelEndpoints(hours.first().dateTime, hours.last().dateTime, currentTime)
        val dayLabelFontMetrics = textMeasurer.getDayTextBounds(false)
        val todayLabelFontMetrics = textMeasurer.getDayTextBounds(true)
        val computedDayPlacements = HourlyIndicatorRenderer.computeDayLabelPlacements(
            leftDate = leftDate,
            rightDate = rightDate,
            leftText = leftText,
            rightText = rightText,
            leftX = textMeasurer.measureDayText(leftText, leftDate == today) / 2f,
            rightX = widthPx - textMeasurer.measureDayText(rightText, rightDate == today) / 2f,
            today = today,
            graphTop = graphTop,
            graphBottom = graphBottom,
            heightPx = heightPx,
            dayLabelFontMetrics = dayLabelFontMetrics,
            todayLabelFontMetrics = todayLabelFontMetrics,
            measureDayText = textMeasurer.measureDayText,
            drawnLabelBounds = overlayBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) },
            drawnIconBounds = drawnIconBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) },
            dpToPx = textMeasurer.dpToPx,
        )
        val dayPlacements = computedDayPlacements.map {
            DayLabelPlacementDebug(
                side = it.side,
                dayText = it.text,
                date = it.date,
                x = it.x,
                y = it.y,
                placement = it.placement,
                isToday = it.isToday,
            )
        }
        overlayBounds.addAll(
            computedDayPlacements.map {
                PrecipRect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom)
            },
        )

        var watermarkPlacement: WatermarkPlacementDebug? = null
        if (hours.size >= HourlyGraphDefaults.WATERMARK_MIN_HOURS) {
            val iconSizePx = textMeasurer.dpToPx(HourlyGraphDefaults.WATERMARK_ICON_SIZE_DP).toInt()
            val halfIcon = iconSizePx / 2f
            val xFractions = HourlyGraphDefaults.OVERLAY_X_FRACTIONS
            val yFractions = HourlyGraphDefaults.OVERLAY_Y_FRACTIONS

            var placed = false
            for (yFrac in yFractions) {
                for (xFrac in xFractions) {
                    val cx = widthPx * xFrac
                    val cy = graphTop + graphHeight * yFrac
                    val bounds = PrecipRect(cx - halfIcon, cy - halfIcon, cx + halfIcon, cy + halfIcon)
                    if (bounds.left < 0f || bounds.right > widthPx) continue
                    if (bounds.top < graphTop || bounds.bottom > graphBottom) continue
                    if (overlayBounds.any { it.intersects(bounds) }) continue

                    watermarkPlacement = WatermarkPlacementDebug(x = bounds.left, y = bounds.top, xFrac = xFrac, yFrac = yFrac)
                    placed = true
                    break
                }
                if (placed) break
            }
        }

        return PrecipGraphLayout(
            points = points,
            probabilityPlacements = probabilityPlacements,
            rainAmountPlacements = rainPlacements,
            actualRainAmountPlacements = actualRainPlacements,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            hourWidth = hourWidth,
            nowX = nowX,
            labelSignal = labelSignal,
            nowLabelPlacement = nowLabelPlacement,
            dayLabelPlacements = dayPlacements,
            watermarkPlacement = watermarkPlacement,
            iconBounds = drawnIconBounds,
            dayNightBoundaryXs = dayNightBoundaryXs,
        )
    }

    private fun ensurePaints(context: Context, heightDp: Float, labelScale: Float) =
        PrecipitationGraphStyle.ensurePaints(context, heightDp, labelScale)

    data class GraphGeometry(
        val widthPx: Int,
        val heightPx: Int,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
    )

    internal fun calculateRainAmountPlacements(
        rainPeriods: List<RainPeriod>,
        geometry: GraphGeometry,
        initialCollisionBounds: List<PrecipRect>,
        labelPrefix: String = "",
        measureText: (String) -> Float,
        getTextBounds: (String) -> Pair<Float, Float>,
        dpToPx: (Float) -> Float
    ): List<RainAmountPlacement> {
        val placements = mutableListOf<RainAmountPlacement>()
        val rainCollisionBounds = initialCollisionBounds.toMutableList()
        val xFractions = HourlyGraphDefaults.OVERLAY_X_FRACTIONS
        val yFractions = HourlyGraphDefaults.OVERLAY_Y_FRACTIONS
        val rainPadPx = dpToPx(RAIN_AMOUNT_PADDING_DP)

        for (period in rainPeriods) {
            val amountText = labelPrefix + formatPrecipAmount(period.totalAmountMm)
            val textWidth = measureText(amountText)
            val (textAscent, textDescent) = getTextBounds(amountText)

            var best: RainCandidate? = null

            // Day/night and per-hour periods carry an anchorX so the label sits over its region;
            // window totals (anchorX == null) keep the legacy free-floating grid search.
            val candidateXs = period.anchorX?.let { listOf(it) }
                ?: xFractions.map { geometry.widthPx * it }

            outer@ for (yFrac in yFractions) {
                for (rawX in candidateXs) {
                    val cx = rawX.coerceIn(textWidth / 2f, geometry.widthPx - textWidth / 2f)
                    val cy = geometry.graphTop + geometry.graphHeight * yFrac
                    val candidateBounds = PrecipRect(
                        cx - textWidth / 2f,
                        cy + textAscent,
                        cx + textWidth / 2f,
                        cy + textDescent,
                    )
                    if (candidateBounds.top < geometry.graphTop || candidateBounds.bottom > geometry.graphBottom) continue

                    val paddedBounds = PrecipRect(
                        candidateBounds.left - rainPadPx,
                        candidateBounds.top - rainPadPx,
                        candidateBounds.right + rainPadPx,
                        candidateBounds.bottom + rainPadPx,
                    )
                    val overlapping = rainCollisionBounds.filter { it.intersects(paddedBounds) }
                    if (overlapping.isEmpty()) {
                        best = RainCandidate(cx, cy, candidateBounds, 0f)
                        break@outer
                    }
                    val overlapArea = overlapping.sumOf { existing ->
                        val intersectLeft = maxOf(existing.left, paddedBounds.left)
                        val intersectTop = maxOf(existing.top, paddedBounds.top)
                        val intersectRight = minOf(existing.right, paddedBounds.right)
                        val intersectBottom = minOf(existing.bottom, paddedBounds.bottom)
                        if (intersectLeft < intersectRight && intersectTop < intersectBottom) {
                            ((intersectRight - intersectLeft) * (intersectBottom - intersectTop)).toDouble()
                        } else 0.0
                    }.toFloat()
                    if (overlapArea < (best?.overlapArea ?: Float.MAX_VALUE)) {
                        best = RainCandidate(cx, cy, candidateBounds, overlapArea)
                    }
                }
            }

            best?.let { b ->
                placements.add(RainAmountPlacement(amountText, b.x, b.y, b.bounds, b.overlapArea))
                rainCollisionBounds.add(
                    PrecipRect(
                        b.bounds.left - rainPadPx,
                        b.bounds.top - rainPadPx,
                        b.bounds.right + rainPadPx,
                        b.bounds.bottom + rainPadPx,
                    )
                )
            }
        }
        return placements
    }

    private data class RainCandidate(
        val x: Float,
        val y: Float,
        val bounds: PrecipRect,
        val overlapArea: Float,
    )

    fun renderGraph(
        context: Context,
        hours: List<PrecipHourData>,
        widthPx: Int,
        heightPx: Int,
        currentTime: LocalDateTime,
        bitmapScale: Float = 1f,
        smoothIterations: Int = 2,
        hourLabelSpacingDp: Float = HourlyGraphDefaults.DEFAULT_HOUR_LABEL_SPACING_DP,
        rainAmountWindowHours: Int = 0,
        rainLabelMode: RainLabelMode = RainLabelMode.WINDOW_TOTAL,
        numColumns: Int = 0,
        job: Job? = null,
        onDebugLog: ((String) -> Unit)? = null,
        onLabelPlaced: ((LabelPlacementDebug) -> Unit)? = null,
        onHourIconDrawn: ((index: Int) -> Unit)? = null,
        onDayLabelPlaced: ((DayLabelPlacementDebug) -> Unit)? = null,
        onWatermarkPlaced: ((WatermarkPlacementDebug) -> Unit)? = null,
        showErrorWatermark: Boolean = false,
        errorSourceLabel: String? = null,
        errorCode: String? = null,
        errorFailureTimeMs: Long? = null,
    ): Bitmap {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hours.isEmpty()) {
            Log.w(TAG, "renderGraph: empty hours list, returning blank bitmap (${widthPx}x${heightPx})")
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
        val labelScale = bitmapScale.coerceAtMost(1f)
        val paints = ensurePaints(context, heightDp, labelScale)
        val showHourlyIcons = hours.any { it.iconRes != null } && widthPx >= HourlyGraphDefaults.MIN_ICON_GRAPH_WIDTH_PX
        val footerIconSize = HourlyFooterRenderer.iconSize(paints.hourLabelTextPaint)
        val footerHourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)
        val footerPoints = hours.indices.map { index -> footerHourWidth * index to 0f }
        val minHourLabelSpacing = dpToPx(context, hourLabelSpacingDp)
        val footerPlan = HourlyFooterRenderer.planHourLabels(
            items = hours,
            points = footerPoints,
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
            iconsAvailable = showHourlyIcons,
        )
        val footerIconBounds =
            footerPlan.placements.mapNotNull { placement ->
                placement.iconBounds?.let {
                    PrecipRect(it.left, it.top, it.right, it.bottom)
                }
            }

        val textMeasurer = TextMeasurer(
            measureProbabilityText = { paints.percentLabelPaint.measureText(it) },
            getProbabilityTextBounds = {
                val fm = paints.percentLabelPaint.fontMetrics
                (fm?.ascent ?: -paints.percentLabelPaint.textSize) to (fm?.descent ?: 0f)
            },
            measureRainAmountText = { paints.rainAmountPaint.measureText(it) },
            getRainAmountTextBounds = {
                val fm = paints.rainAmountPaint.fontMetrics
                (fm?.ascent ?: -paints.rainAmountPaint.textSize) to (fm?.descent ?: 0f)
            },
            measureActualRainAmountText = { paints.actualRainAmountPaint.measureText(it) },
            getActualRainAmountTextBounds = {
                val fm = paints.actualRainAmountPaint.fontMetrics
                (fm?.ascent ?: -paints.actualRainAmountPaint.textSize) to (fm?.descent ?: 0f)
            },
            dpToPx = { dpToPx(context, it) },
            measureNowText = { paints.nowLabelTextPaint.measureText(it) },
            getNowTextBounds = {
                val fm = paints.nowLabelTextPaint.fontMetrics
                (fm?.ascent ?: -paints.nowLabelTextPaint.textSize) to (fm?.descent ?: 0f)
            },
            measureDayText = { text, isToday ->
                (if (isToday) paints.todayDayLabelPaint else paints.dayLabelTextPaint).measureText(text)
            },
            getDayTextBounds = { isToday ->
                val paint = if (isToday) paints.todayDayLabelPaint else paints.dayLabelTextPaint
                val fm = paint.fontMetrics
                (fm?.ascent ?: -paint.textSize) to (fm?.descent ?: 0f)
            },
        )

        val nowLabelText = context.getString(R.string.forecast_hourly_legend)
        val layout = calculateLayout(
            hours = hours,
            widthPx = widthPx,
            heightPx = heightPx,
            currentTime = currentTime,
            bitmapScale = bitmapScale,
            smoothIterations = smoothIterations,
            rainAmountWindowHours = rainAmountWindowHours,
            rainLabelMode = rainLabelMode,
            showHourlyIcons = showHourlyIcons,
            footerIconSize = footerIconSize,
            footerIconBounds = footerIconBounds,
            textMeasurer = textMeasurer,
            onDebugLog = onDebugLog,
            nowLabelText = nowLabelText,
        )

        paints.gradientPaint.shader = LinearGradient(
            0f, layout.graphTop, 0f, layout.graphBottom,
            Color.parseColor(COLOR_CURVE_FILL_TOP), Color.parseColor(COLOR_CURVE_FILL_BOTTOM), Shader.TileMode.CLAMP,
        )

        // --- Draw current-time line early so it's behind labels and curves (lowest z-order) ---
        HourlyIndicatorRenderer.drawNowLine(
            canvas = canvas,
            nowX = layout.nowX,
            graphTop = layout.graphTop,
            graphHeight = layout.graphHeight,
            currentTimePaint = paints.currentTimePaint
        )

        val (curvePath, fillPath) = AndroidCurvePathBuilder.buildSmoothCurveAndFillPaths(layout.points, layout.graphBottom)
        canvas.drawPath(fillPath, paints.gradientPaint)
        canvas.drawPath(curvePath, paints.curvePaint)

        // --- Draw labels and current-time indicator ---
        HourlyFooterRenderer.drawPlan(
            canvas = canvas,
            plan = footerPlan,
            hourLabelTextPaint = paints.hourLabelTextPaint,
        ) { index, iconRect ->
            val hour = hours[index]
            val iconRes = hour.iconRes ?: return@drawPlan
            HourlyFooterRenderer.drawHourIcon(
                context, canvas, iconRes, iconRect,
                isRainy = hour.isRainy, isMixed = hour.isMixed,
                isNight = hour.isNight, isTwilight = hour.isTwilight, isSunny = hour.isSunny,
            )
            onHourIconDrawn?.invoke(index)
        }

        for (placement in layout.probabilityPlacements) {
            canvas.drawText(placement.text, placement.x, placement.baselineY, paints.percentLabelPaint)
            if (placement.index == hours.lastIndex) {
                val logMsg = "PLACED end label: ${placement.text} at right edge"
                Log.d(TAG, logMsg)
                onDebugLog?.invoke(logMsg)
            }
            onLabelPlaced?.invoke(placement.debug)
        }

        for (placement in layout.rainAmountPlacements) {
            canvas.drawText(placement.text, placement.x, placement.y, paints.rainAmountPaint)
            val logMsg = "rainAmountPlaced: \"${placement.text}\" at x=${placement.x} y=${placement.y} widgetSize=${widthPx}x${heightPx} overlapArea=${placement.overlapArea}"
            Log.d(TAG, logMsg)
            onDebugLog?.invoke(logMsg)
        }
        for (placement in layout.actualRainAmountPlacements) {
            canvas.drawText(placement.text, placement.x, placement.y, paints.actualRainAmountPaint)
            val logMsg = "actualRainAmountPlaced: \"${placement.text}\" at x=${placement.x} y=${placement.y} widgetSize=${widthPx}x${heightPx} overlapArea=${placement.overlapArea}"
            Log.d(TAG, logMsg)
            onDebugLog?.invoke(logMsg)
        }

        val drawnLabelBounds = (
            layout.probabilityPlacements.map { it.bounds.toRectF() } +
                layout.rainAmountPlacements.map { it.bounds.toRectF() } +
                layout.actualRainAmountPlacements.map { it.bounds.toRectF() }
            ).toMutableList()

        // Day/night dividers span the full plot height so the day vs night regions read clearly;
        // drawn before the NOW line so NOW stays visually dominant where they coincide.
        for (boundaryX in layout.dayNightBoundaryXs) {
            canvas.drawLines(
                floatArrayOf(boundaryX, layout.graphTop, boundaryX, layout.graphBottom),
                paints.dayNightDividerPaint,
            )
        }
        layout.nowLabelPlacement?.let { placement ->
            canvas.drawText(nowLabelText, placement.x, placement.baselineY, paints.nowLabelTextPaint)
            drawnLabelBounds.add(placement.bounds.toRectF())
        }

        for (placement in layout.dayLabelPlacements) {
            val paint = if (placement.isToday) paints.todayDayLabelPaint else paints.dayLabelTextPaint
            canvas.drawText(placement.dayText, placement.x, placement.y, paint)
            onDayLabelPlaced?.invoke(placement)
        }

        // Rain cloud icon watermark
        val rainDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_weather_rain)
        if (rainDrawable != null && layout.watermarkPlacement != null) {
            val placement = layout.watermarkPlacement
            val iconSizePx = dpToPx(context, HourlyGraphDefaults.WATERMARK_ICON_SIZE_DP).toInt()
            rainDrawable.alpha = HourlyGraphDefaults.WATERMARK_ALPHA
            rainDrawable.setBounds(placement.x.toInt(), placement.y.toInt(), (placement.x + iconSizePx).toInt(), (placement.y + iconSizePx).toInt())
            rainDrawable.draw(canvas)
            onWatermarkPlaced?.invoke(placement)
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
     * Maps the renderer's row type onto the shared one. The actual field is passed through as-is:
     * [PrecipViewHandler] has already nulled it for hours that have not elapsed, and that gate is
     * what keeps an "Act" label out of the future.
     */
    private fun List<PrecipHourData>.toRainHours(): List<RainPeriodSelection.RainHour> = map {
        RainPeriodSelection.RainHour(
            dateTime = it.dateTime,
            precipAmountMm = it.precipAmountMm,
            actualPrecipAmountMm = it.actualPrecipAmountMm,
            label = it.label,
        )
    }

    // Thin delegates over RainPeriodSelection. They exist so the renderer's own tests keep asserting
    // Android behaviour against the shared implementation; the logic has one home.

    internal fun findVisibleWindowRainPeriods(hours: List<PrecipHourData>): List<RainPeriod> =
        RainPeriodSelection.findVisibleWindowRainPeriods(hours.toRainHours()) { it.precipAmountMm }

    internal fun findVisibleWindowActualRainPeriods(hours: List<PrecipHourData>): List<RainPeriod> =
        RainPeriodSelection.findVisibleWindowRainPeriods(hours.toRainHours()) { it.actualPrecipAmountMm }

    internal fun findFixedWindowRainPeriods(hours: List<PrecipHourData>, windowHours: Int): List<RainPeriod> =
        RainPeriodSelection.findFixedWindowRainPeriods(hours.toRainHours(), windowHours) { it.precipAmountMm }

    internal fun findFixedWindowActualRainPeriods(hours: List<PrecipHourData>, windowHours: Int): List<RainPeriod> =
        RainPeriodSelection.findFixedWindowRainPeriods(hours.toRainHours(), windowHours) { it.actualPrecipAmountMm }

    internal fun dayNightRuns(hours: List<PrecipHourData>): List<DayNightSegment> =
        RainPeriodSelection.dayNightRuns(hours.toRainHours())

    internal fun selectDayNightSegments(hours: List<PrecipHourData>): List<DayNightSegment> =
        RainPeriodSelection.selectDayNightSegments(hours.toRainHours())

    internal fun perHourRainPeriods(
        hours: List<PrecipHourData>,
        hourWidth: Float,
        amountFor: (RainPeriodSelection.RainHour) -> Float?,
    ): List<RainPeriod> =
        RainPeriodSelection.perHourRainPeriods(hours.toRainHours(), hourWidth, amountFor)

    internal fun computeDayNightBoundaryXs(hours: List<PrecipHourData>, hourWidth: Float): List<Float> =
        RainPeriodSelection.computeDayNightBoundaryXs(hours.toRainHours(), hourWidth)

    private fun dpToPx(context: Context, dp: Float): Float =
        PrecipitationGraphStyle.dpToPx(context, dp)
}
