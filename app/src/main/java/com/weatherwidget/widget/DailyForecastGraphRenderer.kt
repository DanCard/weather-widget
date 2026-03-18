package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import kotlin.math.roundToInt

object DailyForecastGraphRenderer {
    private const val DAY_LABEL_SIZE_MULTIPLIER = 1.4f
    private const val BASE_DAY_WIDTH_DP = 70f
    private const val MIN_DAY_LABEL_WIDTH_SCALE = 0.96f
    private const val MAX_DAY_LABEL_WIDTH_SCALE = 1.04f

    /**
     * Fired once for each bar drawn, for testing and debugging.
     *
     * @param date      ISO date string of the day (e.g. "2024-01-15")
     * @param barType   "TODAY", "HISTORY", "FUTURE", "CLIMATE", "FORECAST_OVERLAY"
     * @param highY     canvas Y of the top of the bar (lower value = higher on screen)
     * @param lowY      canvas Y of the bottom of the bar
     * @param centerX   canvas X center of the day column
     */
    data class BarDrawnDebug(
        val date: String,
        val barType: String,
        val highY: Float,
        val lowY: Float,
        val centerX: Float,
        val color: Int,
    )

    data class DayData(
        val date: String, // ISO date string (e.g. "2024-01-15")
        val label: String,
        val high: Float?,
        val low: Float?,
        val iconRes: Int? = null,
        val isSunny: Boolean = false,
        val isRainy: Boolean = false,
        val isMixed: Boolean = false,
        val isToday: Boolean = false,
        val isPast: Boolean = false, // Is this a historical day?
        val isClimateNormal: Boolean = false, // Is this long-range climate data?
        val isSourceGapFallback: Boolean = false, // Is this day shown from the generic gap filler?
        val forecastHigh: Float? = null, // Single forecast
        val forecastLow: Float? = null, // Single forecast
        val rainSummary: String? = null, // e.g. "2pm" — start of first rain window
        val dailyPrecipProbability: Int? = null, // From ForecastEntity, used for click routing
        val hasRainForecast: Boolean = false, // Unsuppressed rain signal for click routing
        val columnIndex: Int? = null, // Inherent position in the grid
        val isTodayForecastFallback: Boolean = false,
        val snapshotHigh: Float? = null,
        val snapshotLow: Float? = null,
    )

    fun renderGraph(
        context: Context,
        days: List<DayData>,
        widthPx: Int,
        heightPx: Int,
        bitmapScale: Float = 1f,
        numColumns: Int = 0, // Pass expected columns to avoid scaling issues with gaps
        onBarDrawn: ((BarDrawnDebug) -> Unit)? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (days.isEmpty()) return bitmap

        // Use passed numColumns or fallback to list size
        val columns = if (numColumns > 0) numColumns else days.size

        // Find temperature range for scaling (ignore nulls)
        val allTemps = days.flatMap { listOfNotNull(it.high, it.low, it.forecastHigh, it.forecastLow, it.snapshotHigh, it.snapshotLow) }
        // Remove buffer so the lowest temp hits the bottom exactly
        val minTemp = allTemps.minOrNull() ?: 0f
        val maxTemp = allTemps.maxOrNull() ?: 100f
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)
        
        android.util.Log.d("DailyGraphRenderer", "renderGraph: days=${days.size}, minTemp=$minTemp, maxTemp=$maxTemp, tempRange=$tempRange, widthPx=$widthPx, heightPx=$heightPx")

        // Scale factor based on widget dimensions
        val density = context.resources.displayMetrics.density
        val widthDp = widthPx / density
        val heightDp = heightPx / density

        // Width-based scale factor: ensure day labels fit (base ~70dp per day)
        val dayWidthDp = widthDp / columns
        val widthScaleFactor = (dayWidthDp / BASE_DAY_WIDTH_DP).coerceIn(1.0f, 1.2f) // Cap at 1.2x
        val dayLabelWidthScale = computeDayLabelWidthScale(dayWidthDp)

        // Height-based scale factor: scale fonts up with widget height
        val heightScaleFactor =
            when {
                heightDp < 150f -> 1.0f // 2 rows or less: baseline
                heightDp < 250f -> 1.0f // 3 rows: keep baseline
                else -> 1.05f // 4+ rows: only 5% bigger
            }

        // Combined scale factor for layout elements
        val scaleFactor = widthScaleFactor

        // Layout constants (scaled)
        val horizontalPadding = dpToPx(context, 0f) // Maximize width (was 4f)
        // Allocate space on top for the current temp and weather icon
        val topPadding = dpToPx(context, 24f * scaleFactor)

        // Scale text sizes with widget height
        val baseDayLabelSize = 12.5f
        val baseTempLabelSize = 10.5f

        // Icon size
        val iconSizeDp = 16f
        val iconSize = dpToPx(context, iconSizeDp).toInt()

        // Calculate layout height components
        val dayLabelScale = bitmapScale.coerceIn(0.5f, 1f) * dayLabelWidthScale
        val dayLabelHeight = dpToPx(context, baseDayLabelSize * dayLabelScale * DAY_LABEL_SIZE_MULTIPLIER)
        val tempLabelHeight = dpToPx(context, baseTempLabelSize * heightScaleFactor)

        // Stack Height: Low Temp Label + Icon + Padding (Space attached to the BAR)
        val attachedStackHeight = tempLabelHeight + iconSize + dpToPx(context, 4f)

        // Graph area calculations
        val graphTop = topPadding
        val graphBottom = heightPx - dayLabelHeight - attachedStackHeight
        val graphHeight = graphBottom - graphTop

        val dayWidth = (widthPx - 2 * horizontalPadding) / columns
        val barWidth = dpToPx(context, 2.2f * scaleFactor)

        // Triple line for today
        val tripleBarWidth = dpToPx(context, 1.4f * scaleFactor)
        val tripleBarOffset = dpToPx(context, 1.8f * scaleFactor)

        // Paints
        val barPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5AC8FA")
                strokeWidth = barWidth
                strokeCap = Paint.Cap.ROUND
            }

        val todayBarPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF9F0A")
                strokeWidth = barWidth
                strokeCap = Paint.Cap.ROUND
            }

        val todayTripleYellowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD60A")
                strokeWidth = tripleBarWidth
                strokeCap = Paint.Cap.ROUND
            }

        val todayTripleOrangePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF9F0A")
                strokeWidth = tripleBarWidth
                strokeCap = Paint.Cap.ROUND
            }

        val todayTripleBluePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5AC8FA")
                strokeWidth = tripleBarWidth
                strokeCap = Paint.Cap.ROUND
            }

        val historyBarPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD60A")
                strokeWidth = barWidth * 1.1f
                strokeCap = Paint.Cap.ROUND
            }

        val forecastBarPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5AC8FA")
                strokeWidth = barWidth * 0.8f
                strokeCap = Paint.Cap.ROUND
            }

        val climateOverlayBarPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5AC8FA")
                alpha = 80 // Semi-transparent
                strokeWidth = barWidth * 0.8f
                strokeCap = Paint.Cap.ROUND
            }

        val gapFallbackBarPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#34C759")
                strokeWidth = barWidth
                strokeCap = Paint.Cap.ROUND
            }

        val forecastBarOffset = barWidth * 1.2f

        val dayLabelTextSize = dpToPx(context, baseDayLabelSize * dayLabelScale * DAY_LABEL_SIZE_MULTIPLIER)
        val tempLabelTextSize = dpToPx(context, baseTempLabelSize * heightScaleFactor)

        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#AAAAAA")
                textSize = dayLabelTextSize
                textAlign = Paint.Align.CENTER
            }

        val todayTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFEACC")
                textSize = dayLabelTextSize
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

        val tempTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFFFFF")
                textSize = tempLabelTextSize
                textAlign = Paint.Align.CENTER
            }

        val todayTempTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFEACC")
                textSize = tempLabelTextSize
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

        val rainTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5AC8FA")
                textSize = dpToPx(context, 9f * scaleFactor)
                textAlign = Paint.Align.CENTER
            }

        val iconPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.parseColor("#AAAAAA"), PorterDuff.Mode.SRC_IN)
            }

        val todayIconPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.parseColor("#FFEACC"), PorterDuff.Mode.SRC_IN)
            }

        var firstRainShown = false
        
        days.forEachIndexed { index, day ->
            val columnIndex = day.columnIndex ?: index
            val centerX = horizontalPadding + dayWidth * columnIndex + dayWidth / 2f
            val dayLabelY = heightPx - 3f

            val labelPaint = if (day.isToday) todayTextPaint else textPaint
            canvas.drawText(day.label, centerX, dayLabelY, labelPaint)

            val highY = day.high?.let {
                graphTop + graphHeight * (1 - (it - minTemp).toFloat() / tempRange)
            }

            val lowTemp = day.low
            val lowY = lowTemp?.let {
                graphTop + graphHeight * (1 - (it - minTemp).toFloat() / tempRange)
            }

            if (lowY != null) {
                val iconY = lowY + dpToPx(context, 3f)
                if (day.iconRes != null) {
                    val drawable = androidx.core.content.ContextCompat.getDrawable(context, day.iconRes)
                    if (drawable != null) {
                        val iconX = centerX - iconSize / 2f
                        drawable.setBounds(iconX.toInt(), iconY.toInt(), (iconX + iconSize).toInt(), (iconY + iconSize).toInt())
                        if (!day.isRainy && !day.isMixed) {
                            val tint = if (day.isSunny) {
                                Color.parseColor("#FFD60A")
                            } else {
                                Color.parseColor("#AAAAAA")
                            }
                            drawable.setTint(tint)
                        }
                        drawable.draw(canvas)
                    }
                }

                val lowTempY = iconY + iconSize + tempLabelHeight + dpToPx(context, 1f)
                val lowLabelText = formatTempLabel(lowTemp!!, day.isToday || day.isPast)
                val tempPaint = if (day.isToday) todayTempTextPaint else tempTextPaint
                canvas.drawText(lowLabelText, centerX, lowTempY, tempPaint)

                if (!day.rainSummary.isNullOrEmpty() && !firstRainShown) {
                    val rainTextY = lowTempY + dpToPx(context, 10f * scaleFactor)
                    canvas.drawText("💧 ${day.rainSummary}", centerX, rainTextY, rainTextPaint)
                    firstRainShown = true
                }
            }

            if (day.high == null && day.low == null) return@forEachIndexed

            val paint = when {
                day.isToday -> todayBarPaint
                day.isPast -> historyBarPaint
                day.isSourceGapFallback -> gapFallbackBarPaint
                else -> barPaint
            }

            if (day.high != null && day.low != null) {
                if (day.isToday) {
                    val sHighY = (day.snapshotHigh ?: day.forecastHigh)?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: highY!!
                    val sLowY = (day.snapshotLow ?: day.forecastLow)?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: lowY!!
                    
                    val fHighY = day.forecastHigh?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: highY!!
                    val fLowY = day.forecastLow?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: lowY!!

                    val minBarHeight = dpToPx(context, 6f)
                    val effectiveSLowY = if (kotlin.math.abs(sHighY - sLowY) < minBarHeight) sHighY + minBarHeight else sLowY
                    val effectiveLowY = if (kotlin.math.abs(highY!! - lowY!!) < minBarHeight) highY!! + minBarHeight else lowY!!
                    val effectiveFLowY = if (kotlin.math.abs(fHighY - fLowY) < minBarHeight) fHighY + minBarHeight else fLowY

                    android.util.Log.d("DailyGraphRenderer", "  Drawing Day ${day.date}: [TODAY] sHighY=$sHighY, highY=$highY, fHighY=$fHighY, minTemp=$minTemp, maxTemp=$maxTemp")

                    canvas.drawLine(centerX - tripleBarOffset, sHighY, centerX - tripleBarOffset, effectiveSLowY, todayTripleYellowPaint)
                    canvas.drawLine(centerX, highY!!, centerX, effectiveLowY, todayTripleOrangePaint)
                    canvas.drawLine(centerX + tripleBarOffset, fHighY, centerX + tripleBarOffset, effectiveFLowY, todayTripleBluePaint)
                    onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY", highY!!, effectiveLowY, centerX, todayTripleOrangePaint.color))
                } else {
                    // Ensure at least a small bar if high == low (6dp minimum height)
                    val minBarHeight = dpToPx(context, 6f)
                    val effectiveLowY = if (kotlin.math.abs(highY!! - lowY!!) < minBarHeight) highY + minBarHeight else lowY
                    
                    android.util.Log.d("DailyGraphRenderer", "  Drawing Day ${day.date}: [HIST/FUT] highY=$highY, lowY=$effectiveLowY, centerX=$centerX")
                    
                    canvas.drawLine(centerX, highY!!, centerX, effectiveLowY, paint)
                    val barType = if (day.isPast) "HISTORY" else "FUTURE"
                    onBarDrawn?.invoke(BarDrawnDebug(day.date, barType, highY, effectiveLowY, centerX, paint.color))
                }
            } else if (highY != null) {
                val minBarHeight = dpToPx(context, 6f)
                if (day.isToday) {
                    val sHighY = (day.snapshotHigh ?: day.forecastHigh)?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: highY!!
                    val sLowY = (day.snapshotLow ?: day.forecastLow)?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: (highY!! + minBarHeight)
                    
                    val fHighY = day.forecastHigh?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: highY!!
                    val fLowY = day.forecastLow?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: highY!!

                    val effectiveSLowY = if (kotlin.math.abs(sHighY - sLowY) < minBarHeight) sHighY + minBarHeight else sLowY
                    val effectiveFLowY = if (kotlin.math.abs(fHighY - fLowY) < minBarHeight) fHighY + minBarHeight else fLowY
                    
                    canvas.drawLine(centerX - tripleBarOffset, sHighY, centerX - tripleBarOffset, effectiveSLowY, todayTripleYellowPaint)
                    canvas.drawLine(centerX, highY!!, centerX, highY!! + minBarHeight, todayTripleOrangePaint)
                    canvas.drawLine(centerX + tripleBarOffset, fHighY, centerX + tripleBarOffset, effectiveFLowY, todayTripleBluePaint)
                    onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY", highY!!, highY!! + minBarHeight, centerX, todayTripleOrangePaint.color))
                } else {
                    val effectiveLowY = highY!! + minBarHeight
                    canvas.drawLine(centerX, highY!!, centerX, effectiveLowY, paint)
                    val barType = if (day.isPast) "HISTORY" else "FUTURE"
                    onBarDrawn?.invoke(BarDrawnDebug(day.date, barType, highY!!, effectiveLowY, centerX, paint.color))
                }
            } else if (lowY != null) {
                val minBarHeight = dpToPx(context, 6f)
                if (day.isToday) {
                    val sHighY = (day.snapshotHigh ?: day.forecastHigh)?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: (lowY!! - minBarHeight)
                    val sLowY = (day.snapshotLow ?: day.forecastLow)?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: lowY!!
                    
                    val fHighY = day.forecastHigh?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: lowY!!
                    val fLowY = day.forecastLow?.let {
                        graphTop + graphHeight * (1 - (it - minTemp) / tempRange)
                    } ?: lowY!!
                    
                    val effectiveSHighY = if (kotlin.math.abs(sHighY - sLowY) < minBarHeight) sLowY!! - minBarHeight else sHighY
                    val effectiveFHighY = if (kotlin.math.abs(fHighY - lowY!!) < minBarHeight) lowY!! - minBarHeight else fHighY

                    canvas.drawLine(centerX - tripleBarOffset, effectiveSHighY, centerX - tripleBarOffset, sLowY!!, todayTripleYellowPaint)
                    canvas.drawLine(centerX, lowY!! - minBarHeight, centerX, lowY!!, todayTripleOrangePaint)
                    canvas.drawLine(centerX + tripleBarOffset, fHighY, centerX + tripleBarOffset, fLowY!!, todayTripleBluePaint)
                    onBarDrawn?.invoke(BarDrawnDebug(day.date, "TODAY", lowY!! - minBarHeight, lowY!!, centerX, todayTripleOrangePaint.color))
                } else {
                    val effectiveHighY = lowY!! - minBarHeight
                    canvas.drawLine(centerX, effectiveHighY, centerX, lowY!!, paint)
                    val barType = if (day.isPast) "HISTORY" else "FUTURE"
                    onBarDrawn?.invoke(BarDrawnDebug(day.date, barType, effectiveHighY, lowY!!, centerX, paint.color))
                }
            }

            if (day.forecastHigh != null && day.forecastLow != null && !day.isToday) {
                val fHighY = graphTop + graphHeight * (1 - (day.forecastHigh - minTemp).toFloat() / tempRange)
                val fLowY = graphTop + graphHeight * (1 - (day.forecastLow - minTemp).toFloat() / tempRange)
                
                val minBarHeight = dpToPx(context, 6f)
                val effectiveFLowY = if (kotlin.math.abs(fHighY - fLowY) < minBarHeight) fHighY + minBarHeight else fLowY
                
                val forecastX = centerX + forecastBarOffset
                val overlayPaint = if (day.isClimateNormal) climateOverlayBarPaint else forecastBarPaint
                
                android.util.Log.d("DailyGraphRenderer", "  Drawing Day ${day.date}: [FORECAST] fHighY=$fHighY, fLowY=$effectiveFLowY, centerX=$forecastX, isClimate=${day.isClimateNormal}")
                canvas.drawLine(forecastX, fHighY, forecastX, effectiveFLowY, overlayPaint)
                onBarDrawn?.invoke(BarDrawnDebug(day.date, "FORECAST_OVERLAY", fHighY, effectiveFLowY, forecastX, overlayPaint.color))
            }

            if (day.high != null) {
                val displayHigh = if (day.isToday && day.forecastHigh != null) maxOf(day.high, day.forecastHigh) else day.high
                val highLabel = formatTempLabel(displayHigh, day.isToday || day.isPast)
                highY?.let { y ->
                    val labelY = if (day.isToday && day.forecastHigh != null && day.forecastHigh > day.high) {
                        val fHighY = graphTop + graphHeight * (1 - (day.forecastHigh - minTemp).toFloat() / tempRange)
                        minOf(y, fHighY)
                    } else y
                    val tempPaint = if (day.isToday) todayTempTextPaint else tempTextPaint
                    canvas.drawText(highLabel, centerX, labelY - dpToPx(context, 6f * scaleFactor), tempPaint)
                }
            }
        }
        return bitmap
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
