package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import com.weatherwidget.util.DailyForecastIconResolver
import com.weatherwidget.util.WeatherConditionColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object DailyForecastGraphRenderer {
    private const val TAG = "DailyGraphRenderer"

    private const val DAY_LABEL_SIZE_MULTIPLIER = 1.4f
    private const val DAY_LABEL_TEXT_SCALE = 1.5f
    private const val BASE_DAY_WIDTH_DP = 70f
    private const val MIN_DAY_LABEL_WIDTH_SCALE = 0.96f
    private const val MAX_DAY_LABEL_WIDTH_SCALE = 1.04f
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
    private const val MIN_RAIN_FONT_SCALE = 0.4f
    private const val MIN_COLUMNS_FOR_TOP_DATE = 6
    private const val TOP_DATE_TEXT_SIZE_NARROW_SP = 16f
    private const val TOP_DATE_TEXT_SIZE_WIDE_SP = 18f
    private const val NARROW_WIDGET_WIDTH_DP = 420f

    private var cachedPaintSet: PaintSet? = null
    private var cachedScaleKey: String = ""
    private val topDateFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())

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
    )

    data class RainLabelDrawnDebug(
        val date: LocalDate,
        val text: String,
        val placement: String,
        val centerX: Float,
        val baselineY: Float,
    )

    data class DateLabelDrawnDebug(
        val date: LocalDate,
        val text: String,
        val centerX: Float,
        val baselineY: Float,
        val columnIndex: Int,
    )

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
        val rainSummary: String? = null,
        val dailyPrecipProbability: Int? = null,
        val dailyPrecipAmountMm: Float? = null,
        val dailyRainLabelText: String? = null,
        val hasRainForecast: Boolean = false,
        val columnIndex: Int? = null,
        val isTodayForecastFallback: Boolean = false,
        val snapshotHigh: Float? = null,
        val snapshotLow: Float? = null,
        val trueActualHigh: Float? = null,
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
        val bulbRadius: Float
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
        val todayTempTextPaint: Paint,
        val rainTextPaint: Paint,
        val topDateTextPaint: Paint,
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
        onBarDrawn: ((BarDrawnDebug) -> Unit)? = null,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)? = null,
        onDateLabelDrawn: ((DateLabelDrawnDebug) -> Unit)? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (days.isEmpty()) {
            Log.w(TAG, "renderGraph: empty days list, returning blank bitmap (${widthPx}x${heightPx})")
            return bitmap
        }

        val columns = if (numColumns > 0) numColumns else days.size
        val layout = computeLayout(context, days, widthPx, heightPx, columns, bitmapScale)
        val paints = getPaintSet(context, layout.scaleFactor, layout)

        android.util.Log.d(TAG, "renderGraph: days=${days.size}, minTemp=${layout.minTemp}, maxTemp=${layout.maxTemp}, widthPx=$widthPx, heightPx=$heightPx")

        days.forEachIndexed { index, day ->
            val columnIndex = day.columnIndex ?: index
            val centerX = layout.horizontalPadding + layout.dayWidth * columnIndex + layout.dayWidth / 2f
            
            drawDayColumn(canvas, context, day, centerX, layout, paints, onRainLabelDrawn)
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
        bitmapScale: Float
    ): LayoutInfo {
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
            heightDp < 150f -> 1.0f
            heightDp < 250f -> 1.0f
            else -> 1.05f
        }

        val scaleFactor = widthScaleFactor
        val horizontalPadding = 0f
        val topPadding = dpToPx(context, 24f * scaleFactor)

        val dayLabelScale = bitmapScale.coerceIn(0.5f, 1f) * dayLabelWidthScale
        val dayLabelHeight = dpToPx(context, 12.5f * dayLabelScale * DAY_LABEL_SIZE_MULTIPLIER * DAY_LABEL_TEXT_SCALE)
        val tempLabelHeight = dpToPx(context, 10.5f * heightScaleFactor)

        val iconSize = dpToPx(context, 16f).toInt()
        val attachedStackHeight = tempLabelHeight + iconSize + dpToPx(context, 4f)

        val graphTop = topPadding
        val graphBottom = heightPx - dayLabelHeight - attachedStackHeight
        val graphHeight = graphBottom - graphTop

        val dayWidth = (widthPx - 2 * horizontalPadding) / columns
        val barWidth = dpToPx(context, 2.2f * scaleFactor)
        val tripleBarWidth = dpToPx(context, 1.4f * scaleFactor)

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
            tripleBarOffset = dpToPx(context, 1.8f * scaleFactor),
            forecastBarOffset = barWidth * 1.2f,
            iconSize = iconSize,
            dayLabelHeight = dayLabelHeight,
            tempLabelHeight = tempLabelHeight,
            bulbRadius = tripleBarWidth * 1.2f // Updated multiplier
        )
    }

    private fun getPaintSet(context: Context, scaleFactor: Float, layout: LayoutInfo): PaintSet {
        val key = "$scaleFactor-${layout.dayLabelHeight}-${layout.tempLabelHeight}"
        if (cachedScaleKey == key && cachedPaintSet != null) {
            return cachedPaintSet!!
        }

        val barWidth = dpToPx(context, 2.2f * scaleFactor)
        val tripleBarWidth = dpToPx(context, 1.4f * scaleFactor)

        val set = PaintSet(
            barPaint = createBarPaint(Color.parseColor(COLOR_FORECAST), barWidth),
            todayBarPaint = createBarPaint(Color.parseColor(COLOR_TODAY_HIGHLIGHT), barWidth),
            todayObservedRedPaint = createBarPaint(Color.parseColor(COLOR_OBSERVED_RED), tripleBarWidth),
            todayObservedGhostPaint = createBarPaint(Color.parseColor(COLOR_OBSERVED_RED), tripleBarWidth).apply { alpha = 75 },
            todayObservedRedBulbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(COLOR_OBSERVED_RED)
                style = Paint.Style.FILL
            },
            todaySnapshotYellowPaint = createBarPaint(Color.parseColor(COLOR_TODAY_HIGHLIGHT), tripleBarWidth),
            todayForecastBluePaint = createBarPaint(Color.parseColor(COLOR_FORECAST), tripleBarWidth),
            historyBarPaint = createBarPaint(Color.parseColor(COLOR_OBSERVED_RED), barWidth * 1.1f),
            forecastBarPaint = createBarPaint(Color.parseColor(COLOR_FORECAST), barWidth * 0.8f),
            climateOverlayBarPaint = createBarPaint(Color.parseColor(COLOR_FORECAST), barWidth * 0.8f).apply { alpha = 80 },
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
            todayTempTextPaint = createTextPaint(Color.parseColor(COLOR_TODAY_TEXT), layout.tempLabelHeight, true),
            rainTextPaint = createTextPaint(Color.parseColor(COLOR_FORECAST), dpToPx(context, 8f * scaleFactor)),
            topDateTextPaint = createTextPaint(
                Color.parseColor(COLOR_LABEL_GRAY),
                resolveTopDateTextSizePx(context, layout)
            ),
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

    private fun drawTopDateLabel(
        canvas: Canvas,
        context: Context,
        days: List<DayData>,
        layout: LayoutInfo,
        paints: PaintSet,
        onDateLabelDrawn: ((DateLabelDrawnDebug) -> Unit)?,
    ) {
        if (layout.columns < MIN_COLUMNS_FOR_TOP_DATE || days.isEmpty()) return

        val middleDay = days.minByOrNull { kotlin.math.abs((it.columnIndex ?: days.indexOf(it)) - (layout.columns / 2)) }
            ?: return
        val text = middleDay.date.format(topDateFormatter)
        val baselineY = resolveTopDateBaseline(context, paints.topDateTextPaint)
        val textWidth = paints.topDateTextPaint.measureText(text)
        val preferredColumn = (layout.columns / 2).coerceIn(0, layout.columns - 1)

        for (candidateColumn in preferredColumn until layout.columns) {
            val centerX = layout.horizontalPadding + layout.dayWidth * candidateColumn + layout.dayWidth / 2f
            if (!fitsWithinCanvas(centerX, textWidth, layout.widthPx.toFloat())) {
                Log.d(TAG, "topDate skipped candidate: date=$text column=$candidateColumn reason=overflow centerX=$centerX textWidth=$textWidth widthPx=${layout.widthPx}")
                continue
            }
            if (collidesWithHighLabels(days, candidateColumn, centerX, textWidth, baselineY, layout, paints, context)) {
                Log.d(TAG, "topDate skipped candidate: date=$text column=$candidateColumn reason=high_label_collision centerX=$centerX")
                continue
            }

            canvas.drawText(text, centerX, baselineY, paints.topDateTextPaint)
            Log.d(TAG, "topDate drawn: date=$text column=$candidateColumn centerX=$centerX baselineY=$baselineY textSize=${paints.topDateTextPaint.textSize}")
            onDateLabelDrawn?.invoke(
                DateLabelDrawnDebug(
                    date = middleDay.date,
                    text = text,
                    centerX = centerX,
                    baselineY = baselineY,
                    columnIndex = candidateColumn,
                ),
            )
            return
        }

        Log.d(TAG, "topDate skipped: date=$text reason=no_safe_slot columns=${layout.columns} widthPx=${layout.widthPx}")
    }

    private fun resolveTopDateBaseline(context: Context, paint: Paint): Float {
        return -paint.fontMetrics.ascent
    }

    private fun fitsWithinCanvas(centerX: Float, textWidth: Float, widthPx: Float): Boolean {
        val halfWidth = textWidth / 2f
        return centerX - halfWidth >= 0f && centerX + halfWidth <= widthPx
    }

    private fun collidesWithHighLabels(
        days: List<DayData>,
        candidateColumn: Int,
        centerX: Float,
        textWidth: Float,
        baselineY: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        context: Context,
    ): Boolean {
        val dateBounds = boundsForText(centerX, baselineY, textWidth, paints.topDateTextPaint.fontMetrics)
        return days.anyIndexed { index, day ->
            val dayColumn = day.columnIndex ?: index
            if (dayColumn != candidateColumn) {
                return@anyIndexed false
            }

            val displayHigh = if (day.isToday) {
                listOfNotNull(day.high, day.forecastHigh, day.trueActualHigh).maxOrNull()
            } else {
                day.high
            } ?: return@anyIndexed false

            val highBaseline = resolveHighLabelBaseline(context, day, layout) ?: return@anyIndexed false
            val highText = formatTempLabel(displayHigh, day.isToday || day.isPast)
            val highCenterX = layout.horizontalPadding + layout.dayWidth * dayColumn + layout.dayWidth / 2f
            val highWidth = paints.tempTextPaint.measureText(highText)
            val highBounds = boundsForText(highCenterX, highBaseline, highWidth, paints.tempTextPaint.fontMetrics)
            RectF.intersects(dateBounds, highBounds)
        }
    }

    private fun boundsForText(centerX: Float, baselineY: Float, textWidth: Float, metrics: Paint.FontMetrics): RectF {
        val halfWidth = textWidth / 2f
        return RectF(
            centerX - halfWidth,
            baselineY + metrics.ascent,
            centerX + halfWidth,
            baselineY + metrics.descent,
        )
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (index: Int, item: T) -> Boolean): Boolean {
        forEachIndexed { index, item ->
            if (predicate(index, item)) return true
        }
        return false
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

    private fun drawDayColumn(
        canvas: Canvas,
        context: Context,
        day: DayData,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)?
    ) {
        val labelPaint = if (day.isToday) paints.todayTextPaint else paints.textPaint
        canvas.drawText(day.label, centerX, layout.heightPx - 3f, labelPaint)

        val lowTemp = resolveBottomStackLow(day)
        val lowY = lowTemp?.let {
            layout.graphTop + layout.graphHeight * (1 - (it - layout.minTemp) / layout.tempRange)
        }

        if (lowY != null) {
            val iconY = lowY + dpToPx(context, 3f)
            drawWeatherIcon(canvas, context, day, centerX, iconY, layout.iconSize)

            val lowTempY = iconY + layout.iconSize + layout.tempLabelHeight + dpToPx(context, 1f)
            val lowLabelText = formatTempLabel(lowTemp, day.isToday || day.isPast)
            val tempPaint = if (day.isToday) paints.todayTempTextPaint else paints.tempTextPaint
            canvas.drawText(lowLabelText, centerX, lowTempY, tempPaint)

        }

        drawDailyRainLabel(canvas, context, day, centerX, layout, paints, onRainLabelDrawn)
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
        val highY = day.high?.let {
            layout.graphTop + layout.graphHeight * (1 - (it - layout.minTemp) / layout.tempRange)
        }
        val lowY = day.low?.let {
            layout.graphTop + layout.graphHeight * (1 - (it - layout.minTemp) / layout.tempRange)
        }

        if (day.isToday) {
            drawTodayTripleBar(canvas, context, day, centerX, highY, lowY, layout, paints, onBarDrawn)
        } else if (highY != null || lowY != null) {
            val paint = when {
                day.isPast -> paints.historyBarPaint
                day.isSourceGapFallback -> paints.gapFallbackBarPaint
                else -> paints.barPaint.also {
                    it.color = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
                }
            }
            
            val minBarHeight = dpToPx(context, MIN_BAR_HEIGHT_DP)
            val hY = highY ?: (lowY?.let { it - minBarHeight } ?: 0f)
            val lY = lowY ?: (highY?.let { it + minBarHeight } ?: 0f)
            val effectiveLowY = if (kotlin.math.abs(hY - lY) < minBarHeight) hY + minBarHeight else lY

            val applyGradient = day.isMixed && day.iconRes != null
            if (applyGradient) {
                paint.shader = WeatherConditionColors.forecastBarGradient(day.iconRes, hY, effectiveLowY)
            }
            val colorHex = String.format("#%08X", paint.color)
            Log.d(TAG, "Bar color decision: date=${day.date}" +
                " isPast=${day.isPast} isSunny=${day.isSunny} isRainy=${day.isRainy}" +
                " isMixed=${day.isMixed} iconRes=${day.iconRes}" +
                " color=$colorHex gradient=$applyGradient")
            canvas.drawLine(centerX, hY, centerX, effectiveLowY, paint)
            paint.shader = null
            onBarDrawn?.invoke(BarDrawnDebug(day.date, if (day.isPast) "HISTORY" else "FUTURE", hY, effectiveLowY, centerX, paint.color))
        }

        // Forecast Overlay (only for non-today days)
        if (!day.isToday && day.forecastHigh != null && day.forecastLow != null) {
            val fHighY = layout.graphTop + layout.graphHeight * (1 - (day.forecastHigh - layout.minTemp) / layout.tempRange)
            val fLowY = layout.graphTop + layout.graphHeight * (1 - (day.forecastLow - layout.minTemp) / layout.tempRange)
            val minBarHeight = dpToPx(context, MIN_BAR_HEIGHT_DP)
            val effectiveFLowY = if (kotlin.math.abs(fHighY - fLowY) < minBarHeight) fHighY + minBarHeight else fLowY
            
            val forecastX = centerX + layout.forecastBarOffset
            val condColor = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
            val overlayPaint = if (day.isClimateNormal) {
                paints.climateOverlayBarPaint.also { it.color = condColor; it.alpha = 80 }
            } else {
                paints.forecastBarPaint.also { it.color = condColor }
            }
            val overlayGradient = day.isMixed && day.iconRes != null
            if (overlayGradient) {
                overlayPaint.shader = WeatherConditionColors.forecastBarGradient(day.iconRes, fHighY, effectiveFLowY)
            }
            val overlayColorHex = String.format("#%08X", condColor)
            Log.d(TAG, "Overlay color decision: date=${day.date}" +
                " isSunny=${day.isSunny} isRainy=${day.isRainy}" +
                " isMixed=${day.isMixed} iconRes=${day.iconRes}" +
                " color=$overlayColorHex gradient=$overlayGradient" +
                " isClimateNormal=${day.isClimateNormal}")
            canvas.drawLine(forecastX, fHighY, forecastX, effectiveFLowY, overlayPaint)
            overlayPaint.shader = null
            onBarDrawn?.invoke(BarDrawnDebug(day.date, "FORECAST_OVERLAY", fHighY, effectiveFLowY, forecastX, overlayPaint.color))
        }

        // High Temp Label
        if (day.high != null) {
            val displayHigh = if (day.isToday) {
                listOfNotNull(day.high, day.forecastHigh, day.trueActualHigh).maxOrNull() ?: day.high
            } else {
                day.high
            }
            val highLabel = formatTempLabel(displayHigh, day.isToday || day.isPast)
            val y = highY ?: (lowY?.let { it - dpToPx(context, MIN_BAR_HEIGHT_DP) } ?: 0f)
            val labelY = if (day.isToday) {
                val absoluteHigh = listOfNotNull(day.high, day.forecastHigh, day.trueActualHigh).maxOrNull() ?: 0f
                layout.graphTop + layout.graphHeight * (1 - (absoluteHigh - layout.minTemp) / layout.tempRange)
            } else y
            val tempPaint = if (day.isToday) paints.todayTempTextPaint else paints.tempTextPaint
            canvas.drawText(highLabel, centerX, labelY - dpToPx(context, 6f * layout.scaleFactor), tempPaint)
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
        val minBarHeight = dpToPx(context, MIN_BAR_HEIGHT_DP)
        
        // Observed (Center)
        val obsHighY = highY ?: (lowY?.let { it - minBarHeight } ?: 0f)
        val obsLowY = lowY ?: (highY?.let { it + minBarHeight } ?: 0f)
        val effectiveObsLowY = if (kotlin.math.abs(obsHighY - obsLowY) < minBarHeight) obsHighY + minBarHeight else obsLowY
        
        // Snapshot (Left)
        day.snapshotHigh?.let { sHigh ->
            day.snapshotLow?.let { sLow ->
                val sHighY = layout.graphTop + layout.graphHeight * (1 - (sHigh - layout.minTemp) / layout.tempRange)
                val sLowY = layout.graphTop + layout.graphHeight * (1 - (sLow - layout.minTemp) / layout.tempRange)
                val effectiveSLowY = if (kotlin.math.abs(sHighY - sLowY) < minBarHeight) sHighY + minBarHeight else sLowY
                canvas.drawLine(centerX - layout.tripleBarOffset, sHighY, centerX - layout.tripleBarOffset, effectiveSLowY, paints.todaySnapshotYellowPaint)
            }
        }

        // Forecast (Right)
        val fHigh = day.forecastHigh ?: day.high ?: 0f
        val fLow = day.forecastLow ?: day.low ?: 0f
        val fHighY = layout.graphTop + layout.graphHeight * (1 - (fHigh - layout.minTemp) / layout.tempRange)
        val fLowY = layout.graphTop + layout.graphHeight * (1 - (fLow - layout.minTemp) / layout.tempRange)
        val effectiveFLowY = if (kotlin.math.abs(fHighY - fLowY) < minBarHeight) fHighY + minBarHeight else fLowY
        
        paints.todayForecastBluePaint.color = WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, isNight = false)
        if (day.isMixed && day.iconRes != null) {
            paints.todayForecastBluePaint.shader = WeatherConditionColors.forecastBarGradient(day.iconRes, fHighY, effectiveFLowY)
        }
        canvas.drawLine(centerX + layout.tripleBarOffset, fHighY, centerX + layout.tripleBarOffset, effectiveFLowY, paints.todayForecastBluePaint)
        paints.todayForecastBluePaint.shader = null

        // Draw Observed Bar and Bulb
        canvas.drawLine(centerX, obsHighY, centerX, effectiveObsLowY, paints.todayObservedRedPaint)
        // Repositioned bulb: shift down by half radius
        canvas.drawCircle(centerX, effectiveObsLowY + (layout.bulbRadius * 0.5f), layout.bulbRadius, paints.todayObservedRedBulbPaint)
        
        // Draw Ghost Bar (True Actual High) if it is higher than the current top of the observed bar
        day.trueActualHigh?.let { trueHigh ->
            val obsHighTemp = day.high ?: 0f
            if (trueHigh > obsHighTemp) {
                val ghostHighY = layout.graphTop + layout.graphHeight * (1 - (trueHigh - layout.minTemp) / layout.tempRange)
                canvas.drawLine(centerX, ghostHighY, centerX, obsHighY, paints.todayObservedGhostPaint)
                onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY_GHOST", ghostHighY, obsHighY, centerX, paints.todayObservedGhostPaint.color))
            }
        }
        
        onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY", obsHighY, effectiveObsLowY, centerX, paints.todayObservedRedPaint.color))
    }

    private fun drawDailyRainLabel(
        canvas: Canvas,
        context: Context,
        day: DayData,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)?,
    ) {
        val label = day.dailyRainLabelText ?: return
        val rainText = label
        val prob = (day.dailyPrecipProbability ?: 0) / 100f
        val scale = 1.0f - RAIN_FONT_SCALE_K * (1.0f - prob) * (day.daysFromToday / RAIN_FONT_SCALE_MAX_DAYS)
        val clampedScale = scale.coerceAtLeast(MIN_RAIN_FONT_SCALE)
        val scaledTextSize = paints.rainTextPaint.textSize * clampedScale
        val scaledTextSizeDp = scaledTextSize / context.resources.displayMetrics.density
        Log.d(TAG, "rainFont: date=${day.date} daysFromToday=${day.daysFromToday} prob=${day.dailyPrecipProbability}% rawScale=$scale clampedScale=$clampedScale probFraction=$prob baseTextSize=${paints.rainTextPaint.textSize}px finalTextSize=${scaledTextSize}px (${scaledTextSizeDp}dp) density=${context.resources.displayMetrics.density}")

        // Fix: Use a local Paint copy to avoid thread-safety issues with the shared PaintSet
        val localRainPaint = Paint(paints.rainTextPaint).apply {
            textSize = scaledTextSize
        }

        val textWidth = localRainPaint.measureText(rainText)
        val maxTextWidth = layout.dayWidth - dpToPx(context, 4f * layout.scaleFactor)
        if (textWidth > maxTextWidth) {
            Log.d(TAG, "rainLabel skipped: text too wide: date=${day.date} textWidth=${textWidth}px maxWidth=${maxTextWidth}px dayWidth=${layout.dayWidth}px label=\"$rainText\"")
            return
        }

        val metrics = localRainPaint.fontMetrics
        val topMargin = dpToPx(context, 2f * layout.scaleFactor)
        val spacing = dpToPx(context, 10f * layout.scaleFactor)
        val bottomLimit = layout.heightPx - layout.dayLabelHeight - dpToPx(context, 2f * layout.scaleFactor)

        resolveHighLabelBaseline(context, day, layout)?.let { highBaseline ->
            val aboveBaseline = highBaseline - spacing
            if (aboveBaseline + metrics.ascent >= topMargin) {
                canvas.drawText(rainText, centerX, aboveBaseline, localRainPaint)
                onRainLabelDrawn?.invoke(RainLabelDrawnDebug(day.date, rainText, "ABOVE_HIGH", centerX, aboveBaseline))
                return
            }
        }

        resolveLowLabelBaseline(context, day, layout)?.let { lowBaseline ->
            val belowBaseline = lowBaseline + spacing - metrics.ascent
            if (belowBaseline + metrics.descent <= bottomLimit) {
                canvas.drawText(rainText, centerX, belowBaseline, localRainPaint)
                onRainLabelDrawn?.invoke(RainLabelDrawnDebug(day.date, rainText, "BELOW_LOW", centerX, belowBaseline))
                return
            }
            Log.d(TAG, "rainLabel skipped: below-low overflow: date=${day.date} belowBaseline=$belowBaseline bottomLimit=$bottomLimit descent=${metrics.descent} overflow=${belowBaseline + metrics.descent - bottomLimit}px")
        }

        val highBaseline = resolveHighLabelBaseline(context, day, layout)
        if (highBaseline == null) {
            Log.d(TAG, "rainLabel skipped: no high baseline (null high temp): date=${day.date} high=${day.high}")
        } else {
            val aboveBaseline = highBaseline - spacing
            Log.d(TAG, "rainLabel skipped: above-high overflow: date=${day.date} aboveBaseline=$aboveBaseline topMargin=$topMargin ascent=${metrics.ascent} overflow=${topMargin - (aboveBaseline + metrics.ascent)}px")
        }
    }

    private fun resolveHighLabelBaseline(
        context: Context,
        day: DayData,
        layout: LayoutInfo,
    ): Float? {
        val highY = day.high?.let {
            layout.graphTop + layout.graphHeight * (1 - (it - layout.minTemp) / layout.tempRange)
        } ?: return null
        val labelY =
            if (day.isToday) {
                val absoluteHigh = listOfNotNull(day.high, day.forecastHigh, day.trueActualHigh).maxOrNull() ?: return null
                layout.graphTop + layout.graphHeight * (1 - (absoluteHigh - layout.minTemp) / layout.tempRange)
            } else {
                highY
            }
        return labelY - dpToPx(context, 6f * layout.scaleFactor)
    }

    private fun resolveLowLabelBaseline(
        context: Context,
        day: DayData,
        layout: LayoutInfo,
    ): Float? {
        val lowTemp = resolveBottomStackLow(day) ?: return null
        val lowY = layout.graphTop + layout.graphHeight * (1 - (lowTemp - layout.minTemp) / layout.tempRange)
        val iconY = lowY + dpToPx(context, 3f)
        return iconY + layout.iconSize + layout.tempLabelHeight + dpToPx(context, 1f)
    }

    internal fun resolveBottomStackLow(day: DayData): Float? = day.bottomStackLow ?: day.low

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
    }

    private fun spToPx(context: Context, sp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
    }

    private fun resolveTopDateTextSizePx(context: Context, layout: LayoutInfo): Float {
        val widthDp = layout.widthPx / context.resources.displayMetrics.density
        val sizeSp = if (widthDp < NARROW_WIDGET_WIDTH_DP) TOP_DATE_TEXT_SIZE_NARROW_SP else TOP_DATE_TEXT_SIZE_WIDE_SP
        return spToPx(context, sizeSp)
    }

    internal fun computeDayLabelWidthScale(dayWidthDp: Float): Float {
        return (dayWidthDp / BASE_DAY_WIDTH_DP).coerceIn(MIN_DAY_LABEL_WIDTH_SCALE, MAX_DAY_LABEL_WIDTH_SCALE)
    }

    private fun formatTempLabel(actual: Float, allowDecimals: Boolean): String {
        if (!allowDecimals) return "${actual.roundToInt()}°"
        val rounded = actual.roundToInt()
        return if (kotlin.math.abs(actual - rounded) < 0.01f) "$rounded°" else String.format("%.1f°", actual)
    }
}
