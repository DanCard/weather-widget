package com.weatherwidget.widget

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import androidx.annotation.VisibleForTesting
import com.weatherwidget.widget.handlers.HeaderConstants
import com.weatherwidget.util.HeaderPrecipCalculator
import com.weatherwidget.util.WeatherConditionColors
import kotlin.math.abs
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

object DailyForecastGraphRenderer {
    private const val TAG = "DailyGraphRenderer"

    private const val DAY_LABEL_SIZE_MULTIPLIER = 1.4f
    private const val DAY_LABEL_TEXT_SCALE = 1.5f
    private const val BASE_DAY_WIDTH_DP = 70f
    private const val MIN_DAY_LABEL_WIDTH_SCALE = 0.96f
    private const val MAX_DAY_LABEL_WIDTH_SCALE = 1.04f
    private const val MIN_DYNAMIC_DAY_LABEL_SCALE = 0.72f
    private const val DAY_LABEL_HORIZONTAL_GAP_DP = 4f
    private const val MIN_BAR_HEIGHT_DP = 1.0f

    private const val COLOR_FORECAST = "#5AC8FA"
    private const val COLOR_TODAY_HIGHLIGHT = "#FFFF00"
    private const val COLOR_OBSERVED_RED = "#FF3366"
    private const val COLOR_LABEL_GRAY = "#AAAAAA"
    private const val COLOR_TODAY_TEXT = "#FFEACC"
    private const val COLOR_WHITE = "#FFFFFF"
    private const val COLOR_GAP_FALLBACK = "#34C759"
    private const val COLOR_SUNNY = "#FFD60A"
    private const val RAIN_FONT_SCALE_K = 0.6f
    private const val RAIN_FONT_SCALE_MAX_DAYS = 7f
    private const val TEMP_LABEL_TEXT_SIZE_DP = 24f
    private const val TOP_PADDING_DP = 60f
    private const val FORECAST_BAR_WIDTH_DP = 9f
    private const val TODAY_TRIPLE_BAR_WIDTH_DP = 8f

    private const val HIGH_LABEL_OFFSET_DP = 6f
    private const val ICON_BELOW_BAR_SPACING_DP = 3f
    private const val TEMP_LABEL_SPACING_DP = -1f
    private const val RAIN_TEXT_MARGIN_DP = 4f
    private const val RAIN_LABEL_EDGE_MARGIN_DP = 4f
    private const val NIGHT_RAIN_TEMP_OVERLAP_DP = 3f
    private const val NIGHT_INTERSTITIAL_H_NUDGE_DP = 2f
    private const val NIGHT_INTERSTITIAL_V_DROP_DP = 1f
    private const val ICON_STACK_SPACING_DP = 4f
    private const val DAY_LABEL_BASE_SIZE_DP = 17f
    private const val ICON_BASE_SIZE_DP = 36f
    private const val RAIN_TEXT_SIZE_DP = 24f
    private const val DAY_LABEL_BOTTOM_MARGIN_PX = 3f
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

    // Header rendering constants
    private const val HEADER_TEXT_COLOR = "#AAFFFFFF"

    @Volatile
    private var cachedPaintSet: PaintSet? = null
    @Volatile
    private var cachedScaleKey: String = ""

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
        val deltaColor: Int = Color.parseColor("#FF6B35"),
        val precipText: String? = null,
        val precipColor: Int = Color.parseColor("#5AC8FA"),
        val precipTextSizeDp: Float = HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP,
        val dateText: String? = null,
        val apiSourceText: String? = null,
        val apiTextSizeDp: Float = 16f,
        val settingsIconRes: Int = 0,
        val showIcon: Boolean = true,
        val showDelta: Boolean = true,
        val showPrecip: Boolean = true,
    )

    private fun DayData.effectiveHigh(): Float? {
        if (!isToday) return high
        return listOfNotNull(high, forecastHigh, trueActualHigh).maxOrNull()
    }

    data class DayData(
        val date: LocalDate,
        val label: String,
        val high: Float?,
        val low: Float?,
        val bottomStackLow: Float? = null,
        val iconRes: Int? = null,
        val isSunny: Boolean = false,
        val isRainy: Boolean = false,
        val isMixed: Boolean = false,
        val isToday: Boolean = false,
        val isPast: Boolean = false,
        val isClimateNormal: Boolean = false,
        val isSourceGapFallback: Boolean = false,
val forecastHigh: Float? = null,
    val forecastLow: Float? = null,
    val rainData: RainData = RainData(),
    val columnIndex: Int? = null,
        val isTodayForecastFallback: Boolean = false,
        val snapshotHigh: Float? = null,
        val snapshotLow: Float? = null,
        val trueActualHigh: Float? = null,
        val cloudCoverRatioOverride: Float? = null,
        val daysFromToday: Int = 0,
    )

    private data class LayoutInfo(
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
    ) {
        fun tempToY(temp: Float): Float =
            graphTop + graphHeight * (1 - (temp - minTemp) / tempRange)
    }

    @VisibleForTesting
    internal data class DayLabelInput(
        val date: LocalDate,
        val label: String,
        val isToday: Boolean = false,
    )

    @VisibleForTesting
    internal data class DayLabelLayoutResult(
        val textSizePx: Float,
        val textByDate: Map<LocalDate, String>,
        val scale: Float,
        val shortenedLabels: Boolean,
    )

    @VisibleForTesting
    internal data class RainAboveHighPlacement(
        val baseline: Float,
        val top: Float,
        val bottom: Float,
        val highLabelTop: Float,
        val fits: Boolean,
    )

    @VisibleForTesting
    internal data class TextMetrics(
        val ascent: Float,
        val descent: Float,
    )

    private class PaintSet(
        val barPaint: Paint,
        val todayBarPaint: Paint,
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
        val iconPaint: Paint,
        val todayIconPaint: Paint
    )

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
    ): Bitmap {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (days.isEmpty()) {
            Log.w(TAG, "renderGraph: empty days list, returning blank bitmap (${widthPx}x${heightPx})")
            return bitmap
        }

        val columns = if (numColumns > 0) numColumns else days.size
        val layout = computeLayout(context, days, widthPx, heightPx, columns, bitmapScale, job)
        val paints = getPaintSet(context, layout.scaleFactor, layout)

        if (headerData != null) {
            drawHeader(canvas, context, headerData, widthPx, layout)
        }

        Log.d(TAG, "renderGraph: days=${days.size}, minTemp=${layout.minTemp}, maxTemp=${layout.maxTemp}, widthPx=$widthPx, heightPx=$heightPx")

        val daysByColumn = days.withIndex().associate { (i, d) -> (d.columnIndex ?: i) to d }
        days.forEachIndexed { index, day ->
            job?.ensureActive()
            val columnIndex = day.columnIndex ?: index
            val centerX = layout.horizontalPadding + layout.dayWidth * columnIndex + layout.dayWidth / 2f
            val rightNeighbor = daysByColumn[columnIndex + 1]

            drawDayColumn(canvas, context, day, rightNeighbor, centerX, layout, paints, onRainLabelDrawn, onDayLabelDrawn)
            drawDayBars(canvas, context, day, centerX, layout, paints, onBarDrawn)
        }

        return bitmap
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
        val allTemps = days.flatMap { listOfNotNull(it.high, it.low, it.forecastHigh, it.forecastLow, it.snapshotHigh, it.snapshotLow, it.trueActualHigh) }
        val minTemp = allTemps.minOrNull() ?: 0f
        val maxTemp = allTemps.maxOrNull() ?: 100f
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)

        val density = context.resources.displayMetrics.density
        val widthDp = widthPx / density
        val heightDp = heightPx / density

        val dayWidthDp = widthDp / columns
        val widthScaleFactor = (dayWidthDp / BASE_DAY_WIDTH_DP).coerceIn(1.0f, 1.2f)
        val dayLabelWidthScale = computeDayLabelWidthScale(dayWidthDp)

        val heightScaleFactor = when {
            heightDp < 150f -> 0.92f
            else -> 1.0f
        }

        val scaleFactor = widthScaleFactor
        val labelScale = bitmapScale.coerceAtMost(1f)
        val horizontalPadding = 0f
        val topPadding = dpToPx(context, TOP_PADDING_DP * labelScale)

        val dayLabelScale = labelScale * dayLabelWidthScale
        val baseDayLabelTextSizePx = dpToPx(context, DAY_LABEL_BASE_SIZE_DP * dayLabelScale * DAY_LABEL_TEXT_SCALE)
        val dayWidth = (widthPx - 2 * horizontalPadding) / columns
        val dayLabelLayout = resolveDayLabelLayout(
            labels = days.map { DayLabelInput(it.date, it.label, it.isToday) },
            baseTextSizePx = baseDayLabelTextSizePx,
            maxTextWidthPx = (dayWidth - dpToPx(context, DAY_LABEL_HORIZONTAL_GAP_DP * labelScale)).coerceAtLeast(1f),
            minScale = MIN_DYNAMIC_DAY_LABEL_SCALE,
        )
        val dayLabelHeight = dayLabelLayout.textSizePx * DAY_LABEL_SIZE_MULTIPLIER
        val tempLabelHeight = dailyForecastTempLabelSizePx(context, heightScaleFactor, bitmapScale)

        val iconSize = dpToPx(context, ICON_BASE_SIZE_DP * labelScale).toInt()
        val attachedStackHeight = tempLabelHeight + iconSize + dpToPx(context, ICON_STACK_SPACING_DP * labelScale)

        val graphTop = topPadding
        val graphBottom = heightPx - dayLabelHeight - attachedStackHeight
        val graphHeight = graphBottom - graphTop

        val barWidth = dailyBarStrokeWidthPx(context, scaleFactor, bitmapScale)
        val tripleBarWidth = todayTripleBarStrokeWidthPx(context, scaleFactor, bitmapScale)

        if (dayLabelLayout.scale < 0.999f || dayLabelLayout.shortenedLabels) {
            Log.d(
                TAG,
                "dayLabel layout adjusted: widthPx=$widthPx columns=$columns dayWidth=$dayWidth" +
                    " baseTextSize=$baseDayLabelTextSizePx finalTextSize=${dayLabelLayout.textSizePx}" +
                    " scale=${dayLabelLayout.scale} shortened=${dayLabelLayout.shortenedLabels}" +
                    " labels=${dayLabelLayout.textByDate.values.joinToString(",")}",
            )
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
            tripleBarOffset = dpToPx(context, 8f * scaleFactor * labelScale),
            forecastBarOffset = barWidth * FORECAST_BAR_OFFSET_SCALE,
            iconSize = iconSize,
            dayLabelHeight = dayLabelHeight,
            tempLabelHeight = tempLabelHeight,
            bulbRadius = tripleBarWidth * BULB_RADIUS_SCALE,
            bitmapScale = bitmapScale,
            minBarHeightPx = dpToPx(context, MIN_BAR_HEIGHT_DP),
            dayLabelTextByDate = dayLabelLayout.textByDate,
        )
    }

    private fun getPaintSet(context: Context, scaleFactor: Float, layout: LayoutInfo): PaintSet {
        val key = "$scaleFactor-${layout.dayLabelHeight}-${layout.tempLabelHeight}"
        cachedPaintSet?.let { cached ->
            if (cachedScaleKey == key) return cached
        }

        val labelScale = layout.bitmapScale.coerceIn(0.5f, 1f)
        val barWidth = dailyBarStrokeWidthPx(context, scaleFactor, layout.bitmapScale)
        val tripleBarWidth = todayTripleBarStrokeWidthPx(context, scaleFactor, layout.bitmapScale)

        val set = PaintSet(
            barPaint = createBarPaint(Color.parseColor(COLOR_FORECAST), barWidth),
            todayBarPaint = createBarPaint(Color.parseColor(COLOR_TODAY_HIGHLIGHT), barWidth),
            todayObservedRedPaint = createBarPaint(Color.parseColor(COLOR_OBSERVED_RED), tripleBarWidth),
            todayObservedGhostPaint = createBarPaint(Color.parseColor(COLOR_OBSERVED_RED), tripleBarWidth).apply { alpha = GHOST_BAR_ALPHA },
            todayObservedRedBulbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_OBSERVED_RED)
                style = Paint.Style.FILL
            },
            todaySnapshotYellowPaint = createBarPaint(Color.parseColor(COLOR_TODAY_HIGHLIGHT), tripleBarWidth),
            todayForecastBluePaint = createBarPaint(Color.parseColor(COLOR_FORECAST), tripleBarWidth),
            historyBarPaint = createBarPaint(Color.parseColor(COLOR_OBSERVED_RED), barWidth * HISTORY_BAR_WIDTH_SCALE),
            forecastBarPaint = createBarPaint(Color.parseColor(COLOR_FORECAST), barWidth * FORECAST_OVERLAY_WIDTH_SCALE),
            climateOverlayBarPaint = createBarPaint(Color.parseColor(COLOR_FORECAST), barWidth * CLIMATE_OVERLAY_WIDTH_SCALE).apply { alpha = CLIMATE_OVERLAY_ALPHA },
            gapFallbackBarPaint = createBarPaint(Color.parseColor(COLOR_GAP_FALLBACK), barWidth),
            textPaint = createTextPaint(
                Color.parseColor(COLOR_LABEL_GRAY),
                layout.dayLabelHeight / DAY_LABEL_SIZE_MULTIPLIER
            ),
            todayTextPaint = createTextPaint(
                Color.parseColor(COLOR_TODAY_TEXT),
                layout.dayLabelHeight / DAY_LABEL_SIZE_MULTIPLIER,
                true
            ),
            tempTextPaint = createTextPaint(Color.parseColor(COLOR_WHITE), layout.tempLabelHeight),
            pastTempTextPaint = createTextPaint(Color.parseColor(COLOR_WHITE), layout.tempLabelHeight * PAST_TEMP_SCALE),
            todayTempTextPaint = createTextPaint(Color.parseColor(COLOR_TODAY_TEXT), layout.tempLabelHeight, true),
            rainTextPaint = createTextPaint(Color.parseColor(COLOR_FORECAST), dpToPx(context, RAIN_TEXT_SIZE_DP * scaleFactor * labelScale)),
            iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.parseColor(COLOR_LABEL_GRAY), PorterDuff.Mode.SRC_IN)
            },
            todayIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.parseColor(COLOR_TODAY_TEXT), PorterDuff.Mode.SRC_IN)
            }
        )

        cachedPaintSet = set
        cachedScaleKey = key
        return set
    }

    private fun createBarPaint(colorInt: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
    }

    private fun createTextPaint(colorInt: Int, size: Float, bold: Boolean = false): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        textSize = size
        textAlign = Paint.Align.CENTER
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun drawHeader(
        canvas: Canvas,
        context: Context,
        header: HeaderRenderData,
        widthPx: Int,
        layout: LayoutInfo,
    ) {
        val density = context.resources.displayMetrics.density
        val headerColor = Color.parseColor(HEADER_TEXT_COLOR)
        val labelScale = layout.bitmapScale.coerceAtMost(1f)

        val tempTextSizePx = dpToPx(context, HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP * labelScale)

        var cursorX = -dpToPx(context, 3f * labelScale)

        // Weather icon
        if (header.showIcon && header.iconRes != null && header.iconRes != 0) {
            val iconSizePx = dpToPx(context, HeaderConstants.WEATHER_ICON_SIZE_DP * labelScale).toInt()
            try {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, header.iconRes)
                if (drawable != null) {
                    val iconTop = -dpToPx(context, 2f * labelScale).toInt()
                    drawable.setBounds(
                        cursorX.toInt(), iconTop,
                        cursorX.toInt() + iconSizePx, iconTop + iconSizePx,
                    )
                    drawable.draw(canvas)
                }
            } catch (e: Exception) {
                Log.w(TAG, "drawHeader: failed to draw weather icon", e)
            }
            cursorX += dpToPx(context, (HeaderConstants.WEATHER_ICON_SIZE_DP + HeaderConstants.WEATHER_ICON_END_MARGIN_DP) * labelScale)
        }

        // Current temperature
        val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = headerColor
            textSize = tempTextSizePx
            textAlign = Paint.Align.LEFT
        }
        if (!header.currentTempText.isNullOrBlank()) {
            canvas.drawText(header.currentTempText, cursorX, -tempPaint.ascent(), tempPaint)
            cursorX += tempPaint.measureText(header.currentTempText)
        }

        // Delta
        if (header.showDelta && !header.deltaText.isNullOrBlank()) {
            cursorX += dpToPx(context, HeaderConstants.DELTA_MARGIN_START_DP * labelScale)
            val deltaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = header.deltaColor
                textSize = dpToPx(context, HeaderConstants.DELTA_TEXT_SIZE_DP * labelScale)
                textAlign = Paint.Align.LEFT
            }
            canvas.drawText(header.deltaText, cursorX, -deltaPaint.ascent(), deltaPaint)
            cursorX += deltaPaint.measureText(header.deltaText)
        }

        // Precip probability
        if (header.showPrecip && !header.precipText.isNullOrBlank()) {
            cursorX += dpToPx(context, HeaderConstants.PRECIP_MARGIN_START_DP * labelScale)
            val precipTextSizePx = dpToPx(context, header.precipTextSizeDp * labelScale)
            Log.d(TAG, "headerPrecip: text=\"${header.precipText}\" precipTextSizeDp=${header.precipTextSizeDp} labelScale=$labelScale textSizePx=$precipTextSizePx textSizeDp=${precipTextSizePx / context.resources.displayMetrics.density}")
            val precipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = header.precipColor
                textSize = precipTextSizePx
                textAlign = Paint.Align.LEFT
            }
            canvas.drawText(header.precipText, cursorX, -precipPaint.ascent(), precipPaint)
            cursorX += precipPaint.measureText(header.precipText)
        }

        // Settings gear icon (top-right corner)
        if (header.settingsIconRes != 0) {
            val gearSizePx = dpToPx(context, HeaderConstants.SETTINGS_ICON_SIZE_DP * labelScale).toInt()
            try {
                val drawable = androidx.core.content.ContextCompat.getDrawable(context, header.settingsIconRes)
                if (drawable != null) {
                    drawable.setTint(Color.parseColor(HEADER_TEXT_COLOR))
                    val gearRight = widthPx
                    val gearTop = 0
                    drawable.setBounds(
                        gearRight - gearSizePx, gearTop,
                        gearRight, gearTop + gearSizePx,
                    )
                    drawable.draw(canvas)
                }
            } catch (e: Exception) {
                Log.w(TAG, "drawHeader: failed to draw settings icon", e)
            }
        }

        // API source (top-right, left of settings gear)
        if (!header.apiSourceText.isNullOrBlank()) {
            val apiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = headerColor
                textSize = dpToPx(context, header.apiTextSizeDp * labelScale)
                textAlign = Paint.Align.RIGHT
            }
            val apiX = widthPx - dpToPx(context, HeaderConstants.API_SOURCE_MARGIN_END_DP * labelScale)
            canvas.drawText(header.apiSourceText, apiX, -apiPaint.ascent(), apiPaint)
        }

        // Date text (centered or right-aligned, using same logic as before)
        if (!header.dateText.isNullOrBlank()) {
            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = headerColor
                textSize = dpToPx(context, HeaderConstants.DATE_TEXT_SIZE_DP * labelScale)
                textAlign = Paint.Align.CENTER
            }
            val dateWidth = datePaint.measureText(header.dateText)
            val leftClusterRight = cursorX
            val apiPaintForMeasure = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = dpToPx(context, header.apiTextSizeDp * labelScale)
            }
            val apiContainerWidth = dpToPx(context, HeaderConstants.API_SOURCE_CONTAINER_PADDING_DP * labelScale) +
                apiPaintForMeasure.measureText(header.apiSourceText ?: "")
            val apiLeft = widthPx - dpToPx(context, HeaderConstants.API_SOURCE_MARGIN_END_DP * labelScale) - apiContainerWidth
            val gapPx = dpToPx(context, HeaderConstants.DATE_HORIZONTAL_GAP_DP * labelScale)

            // Try center placement
            val centerX = widthPx / 2f
            val centerLeft = centerX - dateWidth / 2f
            val centerRight = centerX + dateWidth / 2f
            val dateBaseline = -datePaint.ascent()
            if (centerLeft >= leftClusterRight + gapPx && centerRight <= apiLeft - gapPx) {
                canvas.drawText(header.dateText, centerX, dateBaseline, datePaint)
            } else {
                // Fall back to right placement (left of API area)
                val rightMarginPx = dpToPx(context, HeaderConstants.DATE_RIGHT_MARGIN_DP * labelScale)
                val rightX = widthPx - rightMarginPx
                val rightLeft = rightX - dateWidth / 2f
                val rightRight = rightX + dateWidth / 2f
                if (rightLeft >= leftClusterRight + gapPx && rightRight <= apiLeft - gapPx) {
                    canvas.drawText(header.dateText, rightX, dateBaseline, datePaint)
                }
            }
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
        if (!allowAdaptiveSegments || !day.isMixed || day.iconRes == null) {
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
            color = split.topColor
            shader = null
        }

        canvas.drawLine(centerX, topY, centerX, bottomY, bottomPaint)
        if (abs(topSegmentEndY - topY) > 0.5f) {
            canvas.drawLine(centerX, topY, centerX, topSegmentEndY, topPaint)
        }

        Log.d(
            TAG,
            "$logPrefix mixed bar geometry: date=${day.date} centerX=$centerX topY=$topY bottomY=$bottomY height=${barHeight}" +
                " splitRatio=${split.ratio} topFraction=${split.topFraction} topEndY=$topSegmentEndY" +
                " topColor=${String.format("#%08X", split.topColor)} bottomColor=${String.format("#%08X", split.bottomColor)}",
        )
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
        val dayLabelBaseline = layout.heightPx - DAY_LABEL_BOTTOM_MARGIN_PX
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
            val iconY = lowY + dpToPx(context, ICON_BELOW_BAR_SPACING_DP)
            drawWeatherIcon(canvas, context, day, centerX, iconY, layout.iconSize)

            val lowTempY = iconY + layout.iconSize + layout.tempLabelHeight + dpToPx(context, TEMP_LABEL_SPACING_DP)
            val lowLabelText = formatTempLabel(lowTemp, day.isToday || day.isPast)
            val tempPaint = when {
                day.isToday -> paints.todayTempTextPaint
                day.isPast -> paints.pastTempTextPaint
                else -> paints.tempTextPaint
            }
            canvas.drawText(lowLabelText, centerX, lowTempY, tempPaint)

        }

        drawDailyRainLabel(canvas, context, day, centerX, layout, paints, onRainLabelDrawn)
        drawNightRainLabel(canvas, context, day, rightNeighbor, centerX, layout, paints, onRainLabelDrawn)
    }

    private fun drawWeatherIcon(canvas: Canvas, context: Context, day: DayData, centerX: Float, iconY: Float, iconSize: Int) {
        if (day.iconRes == null) return
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, day.iconRes) ?: return
        
        val iconX = centerX - iconSize / 2f
        drawable.setBounds(iconX.toInt(), iconY.toInt(), (iconX + iconSize).toInt(), (iconY + iconSize).toInt())
        
        if (!day.isRainy && !day.isMixed) {
            val tint = if (day.isSunny) Color.parseColor(COLOR_SUNNY) else Color.parseColor(COLOR_LABEL_GRAY)
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
        val highY = day.high?.let { layout.tempToY(it) }
        val lowY = day.low?.let { layout.tempToY(it) }

        if (day.isToday) {
            drawTodayTripleBar(canvas, context, day, centerX, highY, lowY, layout, paints, onBarDrawn)
        } else if (highY != null || lowY != null) {
            val (hY, effectiveLowY) = resolveBarEndpoints(highY, lowY, layout.minBarHeightPx)!!
            val condColor = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
            val paint = when {
                day.isPast -> paints.historyBarPaint
                day.isSourceGapFallback -> paints.gapFallbackBarPaint
                else -> paints.barPaint.also { it.color = condColor }
            }
            
            val usesAdaptiveSegments = !day.isPast && day.isMixed && day.iconRes != null
            Log.d(TAG, "Bar color decision: date=${day.date}" +
                " isPast=${day.isPast} isSunny=${day.isSunny} isRainy=${day.isRainy}" +
                " isMixed=${day.isMixed} iconRes=${day.iconRes}" +
                " color=${String.format("#%08X", paint.color)} gradient=$usesAdaptiveSegments cloudRatioOverride=${day.cloudCoverRatioOverride}")
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

        if (!day.isToday && day.forecastHigh != null && day.forecastLow != null) {
            val fHighY = layout.tempToY(day.forecastHigh)
            val fLowY = layout.tempToY(day.forecastLow)
            val effectiveFLowY = clampMinBarHeight(fHighY, fLowY, layout.minBarHeightPx)
            
            val forecastX = centerX + layout.forecastBarOffset
            val condColor = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
            val overlayPaint = if (day.isClimateNormal) {
                paints.climateOverlayBarPaint.also { it.color = condColor; it.alpha = CLIMATE_OVERLAY_ALPHA }
            } else {
                paints.forecastBarPaint.also { it.color = condColor }
            }
            val overlayGradient = day.isMixed && day.iconRes != null
            Log.d(TAG, "Overlay color decision: date=${day.date}" +
                " isSunny=${day.isSunny} isRainy=${day.isRainy}" +
                " isMixed=${day.isMixed} iconRes=${day.iconRes}" +
                " color=${String.format("#%08X", condColor)} gradient=$overlayGradient cloudRatioOverride=${day.cloudCoverRatioOverride}" +
                " isClimateNormal=${day.isClimateNormal}")
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

        if (day.high != null) {
            val displayHigh = day.effectiveHigh() ?: day.high
            val highLabel = formatTempLabel(displayHigh, day.isToday || day.isPast)
            val y = highY ?: (lowY?.let { it - layout.minBarHeightPx } ?: 0f)
            val labelY = if (day.isToday) {
                val absoluteHigh = listOfNotNull(day.high, day.forecastHigh, day.trueActualHigh).maxOrNull() ?: 0f
                layout.tempToY(absoluteHigh)
            } else y
            val tempPaint = when {
                day.isToday -> paints.todayTempTextPaint
                day.isPast -> paints.pastTempTextPaint
                else -> paints.tempTextPaint
            }
            canvas.drawText(highLabel, centerX, labelY - dpToPx(context, HIGH_LABEL_OFFSET_DP * layout.bitmapScale.coerceAtMost(1f)), tempPaint)
        }
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
                canvas.drawLine(centerX - layout.tripleBarOffset, sHighY, centerX - layout.tripleBarOffset, effectiveSLowY, paints.todaySnapshotYellowPaint)
            }
        }

        val fHigh = day.forecastHigh ?: day.high ?: return
        val fLow = day.forecastLow ?: day.low ?: return
        val fHighY = layout.tempToY(fHigh)
        val fLowY = layout.tempToY(fLow)
        val effectiveFLowY = clampMinBarHeight(fHighY, fLowY, layout.minBarHeightPx)
        
        val condColor = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
        val forecastPaint = paints.todayForecastBluePaint.also { it.color = condColor }
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

        canvas.drawLine(centerX, obsHighY, centerX, effectiveObsLowY, paints.todayObservedRedPaint)
        canvas.drawCircle(centerX, effectiveObsLowY + (layout.bulbRadius * BULB_VERTICAL_CENTER_FRACTION), layout.bulbRadius, paints.todayObservedRedBulbPaint)
        
        day.trueActualHigh?.let { trueHigh ->
            val obsHighTemp = day.high ?: 0f
            if (trueHigh > obsHighTemp) {
                val ghostHighY = layout.tempToY(trueHigh)
                canvas.drawLine(centerX, ghostHighY, centerX, obsHighY, paints.todayObservedGhostPaint)
                onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY_GHOST", ghostHighY, obsHighY, centerX, paints.todayObservedGhostPaint.color))
            }
        }
        
        onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY", obsHighY, effectiveObsLowY, centerX, paints.todayObservedRedPaint.color))
    }

    // ── Rain labels ────────────────────────────────────────────────────────

    private fun drawDailyRainLabel(
        canvas: Canvas,
        context: Context,
        day: DayData,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)?,
    ) {
val label = day.rainData.dailyRainLabelText ?: return
    val rainText = label
    val labelScale = layout.bitmapScale.coerceIn(0.5f, 1f)
    val localRainPaint = createScaledRainPaint(context, paints, day, day.rainData.dailyPrecipProbability, "day", labelScale = labelScale)

        val textWidth = localRainPaint.measureText(rainText)
        val maxTextWidth = layout.dayWidth - dpToPx(context, RAIN_TEXT_MARGIN_DP * layout.scaleFactor)
        if (textWidth > maxTextWidth) {
            Log.d(TAG, "rainLabel skipped: text too wide: date=${day.date} textWidth=${textWidth}px maxWidth=${maxTextWidth}px dayWidth=${layout.dayWidth}px label=\"$rainText\"")
            return
        }

        val highBaseline = resolveHighLabelBaseline(context, day, layout)
        if (highBaseline == null) {
            Log.d(TAG, "rainLabel skipped: no high baseline (null high temp): date=${day.date} high=${day.high}")
            return
        }

        val metrics = textMetrics(localRainPaint)
        val tempPaint = when {
            day.isToday -> paints.todayTempTextPaint
            day.isPast -> paints.pastTempTextPaint
            else -> paints.tempTextPaint
        }
        val tempMetrics = textMetrics(tempPaint)
        val topMargin = layout.graphTop * 0.2f
        val gap = dpToPx(context, RAIN_HIGH_TEMP_GAP_DP * layout.bitmapScale.coerceAtMost(1f))
        val placement = resolveRainAboveHighPlacement(
            highBaseline = highBaseline,
            highMetrics = tempMetrics,
            rainMetrics = metrics,
            topMargin = topMargin,
            gap = gap,
        )

        if (placement.fits) {
            canvas.drawText(rainText, centerX, placement.baseline, localRainPaint)
            onRainLabelDrawn?.invoke(
                RainLabelDrawnDebug(
                    date = day.date,
                    text = rainText,
                    placement = "ABOVE_HIGH",
                    centerX = centerX,
                    leftX = centerX - localRainPaint.measureText(rainText) / 2f,
                    rightX = centerX + localRainPaint.measureText(rainText) / 2f,
                    baselineY = placement.baseline,
                    topY = placement.top,
                    bottomY = placement.bottom,
                    anchorTopY = placement.highLabelTop,
                    anchorBaselineY = highBaseline,
                    isNightLabel = false,
                ),
            )
            Log.d(
                TAG,
                "rainLabel drawn: date=${day.date} label=\"$rainText\" baseline=${placement.baseline}" +
                    " top=${placement.top} bottom=${placement.bottom} highBaseline=$highBaseline" +
                    " highTop=${placement.highLabelTop} gap=$gap",
            )
            return
        }

        Log.d(
            TAG,
            "rainLabel skipped: above-high insufficient space: date=${day.date} label=\"$rainText\"" +
                " baseline=${placement.baseline} top=${placement.top} topMargin=$topMargin ascent=${metrics.ascent}" +
                " descent=${metrics.descent} highBaseline=$highBaseline highTop=${placement.highLabelTop}" +
                " gap=$gap overflow=${topMargin - placement.top}px",
        )
    }

    @VisibleForTesting
    internal fun resolveRainAboveHighPlacement(
        highBaseline: Float,
        highMetrics: TextMetrics,
        rainMetrics: TextMetrics,
        topMargin: Float,
        gap: Float,
    ): RainAboveHighPlacement {
        val highLabelTop = highBaseline + highMetrics.ascent
        val baseline = highLabelTop - gap - rainMetrics.descent
        val top = baseline + rainMetrics.ascent
        val bottom = baseline + rainMetrics.descent
        return RainAboveHighPlacement(
            baseline = baseline,
            top = top,
            bottom = bottom,
            highLabelTop = highLabelTop,
            fits = top >= topMargin,
        )
    }

    private fun textMetrics(paint: Paint): TextMetrics {
        val metrics = paint.fontMetrics
        if ((metrics.ascent != 0f || metrics.descent != 0f) || paint.textSize <= 0f) {
            return TextMetrics(metrics.ascent, metrics.descent)
        }
        return TextMetrics(
            ascent = -paint.textSize * 0.8f,
            descent = paint.textSize * 0.2f,
        )
    }

    private fun drawNightRainLabel(
        canvas: Canvas,
        context: Context,
        day: DayData,
        rightNeighbor: DayData?,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)?,
    ) {
        val rainText = day.rainData.nightRainLabelText ?: return
        
        // Multi-step fitting strategy
        var currentPaint = createScaledRainPaint(context, paints, day, day.rainData.nighttimePrecipProbability, "night", labelScale = layout.bitmapScale.coerceIn(0.5f, 1f))
        var textWidth = currentPaint.measureText(rainText)
        val shiftedCenterX = centerX + layout.dayWidth / 2f
        val halfWidth = textWidth / 2f
        
        // 1. Try shifted position with current scale
        val canShiftStandard = (shiftedCenterX + halfWidth <= layout.widthPx)
        
        val finalCenterX: Float
        val finalPaint: Paint
        val placementType: String
        
        if (canShiftStandard) {
            finalCenterX = shiftedCenterX
            finalPaint = currentPaint
            placementType = "NIGHT_SHIFTED_RIGHT"
        } else {
            // 2. Try smaller font scale at shifted position if near boundary
            val reducedPaint = createScaledRainPaint(context, paints, day, day.rainData.nighttimePrecipProbability, "night", extraScale = 0.85f, labelScale = layout.bitmapScale.coerceIn(0.5f, 1f))
            val reducedWidth = reducedPaint.measureText(rainText)
            val reducedHalfWidth = reducedWidth / 2f
            
            if (shiftedCenterX + reducedHalfWidth <= layout.widthPx) {
                finalCenterX = shiftedCenterX
                finalPaint = reducedPaint
                placementType = "NIGHT_SHIFTED_SCALED"
            } else {
                // 3. Fallback to centered if shifting is impossible (last day)
                val maxTextWidth = layout.dayWidth - dpToPx(context, RAIN_TEXT_MARGIN_DP * layout.scaleFactor)
                if (textWidth > maxTextWidth) {
                    // Try reduced scale centered
                    if (reducedWidth <= maxTextWidth) {
                        finalCenterX = centerX
                        finalPaint = reducedPaint
                        placementType = "NIGHT_CENTERED_SCALED"
                    } else {
                        Log.d(TAG, "nightRainLabel skipped: text too wide even scaled: date=${day.date} textWidth=${reducedWidth}px maxWidth=${maxTextWidth}px")
                        return
                    }
                } else {
                    finalCenterX = centerX
                    finalPaint = currentPaint
                    placementType = "NIGHT_CENTERED"
                }
            }
        }

        val leftBaseline = resolveLowLabelBaseline(context, day, layout)
        if (leftBaseline == null) {
            Log.d(TAG, "nightRainLabel skipped: no low baseline: date=${day.date} low=${day.low}")
            return
        }

        val isShifted = placementType == "NIGHT_SHIFTED_RIGHT" || placementType == "NIGHT_SHIFTED_SCALED"
        val rightBaseline = if (isShifted) {
            rightNeighbor?.let { resolveLowLabelBaseline(context, it, layout) }
        } else null

        val metrics = finalPaint.fontMetrics
        val tempPaint = if (day.isToday) paints.todayTempTextPaint else paints.tempTextPaint
        val tempMetrics = tempPaint.fontMetrics
        val hardBottomLimit = layout.heightPx - dpToPx(context, DAY_LABEL_BOTTOM_MARGIN_PX)

        // 1. Try interstitial: tuck rain label between the two temp labels,
        //    anchoring just below the higher one.
        if (isShifted && rightBaseline != null && leftBaseline != rightBaseline) {
            val higherBaseline = minOf(leftBaseline, rightBaseline)
            val lowerBaseline = maxOf(leftBaseline, rightBaseline)
            val hNudge = dpToPx(context, NIGHT_INTERSTITIAL_H_NUDGE_DP)
            val interstitialCenterX = if (lowerBaseline == leftBaseline) {
                finalCenterX + hNudge
            } else {
                finalCenterX - hNudge
            }
            val vDrop = dpToPx(context, NIGHT_INTERSTITIAL_V_DROP_DP)
            val interstitialTopY = higherBaseline + tempMetrics.descent - dpToPx(context, NIGHT_RAIN_TEMP_OVERLAP_DP) + vDrop
            val interstitialBaseline = interstitialTopY - metrics.ascent
            val interstitialBottomWithMargin = interstitialBaseline + metrics.descent + dpToPx(context, 1f)

            if (interstitialBottomWithMargin <= hardBottomLimit) {
                canvas.drawText(rainText, interstitialCenterX, interstitialBaseline, finalPaint)
                onRainLabelDrawn?.invoke(
                    RainLabelDrawnDebug(
                        date = day.date,
                        text = rainText,
                        placement = "NIGHT_INTERSTITIAL",
                        centerX = interstitialCenterX,
                        leftX = interstitialCenterX - finalPaint.measureText(rainText) / 2f,
                        rightX = interstitialCenterX + finalPaint.measureText(rainText) / 2f,
                        baselineY = interstitialBaseline,
                        topY = interstitialBaseline + metrics.ascent,
                        bottomY = interstitialBaseline + metrics.descent,
                        anchorBaselineY = higherBaseline,
                        isNightLabel = true,
                    )
                )
                Log.d(TAG, "nightRainLabel interstitial: date=${day.date} baseline=$interstitialBaseline higherBaseline=$higherBaseline lowerBaseline=$lowerBaseline leftBaseline=$leftBaseline rightBaseline=$rightBaseline")
                return
            }
        }

        // 2. Fallback: anchor below the lower of the two temp labels.
        val anchorBaseline = maxOf(leftBaseline, rightBaseline ?: leftBaseline)

        val topY = anchorBaseline + tempMetrics.descent - dpToPx(context, NIGHT_RAIN_TEMP_OVERLAP_DP)
        val baseline = topY - metrics.ascent

        val baselineWithMargin = baseline + metrics.descent + dpToPx(context, 1f)

        if (baselineWithMargin <= hardBottomLimit) {
            canvas.drawText(rainText, finalCenterX, baseline, finalPaint)
            onRainLabelDrawn?.invoke(
                RainLabelDrawnDebug(
                    date = day.date,
                    text = rainText,
                    placement = placementType,
                    centerX = finalCenterX,
                    leftX = finalCenterX - finalPaint.measureText(rainText) / 2f,
                    rightX = finalCenterX + finalPaint.measureText(rainText) / 2f,
                    baselineY = baseline,
                    topY = baseline + metrics.ascent,
                    bottomY = baseline + metrics.descent,
                    anchorBaselineY = anchorBaseline,
                    isNightLabel = true,
                )
            )
            return
        }

        Log.d(TAG, "nightRainLabel skipped: bottom overflow: date=${day.date} baseline=$baseline hardBottomLimit=$hardBottomLimit descent=${metrics.descent} topY=${baseline + metrics.ascent} anchorBaseline=$anchorBaseline leftBaseline=$leftBaseline rightBaseline=$rightBaseline overflow=${baselineWithMargin - hardBottomLimit}px")
    }

    private fun createScaledRainPaint(
        context: Context,
        paints: PaintSet,
        day: DayData,
        probability: Int?,
        labelType: String,
        extraScale: Float = 1.0f,
        labelScale: Float = 1.0f,
    ): Paint {
        val prob = (probability ?: 0).toFloat() / 100f
        val probScale = HeaderPrecipCalculator.getPrecipScaleFactor(probability ?: 0)
        val distanceScale = 1.0f - RAIN_FONT_SCALE_K * (1.0f - prob) * (day.daysFromToday / RAIN_FONT_SCALE_MAX_DAYS)
        val nightScale = if (labelType == "night") 0.72f else 1.0f
        val combinedScale = probScale * distanceScale
        val finalTextSize = paints.rainTextPaint.textSize * combinedScale * extraScale * nightScale

        val finalTextSizeDp = finalTextSize / context.resources.displayMetrics.density
        Log.d(TAG, "rainFont: type=$labelType date=${day.date} daysFromToday=${day.daysFromToday} prob=$probability% probScale=$probScale distanceScale=$distanceScale combinedScale=$combinedScale extraScale=$extraScale nightScale=$nightScale baseTextSize=${paints.rainTextPaint.textSize}px finalTextSize=${finalTextSize}px (${finalTextSizeDp}dp) density=${context.resources.displayMetrics.density}")

        return Paint(paints.rainTextPaint).apply {
            textSize = finalTextSize
        }
    }

    // ── Baseline resolvers (shared by bars and rain labels) ────────────────

    private fun resolveHighLabelBaseline(
        context: Context,
        day: DayData,
        layout: LayoutInfo,
    ): Float? {
        day.high ?: return null
        val absoluteHigh = day.effectiveHigh() ?: return null
        val labelY = layout.tempToY(absoluteHigh)
        return labelY - dpToPx(context, HIGH_LABEL_OFFSET_DP * layout.bitmapScale.coerceAtMost(1f))
    }

    private fun resolveLowLabelBaseline(
        context: Context,
        day: DayData,
        layout: LayoutInfo,
    ): Float? {
        val lowTemp = resolveBottomStackLow(day) ?: return null
        val lowY = layout.tempToY(lowTemp)
        val iconY = lowY + dpToPx(context, ICON_BELOW_BAR_SPACING_DP)
        return iconY + layout.iconSize + layout.tempLabelHeight + dpToPx(context, TEMP_LABEL_SPACING_DP)
    }

    // ── Utility ───────────────────────────────────────────────────────────

    internal fun resolveBottomStackLow(day: DayData): Float? = day.bottomStackLow ?: day.low

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
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

    private fun measureTextWidth(paint: Paint, text: String): Float {
        val measured = paint.measureText(text)
        if (measured > 0f) return measured
        return text.length * paint.textSize * 0.55f
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
        context: Context,
        heightScaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float {
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        return dpToPx(context, TEMP_LABEL_TEXT_SIZE_DP * heightScaleFactor * labelScale)
    }

    @VisibleForTesting
    internal fun dailyBarStrokeWidthPx(
        context: Context,
        scaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float {
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        return dpToPx(context, FORECAST_BAR_WIDTH_DP * scaleFactor * labelScale)
    }

    @VisibleForTesting
    internal fun todayTripleBarStrokeWidthPx(
        context: Context,
        scaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float {
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        return dpToPx(context, TODAY_TRIPLE_BAR_WIDTH_DP * scaleFactor * labelScale)
    }

    private fun clampMinBarHeight(highY: Float, lowY: Float, minBarHeight: Float): Float =
        if (abs(highY - lowY) < minBarHeight) highY + minBarHeight else lowY

    private fun resolveBarEndpoints(highY: Float?, lowY: Float?, minBarHeight: Float): Pair<Float, Float>? {
        val hY = highY ?: (lowY?.let { it - minBarHeight }) ?: return null
        val lY = lowY ?: (hY + minBarHeight)
        return hY to clampMinBarHeight(hY, lY, minBarHeight)
    }

    private fun formatTempLabel(actual: Float, isActualData: Boolean): String {
        if (!isActualData) return "${actual.roundToInt()}°"
        val rounded = actual.roundToInt()
        return if (kotlin.math.abs(actual - rounded) < 0.01f) "$rounded°" else String.format("%.1f°", actual)
    }
}
