package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.data.model.DailyActual
import com.weatherwidget.data.model.DailyForecast
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.roundToInt

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Daily forecast bar graph for the desktop popup.
 * Mirrors the Android widget's daily view, showing temperature bars, icons, and labels.
 */
private val COLOR_FORECAST_SUNNY = Color(0xFFF4C542)
private val COLOR_FORECAST_CLOUDY = Color(0xFF8E99A4)
private val COLOR_FORECAST_RAINY = Color(0xFF5A8FBF)
private val COLOR_FORECAST_NIGHT = Color(0xFFBBBBBB)
private val COLOR_OBSERVED = Color(0xFFFF3366)
private val COLOR_LABEL_GRAY = Color(0xFFAAAAAA)

private const val GHOST_BAR_ALPHA = 0.3f
private const val TRIPLE_BAR_OFFSET_DP = 8f

@Composable
fun DailyForecastGraph(
    daily: List<DailyForecast>,
    actuals: Map<String, DailyActual> = emptyMap(),
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val today = remember { LocalDate.now() }
    
    // Take up to 7 days for the desktop view.
    val displayDays = remember(daily) { daily.take(7) }

    // Pre-load painters in a way that works with Compose's rules.
    val painters = displayDays.map { painterResource(WeatherIcon.getIconResource(it.condition)) }

    if (displayDays.isEmpty()) return

    Canvas(modifier = modifier.pointerInput(displayDays) {
        detectTapGestures { offset ->
            val n = displayDays.size
            if (n > 0) {
                val dayWidth = size.width / n
                val index = (offset.x / dayWidth).toInt().coerceIn(0, n - 1)
                onDayClick(LocalDate.parse(displayDays[index].date))
            }
        }
    }) {
        val displayActuals = displayDays.mapNotNull { actuals[it.date] }
        val highTemps = displayDays.map { it.highTemp } + displayActuals.map { it.highTemp }
        val lowTemps = displayDays.map { it.lowTemp } + displayActuals.map { it.lowTemp }
        val rawMin = lowTemps.minOrNull() ?: 0f
        val rawMax = highTemps.maxOrNull() ?: 100f
        
        // Pad the range for labels and breathing room.
        val pad = ((rawMax - rawMin) * 0.3f).coerceAtLeast(4f)
        val minTemp = rawMin - pad
        val maxTemp = rawMax + pad
        val range = (maxTemp - minTemp).coerceAtLeast(1f)

        val w = size.width
        val h = size.height
        val n = displayDays.size

        val dayWidth = w / n
        val barWidth = (dayWidth * 0.15f).coerceIn(4f, 10f)
        val tripleBarOffset = TRIPLE_BAR_OFFSET_DP.dp.toPx()
        
        fun yAt(t: Float): Float = h * (0.15f + 0.55f * (1f - (t - minTemp) / range))

        displayDays.forEachIndexed { i, day ->
            val centerX = dayWidth * i + dayWidth / 2f
            val date = LocalDate.parse(day.date)
            val isToday = date == today
            val isPast = date.isBefore(today)
            
            val highY = yAt(day.highTemp)
            val lowY = yAt(day.lowTemp)
            
            val flags = WeatherIcon.getConditionFlags(day.condition)
            val condColor = forecastColor(flags)

            if (isToday) {
                // Today Triple Bar
                actuals[day.date]?.let { actual ->
                    val actualHighY = yAt(actual.highTemp)
                    val actualLowY = yAt(actual.lowTemp)
                    
                    // 1. Observed (Red line in center)
                    drawLine(
                        color = COLOR_OBSERVED,
                        start = Offset(centerX, actualHighY),
                        end = Offset(centerX, actualLowY),
                        strokeWidth = barWidth * 0.8f,
                        cap = StrokeCap.Round
                    )
                    
                    // 2. Forecast (Thin line to the right)
                    drawLine(
                        color = condColor.copy(alpha = 0.6f),
                        start = Offset(centerX + tripleBarOffset, highY),
                        end = Offset(centerX + tripleBarOffset, lowY),
                        strokeWidth = barWidth * 0.6f,
                        cap = StrokeCap.Round
                    )

                    // 3. Ghost/Snapshot (Thin line to the left - simplified for desktop)
                    drawLine(
                        color = condColor.copy(alpha = 0.3f),
                        start = Offset(centerX - tripleBarOffset, highY),
                        end = Offset(centerX - tripleBarOffset, lowY),
                        strokeWidth = barWidth * 0.6f,
                        cap = StrokeCap.Round
                    )
                } ?: run {
                    // Fallback to single bar if no actuals
                    drawAdaptiveBar(centerX, highY, lowY, barWidth, condColor, day.condition)
                }
            } else if (isPast) {
                // Past Day: Actuals (Solid) + Ghost Forecast (Dashed)
                actuals[day.date]?.let { actual ->
                    val actualHighY = yAt(actual.highTemp)
                    val actualLowY = yAt(actual.lowTemp)
                    
                    // Ghost forecast
                    drawLine(
                        color = condColor.copy(alpha = GHOST_BAR_ALPHA),
                        start = Offset(centerX, highY),
                        end = Offset(centerX, lowY),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )

                    // Actual bar
                    drawLine(
                        color = Color.White.copy(alpha = 0.8f),
                        start = Offset(centerX, actualHighY),
                        end = Offset(centerX, actualLowY),
                        strokeWidth = barWidth * 0.7f,
                        cap = StrokeCap.Round
                    )
                } ?: run {
                    drawAdaptiveBar(centerX, highY, lowY, barWidth, COLOR_LABEL_GRAY, day.condition)
                }
            } else {
                // Future Day: Solid Adaptive Bar
                drawAdaptiveBar(centerX, highY, lowY, barWidth, condColor, day.condition)
            }
            
            // Labels and Icon (Same as before but with minor tweaks)
            val highLabel = "${day.highTemp.roundToInt()}°"
            val highTextLayout = textMeasurer.measure(
                highLabel, 
                TextStyle(fontSize = 12.sp, color = if (isToday) Color.Yellow else Color.White)
            )
            drawText(
                highTextLayout, 
                topLeft = Offset(centerX - highTextLayout.size.width / 2f, highY - 24f)
            )
            
            val lowLabel = "${day.lowTemp.roundToInt()}°"
            val lowTextLayout = textMeasurer.measure(
                lowLabel, 
                TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
            )
            val lowLabelY = lowY + 38f
            drawText(
                lowTextLayout, 
                topLeft = Offset(centerX - lowTextLayout.size.width / 2f, lowLabelY)
            )
            
            val painter = painters[i]
            val iconSize = 24.dp.toPx()
            translate(centerX - iconSize / 2f, lowY + 6f) {
                with(painter) {
                    draw(size = androidx.compose.ui.geometry.Size(iconSize, iconSize))
                }
            }
            
            val dayName = if (isToday) "Today" else date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
            val dayTextLayout = textMeasurer.measure(
                dayName, 
                TextStyle(fontSize = 10.sp, color = if (isToday) Color.Yellow else COLOR_LABEL_GRAY)
            )
            drawText(
                dayTextLayout, 
                topLeft = Offset(centerX - dayTextLayout.size.width / 2f, h - 18f)
            )

            day.precipProbability?.let { prob ->
                if (prob >= 10) {
                    val probText = "$prob%"
                    val probTextLayout = textMeasurer.measure(
                        probText,
                        TextStyle(fontSize = 9.sp, color = COLOR_FORECAST_RAINY)
                    )
                    drawText(
                        probTextLayout,
                        topLeft = Offset(centerX - probTextLayout.size.width / 2f, highY - 38f)
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawAdaptiveBar(centerX: Float, highY: Float, lowY: Float, width: Float, baseColor: Color, condition: String?) {
    val cloudRatio = WeatherIcon.getCloudRatio(condition)
    if (cloudRatio != null && baseColor == COLOR_FORECAST_SUNNY) {
        // Mixed condition: Gold top -> Gray bottom
        val brush = Brush.verticalGradient(
            0f to COLOR_FORECAST_SUNNY,
            (1f - cloudRatio) to COLOR_FORECAST_SUNNY,
            1f to COLOR_FORECAST_CLOUDY,
            startY = highY,
            endY = lowY
        )
        drawLine(
            brush = brush,
            start = Offset(centerX, highY),
            end = Offset(centerX, lowY),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
    } else {
        drawLine(
            color = baseColor,
            start = Offset(centerX, highY),
            end = Offset(centerX, lowY),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
    }
}

private fun forecastColor(flags: WeatherIcon.ConditionFlags): Color {
    return when {
        flags.isRainy -> COLOR_FORECAST_RAINY
        flags.isNight -> COLOR_FORECAST_NIGHT
        flags.isSunny -> COLOR_FORECAST_SUNNY
        else -> COLOR_FORECAST_CLOUDY
    }
}
