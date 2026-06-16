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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.shared.graph.DualHighLabel
import kotlin.math.roundToInt

private val COLOR_FORECAST_SUNNY = Color(com.weatherwidget.shared.util.WeatherColors.FORECAST_SUNNY)
private val COLOR_FORECAST_CLOUDY = Color(com.weatherwidget.shared.util.WeatherColors.FORECAST_CLOUDY)
private val COLOR_FORECAST_RAINY = Color(com.weatherwidget.shared.util.WeatherColors.FORECAST_RAINY)
private val COLOR_OBSERVED = Color(com.weatherwidget.shared.util.WeatherColors.OBSERVED)
private val COLOR_LABEL_GRAY = Color(0xFFAAAAAA)
private val COLOR_GAP_FALLBACK = Color(0xFF34C759)
private const val GHOST_BAR_ALPHA = 0.3f
// Mirrors Android's BULB_RADIUS_SCALE/BULB_VERTICAL_CENTER_FRACTION (DailyForecastGraphRenderer).
private const val BULB_RADIUS_SCALE = 1.2f
private const val BULB_VERTICAL_CENTER_FRACTION = 0.5f

@Composable
fun DailyForecastGraph(
    state: DesktopDailyViewState,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
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
                it.ghostHigh,
                it.snapshotHigh,
                it.snapshotLow,
            )
        }
        val rawMin = allTemps.minOrNull() ?: 0f
        val rawMax = allTemps.maxOrNull() ?: 100f
        // Minimal range padding so the hottest bar reaches the top and the coldest reaches the
        // bottom of the graph band — bars fill the available height (overlap with labels is fine).
        val rangePad = ((rawMax - rawMin) * 0.04f).coerceAtLeast(1f)
        val minTemp = rawMin - rangePad
        val maxTemp = rawMax + rangePad
        val range = (maxTemp - minTemp).coerceAtLeast(1f)
        val dayWidth = size.width / displayDays.size
        val iconSize = (30.dp.toPx() * scale).coerceAtMost(dayWidth * 0.6f)
        // Top: tiny reserve — the hottest bar runs to the top, and its high/rain labels ride up a
        // little past the canvas top into the header row (by design; the Canvas isn't clipped, so
        // the overflow paints over the header). Bottom: reserve enough for the low label + icon +
        // day name so those sit below the bars without overlapping them.
        val lowLabelBand = 11f * scale * 1.4f + 4f * scale
        val dayLabelBand = labelSizeFor(dayWidth) * scale * 1.5f + 6f * scale
        val top = 2f * scale
        // How far labels may ride above the canvas top, overlapping the header (~high-label height
        // + gap, so the hottest day's high label fully clears its bar).
        val headerBleed = 12f * scale * 1.4f + 4f * scale
        val bottomReserve = lowLabelBand + iconSize + dayLabelBand + 6f * scale
        val bottom = (size.height - bottomReserve).coerceAtLeast(size.height * 0.4f)
        val iconFloorTop = size.height - dayLabelBand - iconSize
        val graphHeight = (bottom - top).coerceAtLeast(1f)
        val barWidth = (7.dp.toPx() * scale).coerceAtMost(dayWidth * 0.22f)
        val thinWidth = barWidth * 0.65f
        val tripleOffset = (6.dp.toPx() * scale).coerceAtMost(dayWidth * 0.18f)

        fun yAt(temp: Float): Float = top + graphHeight * (1f - (temp - minTemp) / range)

        displayDays.forEachIndexed { index, day ->
            val centerX = dayWidth * index + dayWidth / 2f
            val baseColor = forecastColor(day)

            if (day.isToday) {
                val snapshotFlags = WeatherIcon.getConditionFlags(day.snapshot?.condition)
                val sCondColor = when {
                    snapshotFlags.isRainy -> COLOR_FORECAST_RAINY
                    snapshotFlags.isMixed -> COLOR_FORECAST_SUNNY
                    snapshotFlags.isSunny -> COLOR_FORECAST_SUNNY
                    else -> COLOR_FORECAST_CLOUDY
                }
                val snapshotColor = if (sCondColor == COLOR_FORECAST_SUNNY || day.snapshot?.condition == null) {
                    Color.Yellow
                } else {
                    sCondColor
                }

                drawRangeLine(centerX + tripleOffset, day.forecastHigh, day.forecastLow, ::yAt, baseColor, thinWidth)
                drawRangeLine(centerX - tripleOffset, day.snapshotHigh, day.snapshotLow, ::yAt, snapshotColor, thinWidth)
                // The thermostat: solid red "mercury" (current temp), a faint ghost reaching up to
                // the day's high-water mark, and a round bulb at the low end. Drawn last so the
                // mercury and bulb sit on top of the forecast/snapshot bars.
                val solidHigh = day.solidHigh
                val ghostHigh = day.ghostHigh
                if (ghostHigh != null && solidHigh != null && ghostHigh > solidHigh) {
                    drawRangeLine(centerX, ghostHigh, solidHigh, ::yAt, COLOR_OBSERVED.copy(alpha = GHOST_BAR_ALPHA), barWidth)
                }
                drawRangeLine(centerX, day.solidHigh, day.solidLow, ::yAt, COLOR_OBSERVED, barWidth)
                day.solidLow?.let { low ->
                    val bulbRadius = barWidth * BULB_RADIUS_SCALE
                    drawCircle(
                        color = COLOR_OBSERVED,
                        radius = bulbRadius,
                        center = Offset(centerX, yAt(low) + bulbRadius * BULB_VERTICAL_CENTER_FRACTION),
                    )
                }
            } else if (day.isPast) {
                drawRangeLine(
                    centerX = centerX + tripleOffset,
                    high = day.forecastHigh,
                    low = day.forecastLow,
                    yAt = ::yAt,
                    // Adaptive forecast color (amber/gray/blue) to match Android's forecast overlay.
                    color = forecastColor(day),
                    width = thinWidth,
                )
                drawRangeLine(centerX, day.solidHigh, day.solidLow, ::yAt, COLOR_OBSERVED, barWidth * 0.72f)
            } else {
                val high = day.solidHigh
                val low = day.solidLow
                if (high != null && low != null) {
                    val color = if (day.isClimateNormal) COLOR_GAP_FALLBACK else baseColor
                    drawAdaptiveBar(centerX, yAt(high), yAt(low), barWidth, color, day.cloudCoverRatio)
                }
            }

            // Today uses the shared cutoff rule: after 9am the low number tracks the observed actual
            // and drops the forecast/snapshot comparison lows (folded together here so the pre-cutoff
            // value is unchanged), falling back to them only when no actual exists yet. Other days
            // keep the plain min of all candidate lows.
            val lowForLabel = if (day.isToday) {
                com.weatherwidget.shared.util.DailyDayValueResolver.effectiveLowForLabel(
                    isToday = true,
                    solidLow = day.solidLow,
                    forecastLow = listOfNotNull(day.forecastLow, day.snapshotLow).minOrNull(),
                    nowHour = day.nowHour,
                )
            } else {
                listOfNotNull(day.solidLow, day.forecastLow, day.snapshotLow).minOrNull()
            }

            // Past days: label BOTH the actual high (thermostat pink) and the forecast high (yellow,
            // matching the forecast bar) when they differ enough and there's room (DualHighLabel);
            // otherwise fall through to the single high label below.
            val highForLabel = listOfNotNull(day.solidHigh, day.forecastHigh, day.ghostHigh, day.snapshotHigh).maxOrNull()
            val pastActualHigh = if (day.isPast) day.solidHigh else null
            val pastForecastHigh = if (day.isPast) day.forecastHigh else null
            // Both highs at full size (no 2% two-label shrink); the lower one gets a 4% boost below.
            val dualBase = 12f * scale
            // Lower label = smaller temp (sits lower, with the taller forecast bar through it) → boost.
            fun dualBaseFor(temp: Float, otherTemp: Float): Float =
                if (temp < otherTemp) dualBase * LOWER_DUAL_LABEL_FONT_BOOST else dualBase
            val showDualHighs = if (pastActualHigh != null && pastForecastHigh != null) {
                val aText = formatTemp(pastActualHigh)
                val fText = formatTemp(pastForecastHigh)
                val aH = textMeasurer.measure(aText, TextStyle(fontSize = tempFontSize(aText, dualBaseFor(pastActualHigh, pastForecastHigh)).sp)).size.height.toFloat()
                val fH = textMeasurer.measure(fText, TextStyle(fontSize = tempFontSize(fText, dualBaseFor(pastForecastHigh, pastActualHigh)).sp)).size.height.toFloat()
                val aTop = (yAt(pastActualHigh) - aH - 3f * scale).coerceAtLeast(-headerBleed)
                val fTop = (yAt(pastForecastHigh) - fH - 3f * scale).coerceAtLeast(-headerBleed)
                DualHighLabel.showBoth(pastActualHigh, pastForecastHigh, aTop, fTop, maxOf(aH, fH))
            } else false

            if (showDualHighs && pastActualHigh != null && pastForecastHigh != null) {
                val aText = formatTemp(pastActualHigh)
                val aSize = tempFontSize(aText, dualBaseFor(pastActualHigh, pastForecastHigh))
                val aLayout = textMeasurer.measure(aText, TextStyle(fontSize = aSize.sp, color = COLOR_OBSERVED))
                val aY = (yAt(pastActualHigh) - aLayout.size.height - 3f * scale).coerceAtLeast(-headerBleed)
                drawOutlinedText(textMeasurer, aLayout, Offset(centerX - aLayout.size.width / 2f, aY))

                val fText = formatTemp(pastForecastHigh)
                val fSize = tempFontSize(fText, dualBaseFor(pastForecastHigh, pastActualHigh))
                val fLayout = textMeasurer.measure(fText, TextStyle(fontSize = fSize.sp, color = forecastColor(day)))
                val fY = (yAt(pastForecastHigh) - fLayout.size.height - 3f * scale).coerceAtLeast(-headerBleed)
                drawOutlinedText(textMeasurer, fLayout, Offset(centerX + tripleOffset - fLayout.size.width / 2f, fY))
            } else {
                // Single label uses the shared effectiveHigh() so the headline rule matches Android
                // exactly: today = max(observed, live-forecast, ghost) — deliberately EXCLUDING the
                // 24h-prior snapshot (a comparison overlay, not the headline). Past/future show the
                // actual/forecast value (day.solidHigh); falls back to forecast only if no actual.
                val singleHigh = if (day.isToday)
                    com.weatherwidget.shared.util.DailyDayValueResolver.effectiveHighForLabel(
                        isToday = true,
                        solidHigh = day.solidHigh,
                        forecastHigh = day.forecastHigh,
                        ghostHigh = day.ghostHigh,
                        nowHour = day.nowHour,
                    )
                else day.solidHigh ?: day.forecastHigh ?: day.snapshotHigh ?: day.ghostHigh
                if (singleHigh != null) {
                    val highLabelText = formatTemp(singleHigh)
                    val highSize = tempFontSize(highLabelText, 12f * scale)
                    val highText = textMeasurer.measure(
                        highLabelText,
                        TextStyle(fontSize = highSize.sp, color = if (day.isToday) Color.Yellow else Color.White)
                    )
                    // Sit above the bar top; for the hottest bar this rides up past the canvas top into
                    // the header (a little overlap is welcome) rather than dropping onto the bar.
                    val highLabelY = (yAt(singleHigh) - highText.size.height - 3f * scale).coerceAtLeast(-headerBleed)
                    val highTopLeft = Offset(centerX - highText.size.width / 2f, highLabelY)
                    // History and today get the thin outline (today's headline sits over the triple
                    // bars, like history's dual labels); future days stay plain.
                    if (day.isPast || day.isToday) drawOutlinedText(textMeasurer, highText, highTopLeft)
                    else drawText(highText, topLeft = highTopLeft)
                }
            }
            if (lowForLabel != null) {
                val lowLabelText = formatTemp(lowForLabel)
                val lowSize = tempFontSize(lowLabelText, 11f * scale)
                val lowText = textMeasurer.measure(
                    lowLabelText,
                    TextStyle(fontSize = lowSize.sp, color = Color.White.copy(alpha = 0.78f))
                )
                // Order matches Android: bar → weather icon → low label. The icon anchors under the
                // LOWEST drawn bar (geometry, via the shared iconAnchorLow) rather than the printed
                // value, so it stays beneath the deepest bar even when the number tracks a higher
                // actual (today) or a forecast-overlay bar dips lower (history). Both clamp to stay
                // above the day-name row, overlapping the bar bottom rather than clipping on cold days.
                val anchorLow = com.weatherwidget.shared.util.DailyDayValueResolver.iconAnchorLow(
                    solidLow = day.solidLow,
                    forecastLow = day.forecastLow,
                    snapshotLow = day.snapshotLow,
                ) ?: lowForLabel
                val iconTopMax = size.height - dayLabelBand - lowText.size.height - 2f * scale - iconSize - 2f * scale
                val iconTop = (yAt(anchorLow) + 4f * scale).coerceAtMost(iconTopMax)
                translate(centerX - iconSize / 2f, iconTop) {
                    with(painters[index]) { draw(Size(iconSize, iconSize)) }
                }

                val lowLabelY = iconTop + iconSize + 2f * scale
                val lowTopLeft = Offset(centerX - lowText.size.width / 2f, lowLabelY)
                if (day.isPast) drawOutlinedText(textMeasurer, lowText, lowTopLeft)
                else drawText(lowText, topLeft = lowTopLeft)
            }

            val dayText = textMeasurer.measure(
                day.label,
                TextStyle(fontSize = (labelSizeFor(dayWidth) * scale).sp, color = if (day.isToday) Color.Yellow else COLOR_LABEL_GRAY)
            )
            drawText(dayText, topLeft = Offset(centerX - dayText.size.width / 2f, size.height - dayText.size.height - 6f * scale))

            // Daytime rain label: sits on top of the bar, above the high-temp label.
            val rainText = day.dailyRainLabelText
            if (rainText != null) {
                val rainLayout = textMeasurer.measure(rainText, TextStyle(fontSize = (9f * scale).sp, color = COLOR_FORECAST_RAINY))
                // Sit above the high-temp label: bar top - (high label height ~14sp*scale) - gap - own height.
                val anchorY = highForLabel?.let { yAt(it) - (14f * scale + 8f * scale) - rainLayout.size.height } ?: (top + 10f)
                // Stays above the high-temp label; may ride a little further into the header than it.
                val rainFloor = -headerBleed - rainLayout.size.height - 2f * scale
                drawText(rainLayout, topLeft = Offset(centerX - rainLayout.size.width / 2f, anchorY.coerceAtLeast(rainFloor)))
            }

            // Nighttime rain label: tucked between this column and the next (Android shifts it
            // +dayWidth/2 toward the neighbor), smaller, in the low-temp band.
            val nightText = day.nightRainLabelText
            if (nightText != null && lowForLabel != null) {
                val nightLayout = textMeasurer.measure(
                    nightText,
                    TextStyle(fontSize = (11f * scale * 0.72f).sp, color = COLOR_FORECAST_RAINY),
                )
                val edgeMargin = 2f * scale
                val nightX = (centerX + dayWidth / 2f - nightLayout.size.width / 2f)
                    .coerceIn(edgeMargin, size.width - nightLayout.size.width - edgeMargin)
                // Anchor just below the low temp, but keep clear of the icon/day-name row.
                val nightFloor = iconFloorTop - nightLayout.size.height - 2f * scale
                val nightY = (yAt(lowForLabel) + 3f * scale).coerceAtMost(nightFloor)
                drawText(nightLayout, topLeft = Offset(nightX, nightY))
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
        flags.isMixed -> COLOR_FORECAST_SUNNY
        flags.isSunny -> COLOR_FORECAST_SUNNY
        day.cloudCoverRatio != null && day.cloudCoverRatio < 0.6f -> COLOR_FORECAST_SUNNY
        else -> COLOR_FORECAST_CLOUDY
    }
}

private fun labelSizeFor(dayWidth: Float): Int =
    when {
        dayWidth < 34f -> 8
        dayWidth < 46f -> 9
        else -> 10
    }

// Show the tenth for any non-integer value (".0" suppressed by TempUtils.formatTemp), for
// forecasts/future and actuals alike — matches the Android daily view. NWS integer forecasts
// stay clean; climate normals and decimal sources reveal their tenth.
private fun formatTemp(v: Float?): String {
    if (v == null) return ""
    return com.weatherwidget.shared.util.TempUtils.formatTemp(v) ?: ""
}

/** Temp-label font size: wide 3+ digit temps (100°, 97.7°) draw a further 5% smaller. */
private fun tempFontSize(text: String, base: Float): Float =
    base * (if (DualHighLabel.isWideLabel(text)) DualHighLabel.WIDE_LABEL_FONT_SCALE else 1f)

// When both past-day highs are labeled, the lower one (smaller temp) sits down where the taller
// forecast bar passes through it; bump it 4% larger for legibility. Desktop-only.
private const val LOWER_DUAL_LABEL_FONT_BOOST = 1.08f
