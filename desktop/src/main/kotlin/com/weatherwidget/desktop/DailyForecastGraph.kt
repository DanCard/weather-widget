package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val COLOR_FORECAST_SUNNY = Color(0xFFFFD60A)
private val COLOR_FORECAST_CLOUDY = Color(0xFF8E99A4)
private val COLOR_FORECAST_RAINY = Color(0xFF5A8FBF)
private val COLOR_OBSERVED = Color(0xFFFF3366)
private val COLOR_LABEL_GRAY = Color(0xFFAAAAAA)
private const val TOP_PADDING_FRACTION = 0.10f
private const val GRAPH_BOTTOM_FRACTION = 0.76f
private const val GHOST_BAR_ALPHA = 0.3f

@Composable
fun DailyForecastGraph(
    state: DesktopDailyViewState,
    modifier: Modifier = Modifier,
    onDayClick: (java.time.LocalDate) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val displayDays = state.days
    val painters = displayDays.map { painterResource(WeatherIcon.getIconResource(it.iconCondition)) }

    if (displayDays.isEmpty()) return

    Canvas(
        modifier = modifier.pointerInput(displayDays) {
            detectTapGestures { offset ->
                val dayWidth = size.width / displayDays.size
                val index = (offset.x / dayWidth).toInt().coerceIn(0, displayDays.lastIndex)
                onDayClick(displayDays[index].date)
            }
        }
    ) {
        val allTemps = displayDays.flatMap {
            listOfNotNull(
                it.solidHigh,
                it.solidLow,
                it.forecastHigh,
                it.forecastLow,
                it.snapshotHigh,
                it.snapshotLow,
            )
        }
        val rawMin = allTemps.minOrNull() ?: 0f
        val rawMax = allTemps.maxOrNull() ?: 100f
        val rangePad = ((rawMax - rawMin) * 0.18f).coerceAtLeast(4f)
        val minTemp = rawMin - rangePad
        val maxTemp = rawMax + rangePad
        val range = (maxTemp - minTemp).coerceAtLeast(1f)
        val top = size.height * TOP_PADDING_FRACTION
        val bottom = size.height * GRAPH_BOTTOM_FRACTION
        val graphHeight = (bottom - top).coerceAtLeast(1f)
        val dayWidth = size.width / displayDays.size
        val barWidth = (dayWidth * 0.14f).coerceIn(5.dp.toPx(), 10.dp.toPx())
        val thinWidth = barWidth * 0.65f
        val tripleOffset = (8f * (dayWidth / 70.dp.toPx()).coerceIn(0.85f, 1.2f)).dp.toPx()

        fun yAt(temp: Float): Float = top + graphHeight * (1f - (temp - minTemp) / range)

        displayDays.forEachIndexed { index, day ->
            val centerX = dayWidth * index + dayWidth / 2f
            val baseColor = forecastColor(day)

            if (day.isToday) {
                drawRangeLine(centerX, day.solidHigh, day.solidLow, ::yAt, COLOR_OBSERVED, barWidth)
                drawRangeLine(centerX + tripleOffset, day.forecastHigh, day.forecastLow, ::yAt, baseColor.copy(alpha = 0.72f), thinWidth)
                drawRangeLine(centerX - tripleOffset, day.snapshotHigh, day.snapshotLow, ::yAt, baseColor.copy(alpha = 0.34f), thinWidth)
            } else if (day.isPast) {
                drawRangeLine(
                    centerX = centerX,
                    high = day.forecastHigh,
                    low = day.forecastLow,
                    yAt = ::yAt,
                    color = baseColor.copy(alpha = GHOST_BAR_ALPHA),
                    width = barWidth,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                )
                drawRangeLine(centerX, day.solidHigh, day.solidLow, ::yAt, Color.White.copy(alpha = 0.82f), barWidth * 0.72f)
            } else {
                val high = day.solidHigh
                val low = day.solidLow
                if (high != null && low != null) {
                    drawAdaptiveBar(centerX, yAt(high), yAt(low), barWidth, baseColor, day.cloudCoverRatio)
                }
            }

            val highForLabel = listOfNotNull(day.solidHigh, day.forecastHigh, day.snapshotHigh).maxOrNull()
            val lowForLabel = listOfNotNull(day.solidLow, day.forecastLow, day.snapshotLow).minOrNull()
            if (highForLabel != null) {
                val highY = yAt(highForLabel)
                val highText = textMeasurer.measure(
                    "${highForLabel.roundToInt()}°",
                    TextStyle(fontSize = 12.sp, color = if (day.isToday) Color.Yellow else Color.White)
                )
                drawText(highText, topLeft = Offset(centerX - highText.size.width / 2f, highY - 24f))
            }
            if (lowForLabel != null) {
                val lowY = yAt(lowForLabel)
                val lowText = textMeasurer.measure(
                    "${lowForLabel.roundToInt()}°",
                    TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.78f))
                )
                drawText(lowText, topLeft = Offset(centerX - lowText.size.width / 2f, lowY + 34f))

                val iconSize = (dayWidth * 0.34f).coerceIn(20.dp.toPx(), 34.dp.toPx())
                translate(centerX - iconSize / 2f, lowY + 7f) {
                    with(painters[index]) { draw(Size(iconSize, iconSize)) }
                }
            }

            val dayText = textMeasurer.measure(
                day.label,
                TextStyle(fontSize = labelSizeFor(dayWidth).sp, color = if (day.isToday) Color.Yellow else COLOR_LABEL_GRAY)
            )
            drawText(dayText, topLeft = Offset(centerX - dayText.size.width / 2f, size.height - 18f))

            val rainText = buildRainLabel(day)
            if (rainText != null) {
                val rainLayout = textMeasurer.measure(rainText, TextStyle(fontSize = 9.sp, color = COLOR_FORECAST_RAINY))
                val anchorY = highForLabel?.let { yAt(it) - 40f } ?: top + 10f
                drawText(rainLayout, topLeft = Offset(centerX - rainLayout.size.width / 2f, anchorY.coerceAtLeast(24f)))
            }
        }
    }
}

private fun DrawScope.drawRangeLine(
    centerX: Float,
    high: Float?,
    low: Float?,
    yAt: (Float) -> Float,
    color: Color,
    width: Float,
    pathEffect: PathEffect? = null,
) {
    if (high == null || low == null) return
    drawLine(
        color = color,
        start = Offset(centerX, yAt(high)),
        end = Offset(centerX, yAt(low)),
        strokeWidth = width,
        cap = StrokeCap.Round,
        pathEffect = pathEffect,
    )
}

private fun DrawScope.drawAdaptiveBar(
    centerX: Float,
    highY: Float,
    lowY: Float,
    width: Float,
    baseColor: Color,
    cloudCoverRatio: Float?,
) {
    if (cloudCoverRatio != null && baseColor == COLOR_FORECAST_SUNNY) {
        drawLine(
            brush = Brush.verticalGradient(
                0f to COLOR_FORECAST_SUNNY,
                (1f - cloudCoverRatio).coerceIn(0.05f, 0.95f) to COLOR_FORECAST_SUNNY,
                1f to COLOR_FORECAST_CLOUDY,
                startY = highY,
                endY = lowY,
            ),
            start = Offset(centerX, highY),
            end = Offset(centerX, lowY),
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
    } else {
        drawLine(
            color = baseColor,
            start = Offset(centerX, highY),
            end = Offset(centerX, lowY),
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
    }
}

private fun forecastColor(day: DesktopDailyDay): Color {
    val flags = WeatherIcon.getConditionFlags(day.iconCondition)
    return when {
        flags.isRainy -> COLOR_FORECAST_RAINY
        flags.isSunny -> COLOR_FORECAST_SUNNY
        day.cloudCoverRatio != null && day.cloudCoverRatio < 0.6f -> COLOR_FORECAST_SUNNY
        else -> COLOR_FORECAST_CLOUDY
    }
}

private fun buildRainLabel(day: DesktopDailyDay): String? {
    day.precipAmountMm?.takeIf { it > 0.25f }?.let {
        return if (it >= 25.4f) {
            "${String.format("%.1f", it / 25.4f)}in"
        } else {
            "${it.roundToInt()}mm"
        }
    }
    return day.precipProbability?.takeIf { it >= 10 }?.let { "$it%" }
}

private fun labelSizeFor(dayWidth: Float): Int =
    when {
        dayWidth < 34f -> 8
        dayWidth < 46f -> 9
        else -> 10
    }
