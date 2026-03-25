package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import java.time.LocalDate
import kotlin.math.roundToInt

object DailyForecastGraphRenderer {
    private const val TAG = "DailyGraphRenderer"

    private const val DAY_LABEL_SIZE_MULTIPLIER = 2.1f
    private const val DAY_LABEL_TEXT_MULTIPLIER = 1.5f
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

    private var cachedPaintSet: PaintSet? = null
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
    )

    data class DayData(
        val date: LocalDate,
        val label: String,
        val high: Float?,
        val low: Float?,
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
        val hasRainForecast: Boolean = false,
        val columnIndex: Int? = null,
        val isTodayForecastFallback: Boolean = false,
        val snapshotHigh: Float? = null,
        val snapshotLow: Float? = null,
        val trueActualHigh: Float? = null,
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
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (days.isEmpty()) return bitmap

        val columns = if (numColumns > 0) numColumns else days.size
        val layout = computeLayout(context, days, widthPx, heightPx, columns, bitmapScale)
        val paints = getPaintSet(context, layout.scaleFactor, layout)

        android.util.Log.d(TAG, "renderGraph: days=${days.size}, minTemp=${layout.minTemp}, maxTemp=${layout.maxTemp}, widthPx=$widthPx, heightPx=$heightPx")

        var firstRainShown = false
        days.forEachIndexed { index, day ->
            val columnIndex = day.columnIndex ?: index
            val centerX = layout.horizontalPadding + layout.dayWidth * columnIndex + layout.dayWidth / 2f
            
            drawDayColumn(canvas, context, day, centerX, layout, paints, firstRainShown)
            if (!day.rainSummary.isNullOrEmpty()) firstRainShown = true
            
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
        val dayLabelHeight = dpToPx(context, 12.5f * dayLabelScale * DAY_LABEL_SIZE_MULTIPLIER)
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
                layout.dayLabelHeight / DAY_LABEL_SIZE_MULTIPLIER * DAY_LABEL_TEXT_MULTIPLIER,
            ),
            todayTextPaint = createTextPaint(
                Color.parseColor(COLOR_TODAY_TEXT),
                layout.dayLabelHeight / DAY_LABEL_SIZE_MULTIPLIER * DAY_LABEL_TEXT_MULTIPLIER,
                true,
            ),
            tempTextPaint = createTextPaint(Color.parseColor(COLOR_WHITE), layout.tempLabelHeight),
            todayTempTextPaint = createTextPaint(Color.parseColor(COLOR_TODAY_TEXT), layout.tempLabelHeight, true),
            rainTextPaint = createTextPaint(Color.parseColor(COLOR_FORECAST), dpToPx(context, 9f * scaleFactor)),
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

    private fun drawDayColumn(
        canvas: Canvas,
        context: Context,
        day: DayData,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        firstRainShown: Boolean
    ) {
        val labelPaint = if (day.isToday) paints.todayTextPaint else paints.textPaint
        canvas.drawText(day.label, centerX, layout.heightPx - 3f, labelPaint)

        val lowTemp = day.low
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

            if (!day.rainSummary.isNullOrEmpty() && !firstRainShown) {
                val rainTextY = lowTempY + dpToPx(context, 10f * layout.scaleFactor)
                canvas.drawText("\uD83D\uDCA7 ${day.rainSummary}", centerX, rainTextY, paints.rainTextPaint)
            }
        }
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
                else -> paints.barPaint
            }
            
            val minBarHeight = dpToPx(context, MIN_BAR_HEIGHT_DP)
            val hY = highY ?: (lowY?.let { it - minBarHeight } ?: 0f)
            val lY = lowY ?: (highY?.let { it + minBarHeight } ?: 0f)
            val effectiveLowY = if (kotlin.math.abs(hY - lY) < minBarHeight) hY + minBarHeight else lY

            canvas.drawLine(centerX, hY, centerX, effectiveLowY, paint)
            onBarDrawn?.invoke(BarDrawnDebug(day.date, if (day.isPast) "HISTORY" else "FUTURE", hY, effectiveLowY, centerX, paint.color))
        }

        // Forecast Overlay (only for non-today days)
        if (!day.isToday && day.forecastHigh != null && day.forecastLow != null) {
            val fHighY = layout.graphTop + layout.graphHeight * (1 - (day.forecastHigh - layout.minTemp) / layout.tempRange)
            val fLowY = layout.graphTop + layout.graphHeight * (1 - (day.forecastLow - layout.minTemp) / layout.tempRange)
            val minBarHeight = dpToPx(context, MIN_BAR_HEIGHT_DP)
            val effectiveFLowY = if (kotlin.math.abs(fHighY - fLowY) < minBarHeight) fHighY + minBarHeight else fLowY
            
            val forecastX = centerX + layout.forecastBarOffset
            val overlayPaint = if (day.isClimateNormal) paints.climateOverlayBarPaint else paints.forecastBarPaint
            canvas.drawLine(forecastX, fHighY, forecastX, effectiveFLowY, overlayPaint)
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
                val absoluteHigh = listOfNotNull(day.high, day.forecastHigh, day.trueActualHigh).maxOrNull() ?: day.high ?: 0f
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
        
        canvas.drawLine(centerX + layout.tripleBarOffset, fHighY, centerX + layout.tripleBarOffset, effectiveFLowY, paints.todayForecastBluePaint)

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

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
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
