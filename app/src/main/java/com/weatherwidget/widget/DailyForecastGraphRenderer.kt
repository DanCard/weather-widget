package com.weatherwidget.widget

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.BuildConfig
import com.weatherwidget.widget.handlers.CloudCoverDiagnosticRow
import com.weatherwidget.widget.handlers.HeaderConstants
import com.weatherwidget.shared.graph.DualHighLabel
import com.weatherwidget.shared.graph.TodayColumnHighlight
import com.weatherwidget.util.WeatherConditionColors
import com.weatherwidget.util.WeatherIconMapper
import kotlin.math.abs
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object DailyForecastGraphRenderer {
    private const val TAG = "DailyGraphRenderer"

    private inline fun debug(msg: () -> String) {
        Log.d(TAG, msg())
    }

    private const val DAY_LABEL_SIZE_MULTIPLIER = 1.15f
    private const val DAY_LABEL_TEXT_SCALE = 1.5f
    private const val BASE_DAY_WIDTH_DP = 70f
    private const val MIN_DAY_LABEL_WIDTH_SCALE = 0.96f
    private const val MAX_DAY_LABEL_WIDTH_SCALE = 1.04f
    private const val MIN_DYNAMIC_DAY_LABEL_SCALE = 0.72f
    private const val DAY_LABEL_HORIZONTAL_GAP_DP = 4f
    // Temp-label shrink-to-fit budget. Rather than enforce a gap, we ALLOW a small overlap into the
    // neighbouring column before shrinking, so labels stay as large as possible — a sliver of overlap
    // reads better than visibly smaller text. Only labels wider than (column + this allowance) shrink,
    // and only down to MIN_TEMP_LABEL_FIT_SCALE of their size (legibility floor).
    private const val TEMP_LABEL_OVERLAP_ALLOWANCE_DP = 6f
    private const val MIN_TEMP_LABEL_FIT_SCALE = 0.7f
    private const val MIN_BAR_HEIGHT_DP = 1.0f

    private val COLOR_FORECAST = 0xFF5AC8FA.toInt()
    private val COLOR_TODAY_HIGHLIGHT = 0xFFFFFF00.toInt()
    private val COLOR_OBSERVED_RED = WeatherConditionColors.OBSERVED
    private val COLOR_LABEL_GRAY = 0xFFAAAAAA.toInt()
    private val COLOR_TODAY_TEXT = 0xFFFFEACC.toInt()
    private val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private val COLOR_GAP_FALLBACK = 0xFF34C759.toInt()
    private val COLOR_SUNNY = 0xFFFFD60A.toInt()
    internal val HEADER_TEXT_COLOR = 0xAAFFFFFF.toInt()
    private const val RAIN_FONT_SCALE_K = 0.6f
    private const val RAIN_FONT_SCALE_MAX_DAYS = 7f
    private const val TEMP_LABEL_TEXT_SIZE_DP = 24f
    private const val TOP_PADDING_DP = 50f
    private const val FORECAST_BAR_WIDTH_DP = 9f
    private const val TODAY_TRIPLE_BAR_WIDTH_DP = 8f

    private const val HIGH_LABEL_OFFSET_DP = 8f
    private const val ICON_BELOW_BAR_SPACING_DP = 3f
    private const val TEMP_LABEL_SPACING_DP = -1f
    private const val RAIN_TEXT_MARGIN_DP = 4f
    private const val RAIN_LABEL_EDGE_MARGIN_DP = 4f
    private const val ICON_STACK_SPACING_DP = 4f
    private const val DAY_LABEL_BASE_SIZE_DP = 17f
    private const val ICON_BASE_SIZE_DP = 36f
    private val RAIN_TEXT_SIZE_DP = HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP
    // Tiny margin in dp so day label baseline sits just inside bitmap bottom edge across densities.
    internal const val DAY_LABEL_BOTTOM_MARGIN_DP = 1f
    private const val GHOST_BAR_ALPHA = 75
    private const val CLIMATE_OVERLAY_ALPHA = 80
    private const val BULB_RADIUS_SCALE = 1.2f
    private const val BULB_VERTICAL_CENTER_FRACTION = 0.5f
    private const val HISTORY_BAR_WIDTH_SCALE = 0.7f
    private const val FORECAST_OVERLAY_WIDTH_SCALE = 0.7f
    private const val CLIMATE_OVERLAY_WIDTH_SCALE = 0.8f
    private const val FORECAST_BAR_OFFSET_SCALE = 0.7f
    private const val PAST_TEMP_SCALE = 0.9f
    private const val LABEL_SHADOW_RADIUS_DP = 2.5f
    private const val LABEL_SHADOW_DY_DP = 1.0f
    // Thin black outline stroke (fraction of font size) for HISTORY temp labels only, so they stay
    // legible over same-colored bars. Much thinner than the reverted heavy outline; gated to past days.
    private const val LABEL_OUTLINE_STROKE_FRACTION = 0.12f
    private const val HEADER_RAIN_OVERLAP_TOLERANCE_DP = 4f

    // Today-column emphasis (frosted-glass panel + touching triple bars) is shared with desktop via
    // com.weatherwidget.shared.graph.TodayColumnHighlight — see it for the geometry/styling constants.

    private data class PaintCache(
        val scaleFactor: Float,
        val dayLabelHeight: Float,
        val tempLabelHeight: Float,
        val set: PaintSet,
    )
    private const val PAINT_CACHE_LRU_SIZE = 3
    @Volatile
    private var paintCaches: List<PaintCache> = emptyList()

    /**
     * Fired once for each bar drawn, for testing and debugging.
     */
    data class BarDrawnDebug(
        val date: LocalDate,
        val barType: String,
        val highY: Float,
        val lowY: Float,
        val centerX: Float,
        val color: Int,
        val adaptiveSegments: Boolean = false,
    )

    data class RainLabelDrawnDebug(
        val date: LocalDate,
        val text: String,
        val placement: String,
        val centerX: Float,
        val leftX: Float = Float.NaN,
        val rightX: Float = Float.NaN,
        val baselineY: Float,
        val topY: Float = Float.NaN,
        val bottomY: Float = Float.NaN,
        val anchorTopY: Float = Float.NaN,
        val anchorBaselineY: Float = Float.NaN,
        val isNightLabel: Boolean = false,
    )

    data class HeaderDrawnDebug(
        val dateText: String?,
        val dateSuppressedForRainOverlap: Boolean,
    )

    data class DayLabelDrawnDebug(
        val date: LocalDate,
        val text: String,
        val centerX: Float,
        val baselineY: Float,
        val leftX: Float,
        val rightX: Float,
        val textSize: Float,
    )

    /**
     * Groups all precipitation-related data for a day.
     * Used for rain labels, probability, and amount data.
     */
    data class RainData(
        val rainSummary: String? = null,
        /** Daytime precipitation probability, 0–100 (percentage). Divided by 100 internally for font scaling. */
        val dailyPrecipProbability: Int? = null,
        /** Nighttime precipitation probability, 0–100 (percentage). Divided by 100 internally for font scaling. */
        val nighttimePrecipProbability: Int? = null,
        val dailyPrecipAmountMm: Float? = null,
        val dailyRainLabelText: String? = null,
        val nightRainLabelText: String? = null,
        val hasRainForecast: Boolean = false,
    )

    data class HeaderRenderData(
        val iconRes: Int? = null,
        val currentTempText: String? = null,
        val deltaText: String? = null,
        val deltaColor: Int = 0xFFFF6B35.toInt(),
        val precipText: String? = null,
        val precipColor: Int = 0xFF5AC8FA.toInt(),
        val precipTextSizeDp: Float = HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP,
        val dateText: String? = null,
        val apiSourceText: String? = null,
        val apiTextSizeDp: Float = 12.6f,
        val settingsIconRes: Int = 0,
        val showIcon: Boolean = true,
        val showDelta: Boolean = true,
        val showPrecip: Boolean = true,
        /** Scale applied to header icons and fonts on wide widgets (1.0 = normal, 1.35 = wide). */
        val headerScale: Float = 1f,
    )

    data class DayData(
        override val date: LocalDate,
        val label: String,
        val solidLineHigh: Float?,
        val solidLineLow: Float?,
        val bottomStackLow: Float? = null,
        val iconRes: Int? = null,
        val isSunny: Boolean = false,
        val isRainy: Boolean = false,
        val isMixed: Boolean = false,
        val isToday: Boolean = false,
        val isPast: Boolean = false,
        val isClimateNormal: Boolean = false,
        val isSourceGapFallback: Boolean = false,
        val dashedLineHigh: Float? = null,
        val dashedLineLow: Float? = null,
        val rainData: RainData = RainData(),
        val columnIndex: Int? = null,
        val isTodayForecastFallback: Boolean = false,
        val snapshotHigh: Float? = null,
        val snapshotLow: Float? = null,
        val snapshotIconRes: Int? = null,
        val ghostLineHigh: Float? = null,
        override val cloudCoverRatioOverride: Float? = null,
        override val daysFromToday: Int = 0,
        /** Local hour-of-day (0–23) for the today column's actual-tracking cutoffs; null = legacy. */
        val nowHour: Int? = null,
    ) : CloudCoverDiagnosticRow

    private fun DayData.effectiveHigh(): Float? =
        com.weatherwidget.shared.util.DailyDayValueResolver.effectiveHighForLabel(
            isToday = isToday,
            solidHigh = solidLineHigh,
            forecastHigh = dashedLineHigh,
            ghostHigh = ghostLineHigh,
            nowHour = nowHour,
        )

    data class LayoutInfo(
        val widthPx: Int,
        val heightPx: Int,
        val columns: Int,
        val minTemp: Float,
        val maxTemp: Float,
        val tempRange: Float,
        val scaleFactor: Float,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val dayWidth: Float,
        // Width budget for a single (centered) temp label before it overflows into the next column.
        val tempLabelMaxWidthPx: Float,
        val horizontalPadding: Float,
        val tripleBarOffset: Float,
        val forecastBarOffset: Float,
        val iconSize: Int,
        val dayLabelHeight: Float,
        val tempLabelHeight: Float,
        val bulbRadius: Float,
        val bitmapScale: Float,
        val minBarHeightPx: Float,
        val dayLabelTextByDate: Map<LocalDate, String>,
        val density: Float,
        val useCelsius: Boolean,
    ) {
        fun tempToY(temp: Float): Float =
            graphTop + graphHeight * (1 - (temp - minTemp) / tempRange)
    }

    @VisibleForTesting
    data class DayLabelInput(
        val date: LocalDate,
        val label: String,
        val isToday: Boolean = false,
    )

    @VisibleForTesting
    data class DayLabelLayoutResult(
        val textSizePx: Float,
        val textByDate: Map<LocalDate, String>,
        val scale: Float,
        val shortenedLabels: Boolean,
    )

    @VisibleForTesting
    data class RainAboveHighPlacement(
        val baseline: Float,
        val top: Float,
        val bottom: Float,
        val highLabelTop: Float,
        val fits: Boolean,
    )

    @VisibleForTesting
    data class TextMetrics(
        val ascent: Float,
        val descent: Float,
    )

    internal class PaintSet(
        val barPaint: Paint,
        val todayObservedRedPaint: Paint,
        val todayObservedGhostPaint: Paint,
        val todayObservedRedBulbPaint: Paint,
        val todaySnapshotYellowPaint: Paint,
        val todayForecastBluePaint: Paint,
        val historyBarPaint: Paint,
        val forecastBarPaint: Paint,
        val climateOverlayBarPaint: Paint,
        val gapFallbackBarPaint: Paint,
        val textPaint: Paint,
        val todayTextPaint: Paint,
        val tempTextPaint: Paint,
        val pastTempTextPaint: Paint,
        val todayTempTextPaint: Paint,
        val rainTextPaint: Paint,
    ) {
        private val barByColor = ConcurrentHashMap<Int, Paint>()
        private val forecastByColor = ConcurrentHashMap<Int, Paint>()
        private val climateOverlayByColor = ConcurrentHashMap<Int, Paint>()
        private val todayForecastByColor = ConcurrentHashMap<Int, Paint>()

        fun barForColor(color: Int): Paint = barByColor.getOrPut(color) {
            Paint(barPaint).apply { this.color = color }
        }
        fun forecastForColor(color: Int): Paint = forecastByColor.getOrPut(color) {
            Paint(forecastBarPaint).apply { this.color = color }
        }
        fun climateOverlayForColor(color: Int): Paint = climateOverlayByColor.getOrPut(color) {
            // Paint.setColor overwrites alpha, so re-apply CLIMATE_OVERLAY_ALPHA after.
            Paint(climateOverlayBarPaint).apply { this.color = color; alpha = CLIMATE_OVERLAY_ALPHA }
        }
        fun todayForecastForColor(color: Int): Paint = todayForecastByColor.getOrPut(color) {
            Paint(todayForecastBluePaint).apply { this.color = color }
        }
    }

    fun renderGraph(
        context: Context,
        days: List<DayData>,
        widthPx: Int,
        heightPx: Int,
        bitmapScale: Float = 1f,
        numColumns: Int = 0,
        job: Job? = null,
        onBarDrawn: ((BarDrawnDebug) -> Unit)? = null,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)? = null,
        onDayLabelDrawn: ((DayLabelDrawnDebug) -> Unit)? = null,
        headerData: HeaderRenderData? = null,
        showErrorWatermark: Boolean = false,
        errorSourceLabel: String? = null,
        errorCode: String? = null,
        errorFailureTimeMs: Long? = null,
        onHeaderDrawn: ((HeaderDrawnDebug) -> Unit)? = null,
        useCelsius: Boolean,
    ): Bitmap {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (days.isEmpty()) {
            Log.w(TAG, "renderGraph: empty days list, returning blank bitmap (${widthPx}x${heightPx})")
            if (showErrorWatermark) {
                val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
                GraphRenderUtils.drawErrorWatermark(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
            }
            return bitmap
        }

        val columns = if (numColumns > 0) numColumns else days.size
        val layout = computeLayout(context, days, widthPx, heightPx, columns, bitmapScale, job, useCelsius = useCelsius)
        val paints = getPaintSet(layout.scaleFactor, layout)

        debug { "renderGraph: days=${days.size}, minTemp=${layout.minTemp}, maxTemp=${layout.maxTemp}, widthPx=$widthPx, heightPx=$heightPx" }

        val daysByColumn = days.withIndex().associate { (i, d) -> (d.columnIndex ?: i) to d }
        days.forEachIndexed { index, day ->
            job?.ensureActive()
            val rawColumnIndex = day.columnIndex ?: index
            val columnIndex = rawColumnIndex.coerceIn(0, layout.columns - 1)
            if (rawColumnIndex != columnIndex) {
                Log.w(TAG, "renderGraph: clamped out-of-range columnIndex date=${day.date} requested=$rawColumnIndex columns=${layout.columns}")
            }
            val centerX = layout.horizontalPadding + layout.dayWidth * columnIndex + layout.dayWidth / 2f
            val rightNeighbor = daysByColumn[columnIndex + 1]

            // Frosted-glass focal panel BEHIND today's triple-bar column (drawn before the bars so it
            // sits underneath them and their labels).
            if (day.isToday) {
                drawTodayHighlightPanel(canvas, centerX, layout)
            }

            // Bars first, then column content (weather icon, low/day labels) so the icon
            // and labels render on top of any bar geometry that might overlap them.
            drawDayBars(canvas, context, day, centerX, layout, paints, onBarDrawn)
            drawDayColumn(canvas, context, day, rightNeighbor, centerX, layout, paints, onRainLabelDrawn, onDayLabelDrawn)
        }

        val finalHeaderData = headerData?.let { suppressHeaderDateForRainOverlap(it, days, layout, paints, widthPx) }
        if (finalHeaderData != null) {
            DailyForecastHeaderRenderer.drawHeader(canvas, context, finalHeaderData, widthPx, layout)
            onHeaderDrawn?.invoke(
                HeaderDrawnDebug(
                    dateText = finalHeaderData.dateText,
                    dateSuppressedForRainOverlap = !headerData.dateText.isNullOrBlank() && finalHeaderData.dateText == null,
                ),
            )
        }

        if (showErrorWatermark) {
            val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
            GraphRenderUtils.drawErrorWatermark(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
        }

        return bitmap
    }

    private fun suppressHeaderDateForRainOverlap(
        headerData: HeaderRenderData,
        days: List<DayData>,
        layout: LayoutInfo,
        paints: PaintSet,
        widthPx: Int,
    ): HeaderRenderData {
        if (headerData.dateText.isNullOrBlank()) return headerData
        val padding = (2f).dp(layout.density)
        val dateBounds = DailyForecastHeaderRenderer.resolveHeaderDateBounds(
            header = headerData,
            widthPx = widthPx,
            layout = layout,
            extraPaddingPx = padding,
        ) ?: return headerData

        days.forEachIndexed { index, day ->
            val rawColumnIndex = day.columnIndex ?: index
            val columnIndex = rawColumnIndex.coerceIn(0, layout.columns - 1)
            val centerX = layout.horizontalPadding + layout.dayWidth * columnIndex + layout.dayWidth / 2f
            val rainLabel = DailyForecastRainLabelRenderer.resolveDailyRainLabelPlacement(
                day = day,
                centerX = centerX,
                layout = layout,
                paints = paints,
            )?.debug ?: return@forEachIndexed
            val rainBounds = RectF(rainLabel.leftX, rainLabel.topY, rainLabel.rightX, rainLabel.bottomY)
            if (hasMeaningfulHeaderRainOverlap(dateBounds, rainBounds, layout.density)) {
                Log.d(
                    TAG,
                    "suppressHeaderDateForRainOverlap: dateText=${headerData.dateText} rainDate=${day.date}" +
                        " rainText=${rainLabel.text} dateBounds=$dateBounds rainBounds=$rainBounds",
                )
                return headerData.copy(dateText = null)
            }
        }
        return headerData
    }

    @VisibleForTesting
    internal fun hasMeaningfulHeaderRainOverlap(
        dateBounds: RectF,
        rainBounds: RectF,
        density: Float,
    ): Boolean {
        val overlapWidth = minOf(dateBounds.right, rainBounds.right) - maxOf(dateBounds.left, rainBounds.left)
        val overlapHeight = minOf(dateBounds.bottom, rainBounds.bottom) - maxOf(dateBounds.top, rainBounds.top)
        val tolerancePx = HEADER_RAIN_OVERLAP_TOLERANCE_DP.dp(density)
        return overlapWidth > tolerancePx && overlapHeight > tolerancePx
    }

    private fun computeLayout(
        context: Context,
        days: List<DayData>,
        widthPx: Int,
        heightPx: Int,
        columns: Int,
        bitmapScale: Float,
        job: Job? = null,
        useCelsius: Boolean,
    ): LayoutInfo {
        job?.ensureActive()
        val allTemps = days.flatMap { listOfNotNull(it.solidLineHigh, it.solidLineLow, it.dashedLineHigh, it.dashedLineLow, it.snapshotHigh, it.snapshotLow, it.ghostLineHigh) }
        val minTemp = allTemps.minOrNull() ?: 0f
        val maxTemp = allTemps.maxOrNull() ?: 100f
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)

        val density = context.resources.displayMetrics.density
        val widthDp = widthPx / density
        val heightDp = heightPx / density

        val dayWidthDp = widthDp / columns
        val widthScaleFactor = (dayWidthDp / BASE_DAY_WIDTH_DP).coerceIn(1.0f, 1.2f)
        val dayLabelWidthScale = computeDayLabelWidthScale(dayWidthDp)

        // Only used for tempLabelHeight scaling, not the overall scaleFactor which is width-driven.
        val heightScaleFactor = when {
            heightDp < 150f -> 0.92f
            else -> 1.0f
        }

        val scaleFactor = widthScaleFactor
        val labelScale = bitmapScale.coerceAtMost(1f)
        val horizontalPadding = 0f
        val topPadding = (TOP_PADDING_DP * labelScale).dp(density)

        val dayLabelScale = labelScale * dayLabelWidthScale
        val baseDayLabelTextSizePx = (DAY_LABEL_BASE_SIZE_DP * dayLabelScale * DAY_LABEL_TEXT_SCALE).dp(density)
        val dayWidth = (widthPx - 2 * horizontalPadding) / columns
        val dayLabelLayout = resolveDayLabelLayout(
            labels = days.map { DayLabelInput(it.date, it.label, it.isToday) },
            baseTextSizePx = baseDayLabelTextSizePx,
            maxTextWidthPx = (dayWidth - (DAY_LABEL_HORIZONTAL_GAP_DP * labelScale).dp(density)).coerceAtLeast(1f),
            minScale = MIN_DYNAMIC_DAY_LABEL_SCALE,
        )
        val dayLabelHeight = dayLabelLayout.textSizePx * DAY_LABEL_SIZE_MULTIPLIER
        val tempLabelHeight = dailyForecastTempLabelSizePx(density, heightScaleFactor, bitmapScale)

        val iconSize = (ICON_BASE_SIZE_DP * labelScale).dp(density).toInt()
        val attachedStackHeight = tempLabelHeight + iconSize + (ICON_STACK_SPACING_DP * labelScale).dp(density)

        val graphTop = topPadding
        val requestedGraphBottom = heightPx - dayLabelHeight - attachedStackHeight
        val graphBottom = requestedGraphBottom.coerceAtLeast(graphTop + 1f)
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        if (requestedGraphBottom < graphTop + 1f) {
            Log.w(
                TAG,
                "computeLayout: clamping undersized graph area widthPx=$widthPx heightPx=$heightPx" +
                    " graphTop=$graphTop requestedGraphBottom=$requestedGraphBottom graphBottom=$graphBottom",
            )
        }

        val barWidth = dailyBarStrokeWidthPx(density, scaleFactor, bitmapScale)
        val tripleBarWidth = todayTripleBarStrokeWidthPx(density, scaleFactor, bitmapScale)

        // Spread of today's flanking bars from the centre (shared with desktop). Android draws all
        // three bars at tripleBarWidth, so centre and flank widths are equal here.
        val tripleBarOffset = TodayColumnHighlight.tripleBarSpacing(
            centerBarWidthPx = tripleBarWidth,
            flankBarWidthPx = tripleBarWidth,
            dayWidthPx = dayWidth,
            columnEdgeMarginPx = (2f).dp(density),
        )

        if (dayLabelLayout.scale < 0.999f || dayLabelLayout.shortenedLabels) {
            debug {
                "dayLabel layout adjusted: widthPx=$widthPx columns=$columns dayWidth=$dayWidth" +
                    " baseTextSize=$baseDayLabelTextSizePx finalTextSize=${dayLabelLayout.textSizePx}" +
                    " scale=${dayLabelLayout.scale} shortened=${dayLabelLayout.shortenedLabels}" +
                    " labels=${dayLabelLayout.textByDate.values.joinToString(",")}"
            }
        }

        return LayoutInfo(
            widthPx = widthPx,
            heightPx = heightPx,
            columns = columns,
            minTemp = minTemp,
            maxTemp = maxTemp,
            tempRange = tempRange,
            scaleFactor = scaleFactor,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            dayWidth = dayWidth,
            tempLabelMaxWidthPx = dayWidth + (TEMP_LABEL_OVERLAP_ALLOWANCE_DP * labelScale).dp(density),
            horizontalPadding = horizontalPadding,
            tripleBarOffset = tripleBarOffset,
            forecastBarOffset = barWidth * FORECAST_BAR_OFFSET_SCALE,
            iconSize = iconSize,
            dayLabelHeight = dayLabelHeight,
            tempLabelHeight = tempLabelHeight,
            bulbRadius = tripleBarWidth * BULB_RADIUS_SCALE,
            bitmapScale = bitmapScale,
            minBarHeightPx = (MIN_BAR_HEIGHT_DP).dp(density),
            dayLabelTextByDate = dayLabelLayout.textByDate,
            density = density,
            useCelsius = useCelsius,
        )
    }

    private fun getPaintSet(scaleFactor: Float, layout: LayoutInfo): PaintSet {
        val current = paintCaches
        current.firstOrNull {
            it.scaleFactor == scaleFactor &&
                it.dayLabelHeight == layout.dayLabelHeight &&
                it.tempLabelHeight == layout.tempLabelHeight
        }?.let { return it.set }

        val labelScale = layout.bitmapScale.coerceIn(0.5f, 1f)
        val barWidth = dailyBarStrokeWidthPx(layout.density, scaleFactor, layout.bitmapScale)
        val tripleBarWidth = todayTripleBarStrokeWidthPx(layout.density, scaleFactor, layout.bitmapScale)
        val shadowRadius = (LABEL_SHADOW_RADIUS_DP * labelScale).dp(layout.density)
        val shadowDy = (LABEL_SHADOW_DY_DP * labelScale).dp(layout.density)

        val set = PaintSet(
            barPaint = createBarPaint(COLOR_FORECAST, barWidth),
            todayObservedRedPaint = createBarPaint(COLOR_OBSERVED_RED, tripleBarWidth),
            todayObservedGhostPaint = createBarPaint(COLOR_OBSERVED_RED, tripleBarWidth).apply { alpha = GHOST_BAR_ALPHA },
            todayObservedRedBulbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_OBSERVED_RED
                style = Paint.Style.FILL
            },
            todaySnapshotYellowPaint = createBarPaint(COLOR_TODAY_HIGHLIGHT, tripleBarWidth),
            todayForecastBluePaint = createBarPaint(COLOR_FORECAST, tripleBarWidth),
            historyBarPaint = createBarPaint(COLOR_OBSERVED_RED, barWidth * HISTORY_BAR_WIDTH_SCALE),
            forecastBarPaint = createBarPaint(COLOR_FORECAST, barWidth * FORECAST_OVERLAY_WIDTH_SCALE),
            climateOverlayBarPaint = createBarPaint(COLOR_FORECAST, barWidth * CLIMATE_OVERLAY_WIDTH_SCALE).apply { alpha = CLIMATE_OVERLAY_ALPHA },
            gapFallbackBarPaint = createBarPaint(COLOR_GAP_FALLBACK, barWidth),
            textPaint = createTextPaint(
                COLOR_LABEL_GRAY,
                layout.dayLabelHeight / DAY_LABEL_SIZE_MULTIPLIER
            ),
            todayTextPaint = createTextPaint(
                COLOR_TODAY_TEXT,
                layout.dayLabelHeight / DAY_LABEL_SIZE_MULTIPLIER,
                true
            ),
            // Temp labels carry no blur: history labels get a thin black outline (drawTempLabel
            // drawOutline=true), today/future labels get no shadow at all.
            tempTextPaint = createTextPaint(COLOR_WHITE, layout.tempLabelHeight),
            // History actuals (low label, and single high when not a dual-label mismatch) read as
            // the thermostat/observed color rather than plain white, matching the dual-high actual
            // label and today's settled-high recolor below.
            pastTempTextPaint = createTextPaint(COLOR_OBSERVED_RED, layout.tempLabelHeight * PAST_TEMP_SCALE),
            // Today temp labels are NOT bold (matches desktop's default weight); they stand out via
            // the COLOR_TODAY_TEXT highlight + outline, not weight.
            todayTempTextPaint = createTextPaint(COLOR_TODAY_TEXT, layout.tempLabelHeight),
            rainTextPaint = createTextPaint(COLOR_FORECAST, (RAIN_TEXT_SIZE_DP * scaleFactor * labelScale).dp(layout.density), shadowRadius = shadowRadius, shadowDy = shadowDy),
        )

        val newCache = PaintCache(scaleFactor, layout.dayLabelHeight, layout.tempLabelHeight, set)
        paintCaches = (listOf(newCache) + current).take(PAINT_CACHE_LRU_SIZE)
        return set
    }

    private fun createBarPaint(colorInt: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
    }

    private fun createTextPaint(
        colorInt: Int,
        size: Float,
        bold: Boolean = false,
        shadowRadius: Float = 0f,
        shadowDy: Float = 0f
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        textSize = size
        textAlign = Paint.Align.CENTER
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        if (shadowRadius > 0f) {
            setShadowLayer(shadowRadius, 0f, shadowDy, 0xFF000000.toInt())
        }
    }

    private fun drawWeatherAdaptiveBar(
        canvas: Canvas,
        centerX: Float,
        topY: Float,
        bottomY: Float,
        paint: Paint,
        day: DayData,
        logPrefix: String,
        allowAdaptiveSegments: Boolean = true,
    ) {
        if (!allowAdaptiveSegments || !shouldUseAdaptiveSegments(day) || day.iconRes == null) {
            canvas.drawLine(centerX, topY, centerX, bottomY, paint)
            return
        }

        val split = WeatherConditionColors.resolveMixedBarSplit(day.iconRes, day.cloudCoverRatioOverride)
        if (split == null) {
            canvas.drawLine(centerX, topY, centerX, bottomY, paint)
            return
        }

        val barHeight = bottomY - topY
        if (barHeight <= 1f) {
            canvas.drawLine(centerX, topY, centerX, bottomY, paint)
            return
        }

        val topSegmentEndY = (topY + barHeight * split.topFraction).coerceIn(topY, bottomY)
        val bottomPaint = Paint(paint).apply {
            color = split.bottomColor
            shader = null
        }
        val topPaint = Paint(paint).apply {
            color = paint.color
            shader = null
        }

        canvas.drawLine(centerX, topY, centerX, bottomY, bottomPaint)
        if (abs(topSegmentEndY - topY) > 0.5f) {
            canvas.drawLine(centerX, topY, centerX, topSegmentEndY, topPaint)
        }

        debug {
            "$logPrefix mixed bar geometry: date=${day.date} centerX=$centerX topY=$topY bottomY=$bottomY height=${barHeight}" +
                " splitRatio=${split.ratio} topFraction=${split.topFraction} topEndY=$topSegmentEndY" +
                " topColor=${String.format("#%08X", split.topColor)} bottomColor=${String.format("#%08X", split.bottomColor)}"
        }
    }

    private fun shouldUseAdaptiveSegments(day: DayData): Boolean {
        return day.isMixed || (day.cloudCoverRatioOverride ?: 0f) > 0f
    }

    private fun drawDayColumn(
        canvas: Canvas,
        context: Context,
        day: DayData,
        rightNeighbor: DayData?,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)?,
        onDayLabelDrawn: ((DayLabelDrawnDebug) -> Unit)?,
    ) {
        val labelPaint = if (day.isToday) paints.todayTextPaint else paints.textPaint
        val dayLabel = layout.dayLabelTextByDate[day.date] ?: day.label
        val dayLabelBaseline = layout.heightPx - DAY_LABEL_BOTTOM_MARGIN_DP.dp(layout.density)
        canvas.drawText(dayLabel, centerX, dayLabelBaseline, labelPaint)
        val labelWidth = measureTextWidth(labelPaint, dayLabel)
        onDayLabelDrawn?.invoke(
            DayLabelDrawnDebug(
                date = day.date,
                text = dayLabel,
                centerX = centerX,
                baselineY = dayLabelBaseline,
                leftX = centerX - labelWidth / 2f,
                rightX = centerX + labelWidth / 2f,
                textSize = labelPaint.textSize,
            ),
        )

        // Position the icon + low label under the LOWEST drawn bar (geometry), but print the
        // gated/observed low VALUE. Decoupling these keeps the icon under the deepest bar even
        // when the headline number tracks a higher actual (today) or the forecast overlay dips
        // below the observed bar (history). See DailyDayValueResolver.iconAnchorLow.
        val anchorLow = resolveIconAnchorLow(day)
        val displayLow = resolveBottomStackLow(day)
        val lowY = anchorLow?.let { layout.tempToY(it) }

        // Captured so the night rain label can treat this day's own low label (degree symbol
        // included) as an obstacle to avoid; null when no low label is drawn.
        var ownLowLabelBox: DailyForecastRainLabelRenderer.LowLabelBox? = null
        if (lowY != null) {
            val iconY = lowY + (ICON_BELOW_BAR_SPACING_DP).dp(layout.density)
            drawWeatherIcon(canvas, context, day, centerX, iconY, layout.iconSize)

            if (displayLow != null) {
                val lowTempY = iconY + layout.iconSize + layout.tempLabelHeight + (TEMP_LABEL_SPACING_DP).dp(layout.density)

                val isLowest = displayLow <= layout.minTemp + 0.01f
                val hasNightRain = day.rainData.nightRainLabelText != null
                val forceInteger = isLowest && hasNightRain
                val lowLabelText = formatTempLabel(displayLow, forceInteger = forceInteger, useCelsius = layout.useCelsius)

                val tempPaint = when {
                    day.isToday -> paints.todayTempTextPaint
                    day.isPast -> paints.pastTempTextPaint
                    else -> paints.tempTextPaint
                }
                // Mirrors the high label: once today's overnight low is settled (past the 9am
                // cutoff) the number tracks the observed actual, so it recolors to the thermostat
                // (observed) color instead of the today-highlight color.
                val todayLowSettled = com.weatherwidget.shared.util.DailyDayValueResolver.isLowTrackingActual(
                    isToday = day.isToday,
                    solidLow = day.solidLineLow,
                    nowHour = day.nowHour,
                )
                val lowColorOverride = if (todayLowSettled) COLOR_OBSERVED_RED else null
                drawTempLabel(canvas, lowLabelText, centerX, lowTempY, tempPaint,
                    colorOverride = lowColorOverride, drawOutline = day.isPast,
                    maxWidthPx = layout.tempLabelMaxWidthPx)

                // Unscaled metrics are a safe (slight) over-estimate of the drawn glyph extent,
                // which only ever makes the obstacle larger — never missing a real collision. Read
                // ascent/descent via the null-safe helpers: Paint.fontMetrics is null under the
                // stubbed-Paint Robolectric tests (see twoLabelHeight below), and they fall back to
                // textSize there.
                val lowHalfWidth = measureTextWidth(tempPaint, lowLabelText) / 2f
                ownLowLabelBox = DailyForecastRainLabelRenderer.LowLabelBox(
                    left = centerX - lowHalfWidth,
                    top = lowTempY + TemperatureGraphStyle.fontAscent(tempPaint),
                    right = centerX + lowHalfWidth,
                    bottom = lowTempY + TemperatureGraphStyle.fontDescent(tempPaint),
                    baseline = lowTempY,
                )
            }
        }

        DailyForecastRainLabelRenderer.drawDailyRainLabel(day, centerX, layout, paints, onRainLabelDrawn, canvas)
        DailyForecastRainLabelRenderer.drawNightRainLabel(day, rightNeighbor, centerX, layout, paints, ownLowLabelBox, onRainLabelDrawn, canvas)
    }

    private fun drawWeatherIcon(canvas: Canvas, context: Context, day: DayData, centerX: Float, iconY: Float, iconSize: Int) {
        if (day.iconRes == null) return
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, day.iconRes)?.mutate() ?: return
        
        val iconX = centerX - iconSize / 2f
        drawable.setBounds(iconX.toInt(), iconY.toInt(), (iconX + iconSize).toInt(), (iconY + iconSize).toInt())
        
        if (!day.isRainy && !day.isMixed) {
            val tint = if (day.isSunny) COLOR_SUNNY else COLOR_LABEL_GRAY
            drawable.setTint(tint)
        }
        drawable.draw(canvas)
    }

    /**
     * Draws the frosted-glass focal panel behind the today column. Spans the three bars horizontally
     * (centerX ± tripleBarOffset, plus a little padding, clamped inside the column) and the bar/icon/
     * low-label area vertically (graphTop → just above the day label). Purely decorative — it changes
     * no geometry, so the label-placement paths are untouched.
     */
    private fun drawTodayHighlightPanel(canvas: Canvas, centerX: Float, layout: LayoutInfo) {
        val density = layout.density
        val tripleBarWidth = todayTripleBarStrokeWidthPx(density, layout.scaleFactor, layout.bitmapScale)
        val bounds = TodayColumnHighlight.panelBounds(
            centerXPx = centerX,
            tripleBarOffsetPx = layout.tripleBarOffset,
            flankBarWidthPx = tripleBarWidth,
            dayWidthPx = layout.dayWidth,
            graphTopPx = layout.graphTop,
            canvasHeightPx = layout.heightPx.toFloat(),
            dayLabelBandPx = layout.dayLabelHeight,
            horizontalPaddingPx = TodayColumnHighlight.PANEL_HORIZONTAL_PADDING_DP.dp(density),
            topMarginPx = TodayColumnHighlight.PANEL_TOP_MARGIN_DP.dp(density),
        )
        val radius = TodayColumnHighlight.PANEL_CORNER_RADIUS_DP.dp(density)
        val rect = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = TodayColumnHighlight.PANEL_FILL_ARGB
        }
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }

    private fun drawDayBars(
        canvas: Canvas,
        context: Context,
        day: DayData,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        onBarDrawn: ((BarDrawnDebug) -> Unit)?
    ) {
        val highY = day.solidLineHigh?.let { layout.tempToY(it) }
        val lowY = day.solidLineLow?.let { layout.tempToY(it) }

        if (day.isToday) {
            drawTodayTripleBar(canvas, context, day, centerX, highY, lowY, layout, paints, onBarDrawn)
        } else if (highY != null || lowY != null) {
            val endpoints = resolveBarEndpoints(highY, lowY, layout.minBarHeightPx)
            if (endpoints == null) {
                Log.w(TAG, "drawDayBars: resolveBarEndpoints returned null despite non-null guard date=${day.date} highY=$highY lowY=$lowY")
            } else {
                val (hY, effectiveLowY) = endpoints
                val condColor = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
                val paint = when {
                    day.isPast -> paints.historyBarPaint
                    day.isSourceGapFallback -> paints.gapFallbackBarPaint
                    else -> paints.barForColor(condColor)
                }

                val usesAdaptiveSegments = !day.isPast && day.iconRes != null && shouldUseAdaptiveSegments(day)
                debug {
                    "Bar color decision: date=${day.date}" +
                        " isPast=${day.isPast} isSunny=${day.isSunny} isRainy=${day.isRainy}" +
                        " isMixed=${day.isMixed} iconRes=${day.iconRes}" +
                        " color=${String.format("#%08X", paint.color)} gradient=$usesAdaptiveSegments cloudRatioOverride=${day.cloudCoverRatioOverride}"
                }
                drawWeatherAdaptiveBar(
                    canvas = canvas,
                    centerX = centerX,
                    topY = hY,
                    bottomY = effectiveLowY,
                    paint = paint,
                    day = day,
                    logPrefix = "primary",
                    allowAdaptiveSegments = !day.isPast,
                )
                onBarDrawn?.invoke(BarDrawnDebug(day.date, if (day.isPast) "HISTORY" else "FUTURE", hY, effectiveLowY, centerX, paint.color))
            }
        }

        if (!day.isToday && day.dashedLineHigh != null && day.dashedLineLow != null) {
            val fHighY = layout.tempToY(day.dashedLineHigh)
            val fLowY = layout.tempToY(day.dashedLineLow)
            val effectiveFLowY = clampMinBarHeight(fHighY, fLowY, layout.minBarHeightPx)
            
            val forecastX = centerX + layout.forecastBarOffset
            val condColor = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
            val overlayPaint = if (day.isClimateNormal) {
                paints.climateOverlayForColor(condColor)
            } else {
                paints.forecastForColor(condColor)
            }
            val overlayGradient = day.iconRes != null && shouldUseAdaptiveSegments(day)
            debug {
                "Overlay color decision: date=${day.date}" +
                    " isSunny=${day.isSunny} isRainy=${day.isRainy}" +
                    " isMixed=${day.isMixed} iconRes=${day.iconRes}" +
                    " color=${String.format("#%08X", condColor)} gradient=$overlayGradient cloudRatioOverride=${day.cloudCoverRatioOverride}" +
                    " isClimateNormal=${day.isClimateNormal}"
            }
            drawWeatherAdaptiveBar(
                canvas = canvas,
                centerX = forecastX,
                topY = fHighY,
                bottomY = effectiveFLowY,
                paint = overlayPaint,
                day = day,
                logPrefix = "overlay",
            )
            onBarDrawn?.invoke(BarDrawnDebug(day.date, "FORECAST_OVERLAY", fHighY, effectiveFLowY, forecastX, overlayPaint.color))
        }

        if (day.solidLineHigh != null) {
            val basePaint = when {
                day.isToday -> paints.todayTempTextPaint
                day.isPast -> paints.pastTempTextPaint
                else -> paints.tempTextPaint
            }
            // History — and today once its high is settled (past the 5pm cutoff) — can label BOTH the
            // actual (thermostat color) and the forecast (forecast-bar color) when the forecast missed
            // by enough AND there's vertical room (DualHighLabel). The plan (shared with the rain-label
            // resolvers) decides that and carries both baselines. Today's "actual" is the observed peak
            // (effectiveHigh).
            //
            // Horizontal anchor: today centers BOTH high labels on the column (centerX), matching its
            // single-high label. Today's forecast bar sits a full triple-bar step (tripleBarOffset) to
            // the right of the observed thermostat, so labeling over that bar would stagger the two
            // stacked numbers and read as misaligned; color already distinguishes forecast (gold) from
            // actual (thermostat), so the bar association isn't needed. A past day's forecast overlay
            // sits right beside its bar (small forecastBarOffset), so labeling over it stays aligned.
            val plan = resolveHighLabelPlan(day, layout, paints)
            if (plan != null && plan.showBoth && plan.forecastHigh != null && plan.forecastBaseline != null) {
                // Dual highs render for past days and settled-today, so both labels are outlined.
                val condColor = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
                val forecastLabelX = if (day.isToday) centerX else centerX + layout.forecastBarOffset
                drawTempLabel(canvas, formatTempLabel(plan.actualHigh, useCelsius = layout.useCelsius), centerX, plan.actualBaseline, basePaint,
                    extraScale = DualHighLabel.TWO_LABEL_FONT_SCALE, colorOverride = COLOR_OBSERVED_RED, drawOutline = true,
                    maxWidthPx = layout.tempLabelMaxWidthPx)
                // The forecast shrinks (DUAL_FORECAST_FONT_SCALE) only when the plan found the two
                // labels colliding at full size; otherwise it draws at the normal dual-label size.
                drawTempLabel(canvas, formatTempLabel(plan.forecastHigh, useCelsius = layout.useCelsius), forecastLabelX, plan.forecastBaseline,
                    basePaint, extraScale = DualHighLabel.TWO_LABEL_FONT_SCALE * plan.forecastFontScale,
                    colorOverride = condColor, drawOutline = true,
                    maxWidthPx = layout.tempLabelMaxWidthPx)
            } else {
                val displayHigh = day.effectiveHigh() ?: day.solidLineHigh
                val highLabel = formatTempLabel(displayHigh, useCelsius = layout.useCelsius)
                // The single label sits at the headline (effective) high — the plan's anchorBaseline
                // when no dual label is shown.
                val labelBaseline = plan?.anchorBaseline ?: run {
                    Log.w(TAG, "drawDayBars: high label plan null despite non-null solidLineHigh date=${day.date}")
                    val labelOffset = (HIGH_LABEL_OFFSET_DP * layout.bitmapScale.coerceAtMost(1f)).dp(layout.density)
                    (highY ?: layout.graphTop) - labelOffset
                }
                // Once today's high is settled (past the 5pm cutoff) the single number tracks the
                // observed actual — recolor it the thermostat (observed) color so it reads as a real
                // reading, not a forecast. Mirrors effectiveHigh()'s own actual-vs-forecast branch.
                val highColorOverride = if (plan?.todayHighSettled == true) COLOR_OBSERVED_RED else null
                // History and today get the thin outline (today's headline sits over the triple bars);
                // future days stay plain.
                drawTempLabel(canvas, highLabel, centerX, labelBaseline, basePaint,
                    colorOverride = highColorOverride, drawOutline = day.isPast || day.isToday,
                    maxWidthPx = layout.tempLabelMaxWidthPx)
            }
        }
    }

    /**
     * Draws a centered temp label. [extraScale] shrinks the font (e.g. 0.92 for past-day dual highs);
     * wide 3+ digit temps (100°, 97.7°) shrink a further 5%. [colorOverride] recolors a copy of [base]
     * (thermostat / forecast color). The common full-size/no-recolor case reuses [base] without allocating.
     * When [drawOutline] is true (history labels), a thin black stroke is drawn behind the fill to keep
     * the label legible over a same-colored bar.
     */
    /**
     * Reduces [currentScale] just enough that a label measuring [measuredWidthAtScale] px fits
     * within [maxWidthPx], never going below [minScale]. Returns [currentScale] unchanged when the
     * label already fits (or no budget is given). Pure so the shrink-to-fit math is unit-testable.
     */
    internal fun fitScaleForWidth(
        measuredWidthAtScale: Float,
        maxWidthPx: Float,
        currentScale: Float,
        minScale: Float = MIN_TEMP_LABEL_FIT_SCALE,
    ): Float {
        if (maxWidthPx <= 0f || measuredWidthAtScale <= maxWidthPx) return currentScale
        return (currentScale * (maxWidthPx / measuredWidthAtScale)).coerceAtLeast(currentScale * minScale)
    }

    /**
     * The font scale a temp label is actually drawn at: the fixed wide-label step times any caller
     * [extraScale], then shrink-to-fit against the column width. Extracted from [drawTempLabel] so the
     * rain label can anchor to the high label as it is *rendered* (a wide "75.6°" squeezed into a
     * narrow column draws much smaller than its full-size metrics imply).
     */
    internal fun tempLabelDrawScale(
        base: Paint,
        text: String,
        extraScale: Float,
        maxWidthPx: Float,
    ): Float {
        val baseScale = extraScale * (if (DualHighLabel.isWideLabel(text)) DualHighLabel.WIDE_LABEL_FONT_SCALE else 1f)
        return fitScaleForWidth(measureTextWidth(base, text) * baseScale, maxWidthPx, baseScale)
    }

    private fun drawTempLabel(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        base: Paint,
        extraScale: Float = 1f,
        colorOverride: Int? = null,
        drawOutline: Boolean = false,
        maxWidthPx: Float = Float.MAX_VALUE,
    ) {
        // Start from the existing fixed wide-label step, then shrink-to-fit against the column width
        // so 3+ digit / decimal temps (e.g. "77.7°") never spill their degree symbol into the next
        // column on dense layouts. Keeps the ° and the tenths; only the font size adapts.
        val scale = tempLabelDrawScale(base, text, extraScale, maxWidthPx)
        val paint = if (colorOverride == null && scale == 1f) base else Paint(base).apply {
            if (colorOverride != null) color = colorOverride
            textSize = base.textSize * scale
        }
        if (drawOutline) {
            val outlinePaint = Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = paint.textSize * LABEL_OUTLINE_STROKE_FRACTION
                color = 0xFF000000.toInt()
                clearShadowLayer()
            }
            canvas.drawText(text, centerX, baselineY, outlinePaint)
        }
        canvas.drawText(text, centerX, baselineY, paint)
    }

    private fun drawTodayTripleBar(
        canvas: Canvas,
        context: Context,
        day: DayData,
        centerX: Float,
        highY: Float?,
        lowY: Float?,
        layout: LayoutInfo,
        paints: PaintSet,
        onBarDrawn: ((BarDrawnDebug) -> Unit)?
    ) {
        val (obsHighY, effectiveObsLowY) = resolveBarEndpoints(highY, lowY, layout.minBarHeightPx) ?: return

        day.snapshotHigh?.let { sHigh ->
            day.snapshotLow?.let { sLow ->
                val sHighY = layout.tempToY(sHigh)
                val sLowY = layout.tempToY(sLow)
                val effectiveSLowY = clampMinBarHeight(sHighY, sLowY, layout.minBarHeightPx)
                val snapshotX = centerX - layout.tripleBarOffset

                val sIsSunny = day.snapshotIconRes?.let { WeatherIconMapper.isSunny(it) } ?: false
                val sIsRainy = day.snapshotIconRes?.let { WeatherIconMapper.isPrecipitation(it) } ?: false
                val sIsMixed = day.snapshotIconRes?.let { WeatherIconMapper.isMixed(it) } ?: false

                val sCondColor = com.weatherwidget.shared.util.WeatherColors.snapshotBarOverrideArgb(sIsRainy)
                    ?: paints.todaySnapshotYellowPaint.color
                val sPaint = paints.todayForecastForColor(sCondColor)

                // The snapshot bar (yesterday's forecast for today) describes the same day as
                // the live forecast bar, so it carries today's resolved cloud-cover ratio.
                val snapshotDay = day.copy(
                    iconRes = day.snapshotIconRes,
                    isSunny = sIsSunny,
                    isRainy = sIsRainy,
                    isMixed = sIsMixed,
                    cloudCoverRatioOverride = day.cloudCoverRatioOverride
                        ?: day.snapshotIconRes?.let { WeatherConditionColors.cloudRatio(it) },
                )

                drawWeatherAdaptiveBar(
                    canvas = canvas,
                    centerX = snapshotX,
                    topY = sHighY,
                    bottomY = effectiveSLowY,
                    paint = sPaint,
                    day = snapshotDay,
                    logPrefix = "today_snapshot",
                    allowAdaptiveSegments = true
                )
                onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY_SNAPSHOT", sHighY, effectiveSLowY, snapshotX, sPaint.color, adaptiveSegments = true))
            }
        }

        val fHigh = day.dashedLineHigh ?: day.solidLineHigh ?: return
        val fLow = day.dashedLineLow ?: day.solidLineLow ?: return
        val fHighY = layout.tempToY(fHigh)
        val fLowY = layout.tempToY(fLow)
        val effectiveFLowY = clampMinBarHeight(fHighY, fLowY, layout.minBarHeightPx)

        val condColor = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
        val forecastPaint = paints.todayForecastForColor(condColor)
        // Today's live forecast sits RIGHT of the thermostat.
        val todayForecastX = centerX + layout.tripleBarOffset
        drawWeatherAdaptiveBar(
            canvas = canvas,
            centerX = todayForecastX,
            topY = fHighY,
            bottomY = effectiveFLowY,
            paint = forecastPaint,
            day = day,
            logPrefix = "today_forecast",
        )
        onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY_FORECAST", fHighY, effectiveFLowY, todayForecastX, forecastPaint.color))

        canvas.drawLine(centerX, obsHighY, centerX, effectiveObsLowY, paints.todayObservedRedPaint)
        canvas.drawCircle(centerX, effectiveObsLowY + (layout.bulbRadius * BULB_VERTICAL_CENTER_FRACTION), layout.bulbRadius, paints.todayObservedRedBulbPaint)
        
        day.ghostLineHigh?.let { trueHigh ->
            val obsHighTemp = day.solidLineHigh ?: 0f
            if (trueHigh > obsHighTemp) {
                val ghostHighY = layout.tempToY(trueHigh)
                canvas.drawLine(centerX, ghostHighY, centerX, obsHighY, paints.todayObservedGhostPaint)
                onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY_GHOST", ghostHighY, obsHighY, centerX, paints.todayObservedGhostPaint.color))
            }
        }
        
        onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY", obsHighY, effectiveObsLowY, centerX, paints.todayObservedRedPaint.color))
    }

    // ── Baseline resolvers (shared by bars and rain labels) ────────────────

    /**
     * The high label(s) a column draws, and the anchor the daily rain % rides above. A past day (or
     * settled-today) can print BOTH the observed actual high and the forecast high (DualHighLabel);
     * when it does, the two labels sit at different heights and the rain % must clear the TOPMOST
     * (warmer) of the two — anchoring only to the actual wedges the % between them (see the "yesterday
     * 15%" report). Single-label days anchor to the headline effective high. Shared by [drawDayBars]
     * and the rain-label resolvers so the dual-high decision is made in exactly one place.
     */
    internal data class HighLabelPlan(
        val showBoth: Boolean,
        val todayHighSettled: Boolean,
        /** Observed-actual (or headline) high, drawn at column center. */
        val actualHigh: Float,
        /** Forecast high, drawn offset to the side — only when [showBoth]. */
        val forecastHigh: Float?,
        val actualBaseline: Float,
        val forecastBaseline: Float?,
        /**
         * Extra scale for the forecast label: [DualHighLabel.DUAL_FORECAST_FONT_SCALE] only when the
         * two labels would collide at full size, else 1f (the shrink is collision-only by request).
         */
        val forecastFontScale: Float,
        /** Warmer of the drawn highs (== [actualHigh] when single) — the value the rain % sits above. */
        val anchorHigh: Float,
        /** Topmost drawn high-label baseline — the rain %'s vertical anchor. */
        val anchorBaseline: Float,
    )

    internal fun resolveHighLabelPlan(
        day: DayData,
        layout: LayoutInfo,
        paints: PaintSet,
    ): HighLabelPlan? {
        day.solidLineHigh ?: return null
        val effective = day.effectiveHigh() ?: return null
        val labelOffset = (HIGH_LABEL_OFFSET_DP * layout.bitmapScale.coerceAtMost(1f)).dp(layout.density)
        val basePaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        val todayHighSettled = com.weatherwidget.shared.util.DailyDayValueResolver.isHighTrackingActual(
            isToday = day.isToday,
            solidHigh = day.solidLineHigh,
            ghostHigh = day.ghostLineHigh,
            nowHour = day.nowHour,
        )
        // actualHigh == effective in every case (past: solid == effective; today: actual IS effective),
        // so the actual label baseline is always the headline (effective) baseline.
        val actualHigh = effective
        val effectiveBaseline = layout.tempToY(effective) - labelOffset
        val forecastHigh = if (day.isPast || todayHighSettled) day.dashedLineHigh else null
        // Two labels this close together (the gap IS the forecast miss, so a 2° miss is only a couple
        // of label-heights on a compressed graph) collide at their natural above-the-bar positions.
        // Move each away from the other before the room test, so the test measures what is drawn.
        // Digits have no descender, so a label's bottom edge is effectively its baseline.
        val offsets = forecastHigh?.let {
            DualHighLabel.bottomOffsetsDp(actualHigh, it, normalGapDp = HIGH_LABEL_OFFSET_DP)
        }
        val offsetScale = layout.bitmapScale.coerceAtMost(1f)
        fun offsetPx(dp: Float) = (dp * offsetScale).dp(layout.density)
        val nudgedActualBaseline = offsets?.let { layout.tempToY(effective) + offsetPx(it.actualDp) }
            ?: effectiveBaseline
        val forecastBaseline = forecastHigh?.let { layout.tempToY(it) + offsetPx(offsets!!.forecastDp) }
        // Label height for the room test; fontMetrics is null under stubbed-Paint unit tests, so fall
        // back to textSize there. Only needed when a forecast label is in play. This is the FULL
        // (unshrunk) dual-label size — both the room test and the collision test below measure the
        // labels at the size they'd draw before any collision shrink.
        val twoLabelHeight = (basePaint.fontMetrics?.let { it.descent - it.ascent } ?: basePaint.textSize) *
            DualHighLabel.TWO_LABEL_FONT_SCALE
        val showBoth = forecastHigh != null && forecastBaseline != null &&
            DualHighLabel.showBoth(actualHigh, forecastHigh, nudgedActualBaseline, forecastBaseline, twoLabelHeight)
        // Shrink the forecast label only when it sits BELOW the actual and the two boxes would
        // collide at full size; a well-separated (or upper) forecast keeps the normal dual-label
        // font. Baselines are the labels' bottom edges (digits have no descenders) and don't move
        // with font size (bottom-pinned), so the full-size test needs no re-measure.
        val forecastFontScale = if (showBoth && forecastBaseline != null)
            DualHighLabel.forecastFontScale(nudgedActualBaseline, forecastBaseline, twoLabelHeight)
        else 1f
        // The nudge only applies when two labels actually render; a lone high label keeps its true
        // above-the-bar position.
        val actualBaseline = if (showBoth) nudgedActualBaseline else effectiveBaseline
        val anchorHigh = if (showBoth && forecastHigh != null) maxOf(actualHigh, forecastHigh) else effective
        val anchorBaseline = if (showBoth && forecastBaseline != null) minOf(actualBaseline, forecastBaseline) else actualBaseline
        return HighLabelPlan(
            showBoth = showBoth,
            todayHighSettled = todayHighSettled,
            actualHigh = actualHigh,
            forecastHigh = forecastHigh,
            actualBaseline = actualBaseline,
            forecastBaseline = forecastBaseline,
            forecastFontScale = forecastFontScale,
            anchorHigh = anchorHigh,
            anchorBaseline = anchorBaseline,
        )
    }

    internal fun resolveHighLabelBaseline(
        day: DayData,
        layout: LayoutInfo,
        paints: PaintSet,
    ): Float? = resolveHighLabelPlan(day, layout, paints)?.anchorBaseline

    /**
     * The fit-to-width font scale the anchored high-temp label is actually drawn at. The rain label
     * multiplies the full-size temp metrics by this so it anchors to that label's *rendered* top, not
     * its full-size top — otherwise a shrunk wide temp (e.g. dual-label "75.6°" in a narrow column)
     * leaves a large gap above the high. Uses the topmost (anchor) high's text so a dual-label day
     * measures the label the rain actually sits above. The 2% dual-label shrink is immaterial next to
     * the fit factor, so we pass extraScale = 1.
     */
    internal fun resolveHighLabelDrawScale(
        day: DayData,
        layout: LayoutInfo,
        paints: PaintSet,
    ): Float {
        val anchorHigh = resolveHighLabelPlan(day, layout, paints)?.anchorHigh
            ?: day.effectiveHigh() ?: day.solidLineHigh ?: return 1f
        val highText = formatTempLabel(anchorHigh, useCelsius = layout.useCelsius)
        val tempPaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        return tempLabelDrawScale(tempPaint, highText, extraScale = 1f, maxWidthPx = layout.tempLabelMaxWidthPx)
    }

    internal fun resolveLowLabelBaseline(
        day: DayData,
        layout: LayoutInfo,
    ): Float? {
        val lowTemp = resolveIconAnchorLow(day) ?: return null
        val lowY = layout.tempToY(lowTemp)
        val iconY = lowY + (ICON_BELOW_BAR_SPACING_DP).dp(layout.density)
        return iconY + layout.iconSize + layout.tempLabelHeight + (TEMP_LABEL_SPACING_DP).dp(layout.density)
    }

    // ── Utility ───────────────────────────────────────────────────────────

    /** The printed low VALUE (gated effective low on today; observed/forecast otherwise). */
    internal fun resolveBottomStackLow(day: DayData): Float? = day.bottomStackLow ?: day.solidLineLow

    /** The icon/low-label POSITION anchor: bottom of the lowest drawn bar for the column. */
    internal fun resolveIconAnchorLow(day: DayData): Float? =
        com.weatherwidget.shared.util.DailyDayValueResolver.iconAnchorLow(
            solidLow = day.solidLineLow,
            forecastLow = day.dashedLineLow,
            snapshotLow = day.snapshotLow,
        ) ?: day.bottomStackLow ?: day.solidLineLow

    private fun Float.dp(density: Float): Float = this * density
    private fun Int.dp(density: Float): Float = this.toFloat() * density

    private fun measureTextWidth(paint: Paint, text: String): Float {
        val measured = paint.measureText(text)
        if (measured > 0f) return measured
        return text.length * paint.textSize * 0.55f
    }

    @VisibleForTesting
    internal fun resolveDayLabelLayout(
        labels: List<DayLabelInput>,
        baseTextSizePx: Float,
        maxTextWidthPx: Float,
        minScale: Float = MIN_DYNAMIC_DAY_LABEL_SCALE,
    ): DayLabelLayoutResult {
        if (labels.isEmpty() || baseTextSizePx <= 0f) {
            return DayLabelLayoutResult(
                textSizePx = baseTextSizePx,
                textByDate = labels.associate { it.date to it.label },
                scale = 1f,
                shortenedLabels = false,
            )
        }

        val originalTextByDate = labels.associate { it.date to it.label }
        val originalScale = fittingScale(labels, originalTextByDate, baseTextSizePx, maxTextWidthPx)
        if (originalScale >= minScale) {
            return DayLabelLayoutResult(
                textSizePx = baseTextSizePx * originalScale,
                textByDate = originalTextByDate,
                scale = originalScale,
                shortenedLabels = false,
            )
        }

        val shortenedTextByDate = labels.associate { input ->
            val label = if (input.isToday) {
                input.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            } else {
                input.label
            }
            input.date to label
        }
        val shortenedScale = fittingScale(labels, shortenedTextByDate, baseTextSizePx, maxTextWidthPx)
            .coerceAtLeast(minScale)

        return DayLabelLayoutResult(
            textSizePx = baseTextSizePx * shortenedScale,
            textByDate = shortenedTextByDate,
            scale = shortenedScale,
            shortenedLabels = shortenedTextByDate != originalTextByDate,
        )
    }

    private fun fittingScale(
        labels: List<DayLabelInput>,
        textByDate: Map<LocalDate, String>,
        baseTextSizePx: Float,
        maxTextWidthPx: Float,
    ): Float {
        val regularPaint = dayLabelMeasurePaint(baseTextSizePx, bold = false)
        val todayPaint = dayLabelMeasurePaint(baseTextSizePx, bold = true)
        val widest = labels.maxOfOrNull { input ->
            val text = textByDate[input.date].orEmpty()
            val paint = if (input.isToday) todayPaint else regularPaint
            measureTextWidth(paint, text)
        } ?: 0f
        if (widest <= 0f || widest <= maxTextWidthPx) return 1f
        return (maxTextWidthPx / widest).coerceAtMost(1f)
    }

    private fun dayLabelMeasurePaint(textSizePx: Float, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            textAlign = Paint.Align.CENTER
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    internal fun computeDayLabelWidthScale(dayWidthDp: Float): Float {
        return (dayWidthDp / BASE_DAY_WIDTH_DP).coerceIn(MIN_DAY_LABEL_WIDTH_SCALE, MAX_DAY_LABEL_WIDTH_SCALE)
    }

    @VisibleForTesting
    internal fun dailyForecastTempLabelSizePx(
        density: Float,
        heightScaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float {
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        return (TEMP_LABEL_TEXT_SIZE_DP * heightScaleFactor * labelScale).dp(density)
    }

    @VisibleForTesting
    internal fun dailyBarStrokeWidthPx(
        density: Float,
        scaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float {
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        return (FORECAST_BAR_WIDTH_DP * scaleFactor * labelScale).dp(density)
    }

    @VisibleForTesting
    internal fun todayTripleBarStrokeWidthPx(
        density: Float,
        scaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float {
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        return (TODAY_TRIPLE_BAR_WIDTH_DP * scaleFactor * labelScale).dp(density)
    }

    private fun clampMinBarHeight(highY: Float, lowY: Float, minBarHeight: Float): Float =
        if (abs(highY - lowY) < minBarHeight) highY + minBarHeight else lowY

    private fun resolveBarEndpoints(highY: Float?, lowY: Float?, minBarHeight: Float): Pair<Float, Float>? {
        val hY = highY ?: (lowY?.let { it - minBarHeight }) ?: return null
        val lY = lowY ?: (hY + minBarHeight)
        return hY to clampMinBarHeight(hY, lY, minBarHeight)
    }

    // Show the tenth (e.g. "77.5°") for any non-integer value, suppressing the ".0" case
    // so whole-degree sources (NWS integer forecasts) stay clean. forceInteger overrides
    // this for the low label when a night-rain label sits alongside it (collision avoidance).
    internal fun formatTempLabel(value: Float, forceInteger: Boolean = false, useCelsius: Boolean): String {
        val displayVal = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(value) else value
        if (forceInteger) return "${displayVal.roundToInt()}°"
        return com.weatherwidget.util.TempUtils.formatTemp(value, useCelsius) ?: ""
    }
}
