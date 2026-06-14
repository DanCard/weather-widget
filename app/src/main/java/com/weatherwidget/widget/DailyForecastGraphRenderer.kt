package com.weatherwidget.widget

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.BuildConfig
import com.weatherwidget.widget.handlers.HeaderConstants
import com.weatherwidget.shared.graph.DualHighLabel
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
    private const val MIN_BAR_HEIGHT_DP = 1.0f

    private val COLOR_FORECAST = 0xFF5AC8FA.toInt()
    private val COLOR_TODAY_HIGHLIGHT = 0xFFFFFF00.toInt()
    private val COLOR_OBSERVED_RED = 0xFFFF3366.toInt()
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
    private const val RAIN_HIGH_TEMP_GAP_DP = -2f
    private const val HISTORY_BAR_WIDTH_SCALE = 0.7f
    private const val FORECAST_OVERLAY_WIDTH_SCALE = 0.7f
    private const val CLIMATE_OVERLAY_WIDTH_SCALE = 0.8f
    private const val FORECAST_BAR_OFFSET_SCALE = 0.7f
    private const val PAST_TEMP_SCALE = 0.9f
    // Fuller dark halo (was 1.5) so colored history labels stay legible over a same-colored bar.
    private const val LABEL_SHADOW_RADIUS_DP = 2.5f
    private const val LABEL_SHADOW_DY_DP = 1.0f
    private const val HEADER_RAIN_OVERLAP_TOLERANCE_DP = 4f

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
        val date: LocalDate,
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
        val cloudCoverRatioOverride: Float? = null,
        val daysFromToday: Int = 0,
    )

    private fun DayData.effectiveHigh(): Float? {
        if (!isToday) return solidLineHigh
        return listOfNotNull(solidLineHigh, dashedLineHigh, ghostLineHigh).maxOrNull()
    }

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
        val layout = computeLayout(context, days, widthPx, heightPx, columns, bitmapScale, job)
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
            horizontalPadding = horizontalPadding,
            tripleBarOffset = (8f * scaleFactor * labelScale).dp(density),
            forecastBarOffset = barWidth * FORECAST_BAR_OFFSET_SCALE,
            iconSize = iconSize,
            dayLabelHeight = dayLabelHeight,
            tempLabelHeight = tempLabelHeight,
            bulbRadius = tripleBarWidth * BULB_RADIUS_SCALE,
            bitmapScale = bitmapScale,
            minBarHeightPx = (MIN_BAR_HEIGHT_DP).dp(density),
            dayLabelTextByDate = dayLabelLayout.textByDate,
            density = density,
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
            tempTextPaint = createTextPaint(COLOR_WHITE, layout.tempLabelHeight, shadowRadius = shadowRadius, shadowDy = shadowDy),
            pastTempTextPaint = createTextPaint(COLOR_WHITE, layout.tempLabelHeight * PAST_TEMP_SCALE, shadowRadius = shadowRadius, shadowDy = shadowDy),
            todayTempTextPaint = createTextPaint(COLOR_TODAY_TEXT, layout.tempLabelHeight, true, shadowRadius = shadowRadius, shadowDy = shadowDy),
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

        val lowTemp = resolveBottomStackLow(day)
        val lowY = lowTemp?.let { layout.tempToY(it) }

        if (lowY != null) {
            val iconY = lowY + (ICON_BELOW_BAR_SPACING_DP).dp(layout.density)
            drawWeatherIcon(canvas, context, day, centerX, iconY, layout.iconSize)

            val lowTempY = iconY + layout.iconSize + layout.tempLabelHeight + (TEMP_LABEL_SPACING_DP).dp(layout.density)
            
            val isLowest = lowTemp <= layout.minTemp + 0.01f
            val hasNightRain = day.rainData.nightRainLabelText != null
            val forceInteger = isLowest && hasNightRain
            val isActualData = (day.isToday || day.isPast) && !forceInteger
            val lowLabelText = formatTempLabel(lowTemp, isActualData)

            val tempPaint = when {
                day.isToday -> paints.todayTempTextPaint
                day.isPast -> paints.pastTempTextPaint
                else -> paints.tempTextPaint
            }
            drawTempLabel(canvas, lowLabelText, centerX, lowTempY, tempPaint)

        }

        DailyForecastRainLabelRenderer.drawDailyRainLabel(day, centerX, layout, paints, onRainLabelDrawn, canvas)
        DailyForecastRainLabelRenderer.drawNightRainLabel(day, rightNeighbor, centerX, layout, paints, onRainLabelDrawn, canvas)
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
            val labelOffset = (HIGH_LABEL_OFFSET_DP * layout.bitmapScale.coerceAtMost(1f)).dp(layout.density)
            val basePaint = when {
                day.isToday -> paints.todayTempTextPaint
                day.isPast -> paints.pastTempTextPaint
                else -> paints.tempTextPaint
            }
            val y = highY ?: lowY?.let { it - layout.minBarHeightPx } ?: run {
                Log.w(TAG, "drawDayBars: both highY and lowY null for high label date=${day.date}; falling back to graphTop")
                layout.graphTop
            }

            // Past days: when the forecast high missed the actual by enough AND there's room, label
            // BOTH — actual in the thermostat color, forecast in the forecast-bar color (DualHighLabel).
            val actualHigh = day.solidLineHigh
            val forecastHigh = if (day.isPast) day.dashedLineHigh else null
            val actualBaseline = y - labelOffset
            val forecastBaseline = forecastHigh?.let { layout.tempToY(it) - labelOffset }
            // Label height for the room test; fontMetrics is null under stubbed-Paint unit tests,
            // so fall back to textSize there. Only needed when a forecast label is in play.
            val twoLabelHeight = (basePaint.fontMetrics?.let { it.descent - it.ascent } ?: basePaint.textSize) *
                DualHighLabel.TWO_LABEL_FONT_SCALE
            val showBoth = forecastHigh != null && forecastBaseline != null &&
                DualHighLabel.showBoth(actualHigh, forecastHigh, actualBaseline, forecastBaseline, twoLabelHeight)

            if (showBoth) {
                val condColor = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
                drawTempLabel(canvas, formatTempLabel(actualHigh, true), centerX, actualBaseline, basePaint,
                    extraScale = DualHighLabel.TWO_LABEL_FONT_SCALE, colorOverride = COLOR_OBSERVED_RED)
                drawTempLabel(canvas, formatTempLabel(forecastHigh, true), centerX + layout.forecastBarOffset, forecastBaseline,
                    basePaint, extraScale = DualHighLabel.TWO_LABEL_FONT_SCALE, colorOverride = condColor)
            } else {
                val displayHigh = day.effectiveHigh() ?: day.solidLineHigh
                val highLabel = formatTempLabel(displayHigh, day.isToday || day.isPast)
                val labelY = if (day.isToday) layout.tempToY(day.effectiveHigh() ?: day.solidLineHigh) else y
                drawTempLabel(canvas, highLabel, centerX, labelY - labelOffset, basePaint)
            }
        }
    }

    /**
     * Draws a centered temp label. [extraScale] shrinks the font (e.g. 0.92 for past-day dual highs);
     * wide 3+ digit temps (100°, 97.7°) shrink a further 5%. [colorOverride] recolors a copy of [base]
     * (thermostat / forecast color). The common full-size/no-recolor case reuses [base] without allocating.
     */
    private fun drawTempLabel(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        base: Paint,
        extraScale: Float = 1f,
        colorOverride: Int? = null,
    ) {
        val scale = extraScale * (if (DualHighLabel.isWideLabel(text)) DualHighLabel.WIDE_LABEL_FONT_SCALE else 1f)
        val paint = if (colorOverride == null && scale == 1f) base else Paint(base).apply {
            if (colorOverride != null) color = colorOverride
            textSize = base.textSize * scale
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

                var sCondColor = WeatherConditionColors.forecastColor(sIsSunny, sIsRainy, sIsMixed, isNight = false)
                // If sunny or no icon info available, stick to the bright yellow high-contrast snapshot color.
                if (sCondColor == WeatherConditionColors.FORECAST_SUNNY || day.snapshotIconRes == null) {
                    sCondColor = paints.todaySnapshotYellowPaint.color
                }
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

    internal fun resolveHighLabelBaseline(
        day: DayData,
        layout: LayoutInfo,
    ): Float? {
        day.solidLineHigh ?: return null
        val absoluteHigh = day.effectiveHigh() ?: return null
        val labelY = layout.tempToY(absoluteHigh)
        return labelY - (HIGH_LABEL_OFFSET_DP * layout.bitmapScale.coerceAtMost(1f)).dp(layout.density)
    }

    internal fun resolveLowLabelBaseline(
        day: DayData,
        layout: LayoutInfo,
    ): Float? {
        val lowTemp = resolveBottomStackLow(day) ?: return null
        val lowY = layout.tempToY(lowTemp)
        val iconY = lowY + (ICON_BELOW_BAR_SPACING_DP).dp(layout.density)
        return iconY + layout.iconSize + layout.tempLabelHeight + (TEMP_LABEL_SPACING_DP).dp(layout.density)
    }

    // ── Utility ───────────────────────────────────────────────────────────

    internal fun resolveBottomStackLow(day: DayData): Float? = day.bottomStackLow ?: day.solidLineLow

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

    internal fun formatTempLabel(actual: Float, isActualData: Boolean): String {
        if (!isActualData) return "${actual.roundToInt()}°"
        return com.weatherwidget.util.TempUtils.formatTemp(actual) ?: ""
    }
}
