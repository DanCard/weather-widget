package com.weatherwidget.widget

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import kotlin.math.roundToInt

object DailyForecastGraphRenderer {
    private const val DAY_LABEL_SIZE_MULTIPLIER = 1.4f

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
        val forecastHigh: Float? = null, // Single forecast
        val forecastLow: Float? = null, // Single forecast
        val rainSummary: String? = null, // e.g. "2pm" — start of first rain window
        val dailyPrecipProbability: Int? = null, // From WeatherEntity, used for click routing
        val hasRainForecast: Boolean = false, // Unsuppressed rain signal for click routing
    )

    fun renderGraph(
        context: Context,
        days: List<DayData>,
        widthPx: Int,
        heightPx: Int,
        bitmapScale: Float = 1f,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (days.isEmpty()) return bitmap

        // Find temperature range for scaling (ignore nulls)
        val allTemps = days.flatMap { listOfNotNull(it.high, it.low, it.forecastHigh, it.forecastLow) }
        // Remove buffer so the lowest temp hits the bottom exactly
        val minTemp = (allTemps.minOrNull() ?: 0).toFloat()
        val maxTemp = (allTemps.maxOrNull() ?: 100).toFloat()
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)

        // Scale factor based on widget dimensions
        val density = context.resources.displayMetrics.density
        val widthDp = widthPx / density
        val heightDp = heightPx / density

        // Width-based scale factor: ensure day labels fit (base ~70dp per day)
        val baseDayWidthDp = 70f
        val dayWidthDp = widthDp / days.size
        val widthScaleFactor = (dayWidthDp / baseDayWidthDp).coerceIn(1.0f, 1.2f) // Cap at 1.2x

        // Height-based scale factor: scale fonts up with widget height
        // Base height ~136dp (2 rows), scale up slightly
        val baseHeightDp = 136f // 2-row widget height
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
        val bottomPadding = dpToPx(context, 0f) // No bottom padding

        // Scale text sizes with widget height
        val baseDayLabelSize = 12.5f
        val baseTempLabelSize = 11.5f

        // Icon size
        val iconSizeDp = 16f
        val iconSize = dpToPx(context, iconSizeDp).toInt()

        // Calculate layout height components
        val dayLabelScale = bitmapScale.coerceIn(0.5f, 1f)
        val dayLabelHeight = dpToPx(context, baseDayLabelSize * dayLabelScale * DAY_LABEL_SIZE_MULTIPLIER)
        val tempLabelHeight = dpToPx(context, baseTempLabelSize * heightScaleFactor)

        // Log font and layout sizing info
        android.util.Log.d(
            "TemperatureGraph",
            "Widget: ${widthPx}px × ${heightPx}px (${widthDp.toInt()}dp × ${heightDp.toInt()}dp) | " +
                "horizontalPadding=${horizontalPadding}px, dayWidth=${(widthPx - 2 * horizontalPadding) / days.size}px | " +
                "heightScaleFactor=$heightScaleFactor | baseDayLabel=$baseDayLabelSize, finalDayLabel=${baseDayLabelSize * dayLabelScale}dp",
        )

        // Stack Height: Low Temp Label + Icon + Padding (Space attached to the BAR)
        // Added 4dp padding (3dp bar-to-icon + 1dp icon-to-text) for breathing room
        val attachedStackHeight = tempLabelHeight + iconSize + dpToPx(context, 4f)

        // Graph area calculations
        val graphTop = topPadding
        // Reserve minimal space - overlap logic handled in drawing
        val graphBottom = heightPx - dayLabelHeight - attachedStackHeight
        val graphHeight = graphBottom - graphTop

        val dayWidth = (widthPx - 2 * horizontalPadding) / days.size
        val barWidth = dpToPx(context, 2.2f * scaleFactor) // Even thinner bars (was 3f)
        val capHeight = dpToPx(context, 2f * scaleFactor)

        // Triple line for today
        val tripleBarWidth = dpToPx(context, 1.1f * scaleFactor)
        val tripleBarOffset = dpToPx(context, 1.5f * scaleFactor)

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

        // Triple line paints for today
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

        // History bar - slight bold
        val historyBarPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD60A") // Yellow for past days (actual)
                strokeWidth = barWidth * 1.1f // Slightly bold (was 1.8x)
                strokeCap = Paint.Cap.ROUND
            }

        val capPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5AC8FA")
                strokeWidth = barWidth + dpToPx(context, 4f * scaleFactor)
                strokeCap = Paint.Cap.BUTT
            }

        val todayCapPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF9F0A")
                strokeWidth = barWidth + dpToPx(context, 4f * scaleFactor)
                strokeCap = Paint.Cap.BUTT
            }

        val historyCapPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD60A") // Yellow caps for history
                strokeWidth = (barWidth * 1.1f) + dpToPx(context, 4f * scaleFactor)
                strokeCap = Paint.Cap.BUTT
            }

        // Climate Normal bar - Green for long-range averages
        val climateNormalBarPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#34C759")
                strokeWidth = barWidth
                strokeCap = Paint.Cap.ROUND
            }

        val climateNormalCapPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#34C759")
                strokeWidth = barWidth + dpToPx(context, 4f * scaleFactor)
                strokeCap = Paint.Cap.BUTT
            }

        val dayLabelTextSize = dpToPx(context, baseDayLabelSize * dayLabelScale * DAY_LABEL_SIZE_MULTIPLIER)
        val tempLabelTextSize = dpToPx(context, baseTempLabelSize * heightScaleFactor)

        android.util.Log.d(
            "TemperatureGraph",
            "Font sizes: dayLabel=${dayLabelTextSize}px (${dayLabelTextSize / density}dp), tempLabel=${tempLabelTextSize}px (${tempLabelTextSize / density}dp)",
        )

        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#AAAAAA")
                textSize = dayLabelTextSize
                textAlign = Paint.Align.CENTER
            }

        // Highlight today's label with orange tint and bold text
        val todayTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFEACC") // Very Light Orange (averaged with white)
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
                color = Color.parseColor("#FFEACC") // Very Light Orange (averaged with white)
                textSize = tempLabelTextSize
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

        // Rain summary text paint - small blue text below temp
        val rainTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5AC8FA")
                textSize = dpToPx(context, 9f * scaleFactor)
                textAlign = Paint.Align.CENTER
            }

        // Forecast bar paint (blue line showing what was predicted for past days)
        val forecastBarPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#5AC8FA") // Blue for forecast comparison
                strokeWidth = barWidth * 0.8f // More visible (was 0.5f)
                strokeCap = Paint.Cap.ROUND
            }

        val forecastBarOffset = barWidth * 1.2f // Offset for forecast bar from main bar

        // Icon paints
        val iconPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter =
                    PorterDuffColorFilter(
                        Color.parseColor("#AAAAAA"),
                        PorterDuff.Mode.SRC_IN,
                    )
            }
        val sunnyIconPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter =
                    PorterDuffColorFilter(
                        Color.parseColor("#FFD60A"),
                        PorterDuff.Mode.SRC_IN,
                    )
            }

        // Track if we've already shown the first rain indicator
        var firstRainShown = false
        
        // Draw each day
        days.forEachIndexed { index, day ->
            val centerX = horizontalPadding + dayWidth * index + dayWidth / 2

            // 1. Draw Day Label (Fixed at absolute Bottom)
            val dayLabelY = heightPx - 3f // Experiment: move up 3 pixels

            val labelPaint = if (day.isToday) todayTextPaint else textPaint
            canvas.drawText(day.label, centerX, dayLabelY, labelPaint)

            // Calculate Y positions first
            val highY =
                day.high?.let {
                    graphTop + graphHeight * (1 - (it - minTemp).toFloat() / tempRange)
                }

            val lowTemp = day.low
            val lowY =
                lowTemp?.let {
                    graphTop + graphHeight * (1 - (it - minTemp).toFloat() / tempRange)
                }

            // 2. Draw attached elements with overlap
            if (lowY != null) {
                // Icon sits immediately below the bar (3dp padding for breathing room)
                val iconY = lowY + dpToPx(context, 3f)

                // Draw Icon
                if (day.iconRes != null) {
                    val drawable =
                        androidx.core.content.ContextCompat.getDrawable(context, day.iconRes)

                    if (drawable != null) {
                        val iconX = centerX - iconSize / 2f

                        drawable.setBounds(
                            iconX.toInt(),
                            iconY.toInt(),
                            (iconX + iconSize).toInt(),
                            (iconY + iconSize).toInt(),
                        )

                        if (!day.isRainy && !day.isMixed) {
                            // Rain/storm/mixed icons keep native vector colors
                            val tint = if (day.isSunny) Color.parseColor("#FFD60A") else Color.parseColor("#AAAAAA")
                            drawable.setTint(tint)
                        }

                        drawable.draw(canvas)
                    }
                }

                // Low Temp Label sits below the Icon
                // 1dp padding for breathing room (reversing the previous overlap)
                val lowTempY = iconY + iconSize + tempLabelHeight + dpToPx(context, 1f)

                // Draw Low Temp Label
                val lowLabel =
                    formatTempLabel(lowTemp!!, day.isToday)
                val tempPaint = if (day.isToday) todayTempTextPaint else tempTextPaint
                canvas.drawText(lowLabel, centerX, lowTempY, tempPaint)

                // Draw rain summary only for the first day with rain
                // (intent is to show when rain will start, not all rainy days)
                if (!day.rainSummary.isNullOrEmpty() && !firstRainShown) {
                    val rainTextY = lowTempY + dpToPx(context, 10f * scaleFactor)
                    canvas.drawText("💧 ${day.rainSummary}", centerX, rainTextY, rainTextPaint)
                    firstRainShown = true
                }
            } else {
                // Fallback
            }

            // Skip drawing bar if BOTH high and low are missing
            if (day.high == null && day.low == null) return@forEachIndexed

            // Determine paint style
            val paint =
                when {
                    day.isToday -> todayBarPaint
                    day.isPast -> historyBarPaint
                    day.isClimateNormal -> climateNormalBarPaint
                    else -> barPaint // Future days
                }
            val cap =
                when {
                    day.isToday -> todayCapPaint
                    day.isPast -> historyCapPaint
                    day.isClimateNormal -> climateNormalCapPaint
                    else -> capPaint
                }

            // Draw based on available data
            if (highY != null && lowY != null) {
                if (day.isToday) {
                    // Triple line for today: Yellow (history color), Orange (today color), Blue (forecast color)
                    val forecastHighY = day.forecastHigh?.let {
                        graphTop + graphHeight * (1 - (it - minTemp).toFloat() / tempRange)
                    } ?: highY
                    val forecastLowY = day.forecastLow?.let {
                        graphTop + graphHeight * (1 - (it - minTemp).toFloat() / tempRange)
                    } ?: lowY

                    canvas.drawLine(centerX - tripleBarOffset, highY, centerX - tripleBarOffset, lowY, todayTripleYellowPaint)
                    canvas.drawLine(centerX, highY, centerX, lowY, todayTripleOrangePaint)
                    canvas.drawLine(centerX + tripleBarOffset, forecastHighY, centerX + tripleBarOffset, forecastLowY, todayTripleBluePaint)
                } else {
                    // Full data: draw vertical bar (no caps as requested)
                    canvas.drawLine(centerX, highY, centerX, lowY, paint)
                }
            } else if (highY != null) {
                if (day.isToday) {
                    val forecastHighY = day.forecastHigh?.let {
                        graphTop + graphHeight * (1 - (it - minTemp).toFloat() / tempRange)
                    } ?: highY

                    canvas.drawLine(centerX - tripleBarOffset, highY, centerX - tripleBarOffset, highY, todayTripleYellowPaint)
                    canvas.drawLine(centerX, highY, centerX, highY, todayTripleOrangePaint)
                    canvas.drawLine(centerX + tripleBarOffset, forecastHighY, centerX + tripleBarOffset, forecastHighY, todayTripleBluePaint)
                } else {
                    // Only high temp: draw point/small segment?
                    // Using a 1-pixel line to mark the spot if no range exists
                    canvas.drawLine(centerX, highY, centerX, highY, paint)
                }
            } else if (lowY != null) {
                if (day.isToday) {
                    val forecastLowY = day.forecastLow?.let {
                        graphTop + graphHeight * (1 - (it - minTemp).toFloat() / tempRange)
                    } ?: lowY

                    canvas.drawLine(centerX - tripleBarOffset, lowY, centerX - tripleBarOffset, lowY, todayTripleYellowPaint)
                    canvas.drawLine(centerX, lowY, centerX, lowY, todayTripleOrangePaint)
                    canvas.drawLine(centerX + tripleBarOffset, forecastLowY, centerX + tripleBarOffset, forecastLowY, todayTripleBluePaint)
                } else {
                    // Only low temp
                    canvas.drawLine(centerX, lowY, centerX, lowY, paint)
                }
            }

            // Draw forecast bar (only if full data available for comparison)
            if (day.forecastHigh != null &&
                day.forecastLow != null
            ) {
                val forecastHighY =
                    graphTop +
                        graphHeight *
                        (1 - (day.forecastHigh - minTemp).toFloat() / tempRange)
                val forecastLowY =
                    graphTop +
                        graphHeight *
                        (1 - (day.forecastLow - minTemp).toFloat() / tempRange)
                val forecastX = centerX + forecastBarOffset

                // Draw the forecast bar (simple vertical line)
                canvas.drawLine(forecastX, forecastHighY, forecastX, forecastLowY, forecastBarPaint)
            }

            // Draw high label if available
            if (day.high != null) {
                val highLabel =
                    formatTempLabel(day.high, day.isToday)
                // If we have a Y position, use it. Otherwise (shouldn't happen here), skip.
                highY?.let { y ->
                    val tempPaint = if (day.isToday) todayTempTextPaint else tempTextPaint
                    canvas.drawText(
                        highLabel,
                        centerX,
                        y - dpToPx(context, 6f * scaleFactor),
                        tempPaint,
                    )
                }
            }

        }

        return bitmap
    }

    private fun dpToPx(
        context: Context,
        dp: Float,
    ): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
    }

    private fun formatTempLabel(
        actual: Float,
        isToday: Boolean,
    ): String {
        if (!isToday) return "${actual.roundToInt()}°"
        val rounded = actual.roundToInt()
        return if (kotlin.math.abs(actual - rounded) < 0.01f) "$rounded°" else String.format("%.1f°", actual)
    }
}
