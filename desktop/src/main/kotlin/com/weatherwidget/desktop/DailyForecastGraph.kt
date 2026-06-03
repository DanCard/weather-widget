package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
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

/**
 * Daily forecast bar graph for the desktop popup.
 * Mirrors the Android widget's daily view, showing temperature bars, icons, and labels.
 */
private val COLOR_FORECAST = Color(0xFF5AC8FA)
private val COLOR_SUNNY = Color(0xFFFFD60A)
private val COLOR_LABEL_GRAY = Color(0xFFAAAAAA)

@Composable
fun DailyForecastGraph(
    daily: List<DailyForecast>,
    actuals: Map<String, DailyActual> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val today = remember { LocalDate.now() }
    
    // Take up to 7 days for the desktop view.
    val displayDays = remember(daily) { daily.take(7) }

    // Pre-load painters in a way that works with Compose's rules.
    val painters = displayDays.map { painterResource(WeatherIcon.getIconResource(it.condition)) }

    if (displayDays.isEmpty()) return

    Canvas(modifier = modifier) {
        val displayActuals = displayDays.mapNotNull { actuals[it.date] }
        val highTemps = displayDays.map { it.highTemp } + displayActuals.map { it.highTemp }
        val lowTemps = displayDays.map { it.lowTemp } + displayActuals.map { it.lowTemp }
        val rawMin = lowTemps.min()
        val rawMax = highTemps.max()
        
        // Pad the range for labels and breathing room.
        val pad = ((rawMax - rawMin) * 0.3f).coerceAtLeast(4f)
        val minTemp = rawMin - pad
        val maxTemp = rawMax + pad
        val range = (maxTemp - minTemp).coerceAtLeast(1f)

        val w = size.width
        val h = size.height
        val n = displayDays.size

        val dayWidth = w / n
        val barWidth = (dayWidth * 0.2f).coerceIn(4f, 12f)
        
        fun yAt(t: Float): Float = h * (0.15f + 0.55f * (1f - (t - minTemp) / range))

        displayDays.forEachIndexed { i, day ->
            val centerX = dayWidth * i + dayWidth / 2f
            val date = LocalDate.parse(day.date)
            val isToday = date == today
            
            val highY = yAt(day.highTemp)
            val lowY = yAt(day.lowTemp)
            
            // 1. Draw the Forecast Bar
            val condColor = conditionToColor(day.condition)
            drawBar(centerX, highY, lowY, barWidth, condColor)

            actuals[day.date]?.let { actual ->
                val actualHighY = yAt(actual.highTemp)
                val actualLowY = yAt(actual.lowTemp)
                drawLine(
                    color = Color.White.copy(alpha = 0.82f),
                    start = Offset(centerX + barWidth * 0.95f, actualHighY),
                    end = Offset(centerX + barWidth * 0.95f, actualLowY),
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = Color.White,
                    radius = 3f,
                    center = Offset(centerX + barWidth * 0.95f, actualHighY),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 2.5f,
                    center = Offset(centerX + barWidth * 0.95f, actualLowY),
                )
            }
            
            // 2. High Temperature Label
            val highLabel = "${day.highTemp.roundToInt()}°"
            val highTextLayout = textMeasurer.measure(
                highLabel, 
                TextStyle(fontSize = 12.sp, color = if (isToday) Color.Yellow else Color.White)
            )
            drawText(
                highTextLayout, 
                topLeft = Offset(centerX - highTextLayout.size.width / 2f, highY - 22f)
            )
            
            // 3. Low Temperature Label
            val lowLabel = "${day.lowTemp.roundToInt()}°"
            val lowTextLayout = textMeasurer.measure(
                lowLabel, 
                TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
            )
            val lowLabelY = lowY + 38f // Below the icon
            drawText(
                lowTextLayout, 
                topLeft = Offset(centerX - lowTextLayout.size.width / 2f, lowLabelY)
            )
            
            // 4. Weather Icon
            val painter = painters[i]
            val iconSize = 24.dp.toPx()
            translate(centerX - iconSize / 2f, lowY + 6f) {
                with(painter) {
                    draw(size = androidx.compose.ui.geometry.Size(iconSize, iconSize))
                }
            }
            
            // 5. Day Label (Mon, Tue, etc.)
            val dayName = if (isToday) "Today" else date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
            val dayTextLayout = textMeasurer.measure(
                dayName, 
                TextStyle(fontSize = 10.sp, color = if (isToday) Color.Yellow else COLOR_LABEL_GRAY)
            )
            drawText(
                dayTextLayout, 
                topLeft = Offset(centerX - dayTextLayout.size.width / 2f, h - 18f)
            )

            // 6. Precipitation Probability
            day.precipProbability?.let { prob ->
                if (prob >= 10) {
                    val probText = "$prob%"
                    val probTextLayout = textMeasurer.measure(
                        probText,
                        TextStyle(fontSize = 9.sp, color = COLOR_FORECAST)
                    )
                    drawText(
                        probTextLayout,
                        topLeft = Offset(centerX - probTextLayout.size.width / 2f, highY - 36f)
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawBar(centerX: Float, highY: Float, lowY: Float, width: Float, color: Color) {
    drawLine(
        color = color,
        start = Offset(centerX, highY),
        end = Offset(centerX, lowY),
        strokeWidth = width,
        cap = androidx.compose.ui.graphics.StrokeCap.Round
    )
}

private fun conditionToColor(condition: String?): Color {
    if (condition == null) return COLOR_LABEL_GRAY
    val lower = condition.lowercase()
    return when {
        lower.contains("rain") || lower.contains("drizzle") || lower.contains("shower") || lower.contains("storm") -> Color(0xFF5A8FBF)
        lower.contains("sunny") || lower.contains("clear") -> COLOR_SUNNY
        else -> COLOR_LABEL_GRAY
    }
}
