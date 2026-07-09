package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.shared.util.DayClickResolver
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.WeatherConditionResolver
import kotlin.math.roundToInt

private const val TAG = "DailyForecastGraph"

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
    onDayClick: (java.time.LocalDate, DayClickResolver.DayTapZone) -> Unit = { _, _ -> },
    useCelsius: Boolean,
) {
    val formatTemp = { v: Float? ->
        if (v == null) ""
        else com.weatherwidget.shared.util.TempUtils.formatTemp(v, useCelsius) ?: ""
    }
    val textMeasurer = rememberTextMeasurer()
    val displayDays = state.days
    // Use the pre-resolved, cloud-gated icon NAME (matches Android) rather than re-resolving the raw
    // condition here (which would ignore the noon cloud % and the daily partly-cloudy floor).
    val painters = displayDays.map { painterResource("drawable/${it.iconName}.xml") }

    if (displayDays.isEmpty()) return

    Canvas(
        modifier = modifier.pointerInput(displayDays, scale, textMeasurer) {
            detectTapGestures { offset ->
                val canvasW = size.width.toFloat()
                val canvasH = size.height.toFloat()
                val layout = computeDailyGraphTapLayout(
                    days = displayDays,
                    canvasWidth = canvasW,
                    canvasHeight = canvasH,
                    scale = scale,
                    density = density,
                    useCelsius = useCelsius,
                    measureLowLabelHeight = { text, base ->
                        textMeasurer.measure(text, TextStyle(fontSize = tempFontSize(text, base).sp)).size.height.toFloat()
                    },
                )
                val index = (offset.x / layout.dayWidth).toInt().coerceIn(0, displayDays.lastIndex)
                val zone = classifyDailyGraphTapZone(offset.x, offset.y, index, layout)
                val day = displayDays[index]
                Log.d(
                    TAG,
                    "dayClick date=${day.date} col=$index tap=(${offset.x.roundToInt()},${offset.y.roundToInt()}) " +
                        "zone=$zone iconTop=${layout.iconTops.getOrNull(index)?.roundToInt()} " +
                        "iconSize=${layout.iconSize.roundToInt()} strip=${layout.bottomStripHeightPx.roundToInt()} " +
                        "iconName=${day.iconName}",
                )
                onDayClick(day.date, zone)
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

        fun resolveLowLabelY(idx: Int): Float? {
            val d = displayDays.getOrNull(idx) ?: return null
            val lowVal = com.weatherwidget.shared.util.DailyDayValueResolver.effectiveLowForLabel(
                isToday = d.isToday,
                solidLow = d.solidLow,
                forecastLow = listOfNotNull(d.forecastLow, d.snapshotLow).minOrNull(),
                nowHour = d.nowHour,
            ) ?: return null

            val lowLabelText = formatTemp(lowVal)
            val lowSize = tempFontSize(lowLabelText, 11f * scale)
            val lowTextLayout = textMeasurer.measure(
                lowLabelText,
                TextStyle(fontSize = lowSize.sp)
            )

            val anchorLow = com.weatherwidget.shared.util.DailyDayValueResolver.iconAnchorLow(
                solidLow = d.solidLow,
                forecastLow = d.forecastLow,
                snapshotLow = d.snapshotLow,
            ) ?: lowVal

            val iconTopMax = size.height - dayLabelBand - lowTextLayout.size.height - 2f * scale - iconSize - 2f * scale
            val iconTop = (yAt(anchorLow) + 4f * scale).coerceAtMost(iconTopMax)
            return iconTop + iconSize + 2f * scale
        }

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

                // Both today bars carry today's cloud-cover ratio (same day); the rain-vs-cloud
                // bottom color follows each bar's own condition (live forecast vs 24h-prior snapshot).
                drawAdaptiveBar(centerX + tripleOffset, day.forecastHigh, day.forecastLow, ::yAt, thinWidth, baseColor, day.cloudCoverRatio, day.iconCondition)
                drawAdaptiveBar(centerX - tripleOffset, day.snapshotHigh, day.snapshotLow, ::yAt, thinWidth, snapshotColor, day.cloudCoverRatio, day.snapshot?.condition)
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
                // Forecast overlay carries the cloud/rain split (matches Android's past-day overlay);
                // the observed actual bar stays solid.
                drawAdaptiveBar(
                    centerX = centerX + tripleOffset,
                    high = day.forecastHigh,
                    low = day.forecastLow,
                    yAt = ::yAt,
                    width = thinWidth,
                    baseColor = forecastColor(day),
                    cloudCoverRatio = day.cloudCoverRatio,
                    iconCondition = day.iconCondition,
                )
                drawRangeLine(centerX, day.solidHigh, day.solidLow, ::yAt, COLOR_OBSERVED, barWidth * 0.72f)
            } else {
                val high = day.solidHigh
                val low = day.solidLow
                if (high != null && low != null) {
                    val color = if (day.isClimateNormal) COLOR_GAP_FALLBACK else baseColor
                    drawAdaptiveBar(centerX, high, low, ::yAt, barWidth, color, day.cloudCoverRatio, day.iconCondition)
                }
            }

            // All columns funnel through the shared resolver (matches Android exactly):
            //   today  -> cutoff rule: after 9am the low tracks the observed actual and drops the
            //             forecast/snapshot comparison lows (folded together so the pre-cutoff value
            //             is unchanged), falling back to them only when no actual exists yet.
            //   past   -> returns solidLow (the observed actual) — the forecast low is bar-only and
            //             must never win the printed number, even when it predicted colder.
            //   future -> returns solidLow (which IS the forecast for future days).
            val lowForLabel = com.weatherwidget.shared.util.DailyDayValueResolver.effectiveLowForLabel(
                isToday = day.isToday,
                solidLow = day.solidLow,
                forecastLow = listOfNotNull(day.forecastLow, day.snapshotLow).minOrNull(),
                nowHour = day.nowHour,
            )

            // History — and today once its high is settled (past the 5pm cutoff) — label BOTH the
            // actual high (thermostat pink) and the forecast high (yellow, matching the forecast bar)
            // when they differ enough and there's room (DualHighLabel); otherwise fall through to the
            // single high label below. Today's actual is the observed peak (max of solid/ghost); its
            // forecast label sits over the live-forecast bar (the same +tripleOffset as history's).
            val highForLabel = listOfNotNull(day.solidHigh, day.forecastHigh, day.ghostHigh, day.snapshotHigh).maxOrNull()
            val todayHighSettled = com.weatherwidget.shared.util.DailyDayValueResolver.isHighTrackingActual(
                isToday = day.isToday,
                solidHigh = day.solidHigh,
                ghostHigh = day.ghostHigh,
                nowHour = day.nowHour,
            )
            // Top Y of the high-temp label drawn at this column's center (actual high in dual mode,
            // the single high otherwise). The day rain label anchors to this *rendered* top so it
            // tucks against the temperature instead of floating, matching Android.
            var highLabelTopAtCenter: Float? = null
            val dualActualHigh = when {
                day.isPast -> day.solidHigh
                todayHighSettled -> listOfNotNull(day.solidHigh, day.ghostHigh).maxOrNull()
                else -> null
            }
            val dualForecastHigh = if (day.isPast || todayHighSettled) day.forecastHigh else null
            // Both highs at full size (no 2% two-label shrink); the lower one gets a 4% boost below.
            val dualBase = 12f * scale
            // Lower label = smaller temp (sits lower, with the taller forecast bar through it) → boost.
            fun dualBaseFor(temp: Float, otherTemp: Float): Float =
                if (temp < otherTemp) dualBase * LOWER_DUAL_LABEL_FONT_BOOST else dualBase
            val showDualHighs = if (dualActualHigh != null && dualForecastHigh != null) {
                val aText = formatTemp(dualActualHigh)
                val fText = formatTemp(dualForecastHigh)
                val aH = textMeasurer.measure(aText, TextStyle(fontSize = tempFontSize(aText, dualBaseFor(dualActualHigh, dualForecastHigh)).sp)).size.height.toFloat()
                val fH = textMeasurer.measure(fText, TextStyle(fontSize = tempFontSize(fText, dualBaseFor(dualForecastHigh, dualActualHigh)).sp)).size.height.toFloat()
                val aTop = (yAt(dualActualHigh) - aH - 3f * scale).coerceAtLeast(-headerBleed)
                val fTop = (yAt(dualForecastHigh) - fH - 3f * scale).coerceAtLeast(-headerBleed)
                DualHighLabel.showBoth(dualActualHigh, dualForecastHigh, aTop, fTop, maxOf(aH, fH))
            } else false

            if (showDualHighs && dualActualHigh != null && dualForecastHigh != null) {
                val aText = formatTemp(dualActualHigh)
                val aSize = tempFontSize(aText, dualBaseFor(dualActualHigh, dualForecastHigh))
                val aLayout = textMeasurer.measure(aText, TextStyle(fontSize = aSize.sp, color = COLOR_OBSERVED))
                val aY = (yAt(dualActualHigh) - aLayout.size.height - 3f * scale).coerceAtLeast(-headerBleed)
                drawOutlinedText(textMeasurer, aLayout, Offset(centerX - aLayout.size.width / 2f, aY))

                val fText = formatTemp(dualForecastHigh)
                val fSize = tempFontSize(fText, dualBaseFor(dualForecastHigh, dualActualHigh))
                val fLayout = textMeasurer.measure(fText, TextStyle(fontSize = fSize.sp, color = forecastColor(day)))
                val fY = (yAt(dualForecastHigh) - fLayout.size.height - 3f * scale).coerceAtLeast(-headerBleed)
                drawOutlinedText(textMeasurer, fLayout, Offset(centerX + tripleOffset - fLayout.size.width / 2f, fY))
                // Rain % anchors above the TOPMOST of the two high labels (warmer temp = higher on
                // screen) so it clears BOTH instead of wedging between them (matches Android).
                highLabelTopAtCenter = minOf(aY, fY)
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
                    // Once today's high is settled (past 5pm) the single number tracks the observed
                    // actual — recolor it the thermostat (observed) color so it reads as a real
                    // reading, not a forecast. Mirrors the dual-label gate above (and Android).
                    val highColor = when {
                        todayHighSettled -> COLOR_OBSERVED
                        day.isToday -> Color.Yellow
                        else -> Color.White
                    }
                    val highText = textMeasurer.measure(
                        highLabelText,
                        TextStyle(fontSize = highSize.sp, color = highColor)
                    )
                    // Sit above the bar top; for the hottest bar this rides up past the canvas top into
                    // the header (a little overlap is welcome) rather than dropping onto the bar.
                    val highLabelY = (yAt(singleHigh) - highText.size.height - 3f * scale).coerceAtLeast(-headerBleed)
                    highLabelTopAtCenter = highLabelY
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
                // Probability-weighted (and, for future/today, distance-weighted) font size — the same
                // shared rule Android uses, applied to the desktop base size. History scales by
                // probability only (no distance term).
                val rainScale = DailyRainLabels.rainLabelFontScale(day.isPast, day.dayPrecipProbability, day.daysFromToday)
                val rainLayout = textMeasurer.measure(rainText, TextStyle(fontSize = (9f * scale * rainScale).sp, color = COLOR_FORECAST_RAINY))
                // Anchor to the high label's actual rendered top (shared rule: rain bottom = high top -
                // gap; negative gap = slight overlap). Falls back to a small inset only if no high label.
                val gapPx = DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP * scale
                val anchorY = highLabelTopAtCenter?.let { it - gapPx - rainLayout.size.height } ?: (top + 10f)
                // Stays above the high-temp label; may ride a little further into the header than it.
                val rainFloor = -headerBleed - rainLayout.size.height - 2f * scale
                drawText(rainLayout, topLeft = Offset(centerX - rainLayout.size.width / 2f, anchorY.coerceAtLeast(rainFloor)))
            }

            // Nighttime rain label: tucked between this column and the next (Android shifts it
            // +dayWidth/2 toward the neighbor), smaller, in the low-temp band.
            val nightText = day.nightRainLabelText
            if (nightText != null && lowForLabel != null) {
                val leftLowY = resolveLowLabelY(index)
                val rightLowY = resolveLowLabelY(index + 1)

                if (leftLowY != null) {
                    val anchorLowY = if (rightLowY != null) minOf(leftLowY, rightLowY) else leftLowY

                    // Measure the low text of the left day to know its height/bottom
                    val lowLabelText = formatTemp(lowForLabel)
                    val lowSize = tempFontSize(lowLabelText, 11f * scale)
                    val lowText = textMeasurer.measure(
                        lowLabelText,
                        TextStyle(fontSize = lowSize.sp)
                    )
                    val leftLowHeight = lowText.size.height

                    val anchorBottomY = anchorLowY + leftLowHeight

                    // Room below calculation (Android: hardBottomLimit - anchorBaseline)
                    val roomBelowPx = (size.height - dayLabelBand - anchorBottomY).coerceAtLeast(0f)
                    val roomBelowDp = roomBelowPx / scale

                    val NIGHT_TUCK_ROOM_MIN_DP = DailyRainLabels.NIGHT_TUCK_ROOM_MIN_DP
                    val NIGHT_TUCK_ROOM_MAX_DP = DailyRainLabels.NIGHT_TUCK_ROOM_MAX_DP
                    val NIGHT_TUCK_OVERLAP_BASE_DP = DailyRainLabels.NIGHT_TUCK_OVERLAP_BASE_DP
                    val NIGHT_TUCK_NUDGE_BASE_DP = DailyRainLabels.NIGHT_TUCK_NUDGE_BASE_DP
                    val NIGHT_TUCK_NUDGE_RANGE_DP = DailyRainLabels.NIGHT_TUCK_NUDGE_RANGE_DP

                    val tightFraction = (1f - (roomBelowDp - NIGHT_TUCK_ROOM_MIN_DP) / (NIGHT_TUCK_ROOM_MAX_DP - NIGHT_TUCK_ROOM_MIN_DP)).coerceIn(0f, 1f)
                    val dynamicOverlapDp = NIGHT_TUCK_OVERLAP_BASE_DP * tightFraction
                    val dynamicNudgeDp = NIGHT_TUCK_NUDGE_BASE_DP + (NIGHT_TUCK_NUDGE_RANGE_DP * tightFraction)

                    // When roomy, push the label a couple px right + down off its snug tuck; collapses
                    // to 0 when cramped so a tight column keeps the existing tuck under the low temp.
                    val roomFraction = 1f - tightFraction
                    val roomyRightPx = DailyRainLabels.NIGHT_TUCK_ROOMY_RIGHT_DP * roomFraction * scale
                    val roomyDownPx = DailyRainLabels.NIGHT_TUCK_ROOMY_DOWN_DP * roomFraction * scale

                    val isLeftTempLower = rightLowY != null && leftLowY > rightLowY
                    val effectiveNudgeDp = if (isLeftTempLower) dynamicNudgeDp * 0.0f else dynamicNudgeDp

                    val hNudgePx = effectiveNudgeDp * scale
                    val shiftedCenterX = centerX + dayWidth / 2f - hNudgePx + 1f * scale + roomyRightPx

                    // Base rain layout. Same probability/distance font scaling as the day label,
                    // times NIGHT_SCALE (history = probability only, no distance term).
                    val nightFontScale = DailyRainLabels.rainLabelFontScale(day.isPast, day.nightPrecipProbability, day.daysFromToday)
                    var finalPaintStyle = TextStyle(fontSize = (11f * scale * DailyRainLabels.NIGHT_SCALE * nightFontScale).sp, color = COLOR_FORECAST_RAINY)
                    var finalLayout = textMeasurer.measure(nightText, finalPaintStyle)
                    val edgeMargin = 2f * scale
                    val halfWidth = finalLayout.size.width / 2f

                    var finalX = shiftedCenterX - halfWidth

                    val canShiftStandard = (shiftedCenterX + halfWidth <= size.width - edgeMargin) && (shiftedCenterX - halfWidth >= edgeMargin)
                    if (!canShiftStandard) {
                        // Try reduced scaling (extraScale = 0.85f)
                        val reducedStyle = TextStyle(fontSize = (11f * scale * DailyRainLabels.NIGHT_SCALE * nightFontScale * 0.85f).sp, color = COLOR_FORECAST_RAINY)
                        val reducedLayout = textMeasurer.measure(nightText, reducedStyle)
                        val reducedHalfWidth = reducedLayout.size.width / 2f
                        if (shiftedCenterX + reducedHalfWidth <= size.width - edgeMargin && shiftedCenterX - reducedHalfWidth >= edgeMargin) {
                            finalPaintStyle = reducedStyle
                            finalLayout = reducedLayout
                            finalX = shiftedCenterX - reducedHalfWidth
                        } else {
                            // Fallback to centered
                            finalX = (centerX - finalLayout.size.width / 2f).coerceIn(edgeMargin, size.width - finalLayout.size.width - edgeMargin)
                        }
                    }

                    val dynamicOverlapPx = dynamicOverlapDp * scale
                    val nightTopY = anchorBottomY - dynamicOverlapPx

                    // Collision check with the left low label
                    val leftLowX = centerX - lowText.size.width / 2f
                    val rightLowX = centerX + lowText.size.width / 2f

                    val nightLeft = finalX
                    val nightRight = finalX + finalLayout.size.width
                    val nightTop = nightTopY
                    val nightBottom = nightTopY + finalLayout.size.height

                    val intersects = (nightLeft < rightLowX && leftLowX < nightRight && nightTop < leftLowY + leftLowHeight && leftLowY < nightBottom)

                    val finalNightTopY = if (intersects && leftLowY > nightTopY) {
                        leftLowY
                    } else {
                        nightTopY
                    }

                    // Keep clear of the day-name row
                    val hardBottomLimit = size.height - dayLabelBand
                    val drawnTopY = finalNightTopY + roomyDownPx
                    if (drawnTopY + finalLayout.size.height <= hardBottomLimit) {
                        drawText(finalLayout, topLeft = Offset(finalX, drawnTopY))
                    }
                }
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

/**
 * Weather-adaptive forecast bar: a clear ([baseColor]) top over a cloud/rain bottom, where the
 * bottom segment's height is the cloud-cover ratio and its color is rain-blue vs cloud-grey.
 * Faithful port of Android's DailyForecastGraphRenderer.drawWeatherAdaptiveBar — two hard solid
 * segments (no fade), with the split math/colors single-sourced from shared WeatherColors. Falls
 * back to a solid [baseColor] bar when there's no cloud ratio (e.g. clear/fully-rainy days).
 * Nullable high/low (skips the bar when either is absent), mirroring [drawRangeLine].
 */
private fun DrawScope.drawAdaptiveBar(
    centerX: Float,
    high: Float?,
    low: Float?,
    yAt: (Float) -> Float,
    width: Float,
    baseColor: Color,
    cloudCoverRatio: Float?,
    iconCondition: String?,
) {
    if (high == null || low == null) return
    val highY = yAt(high)
    val lowY = yAt(low)

    val iconName = iconCondition?.let { WeatherConditionResolver.resolveIconName(it) }
    val ratio = cloudCoverRatio ?: iconName?.let { WeatherConditionResolver.cloudRatioFromIcon(it) }
    val isChanceOfRain = iconName?.let { WeatherConditionResolver.isChanceOfRainIcon(it) } ?: false
    val split = com.weatherwidget.shared.util.WeatherColors.mixedBarSplit(ratio, isChanceOfRain)

    if (split == null) {
        drawLine(
            color = baseColor,
            start = Offset(centerX, highY),
            end = Offset(centerX, lowY),
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
        return
    }

    val barHeight = lowY - highY
    val topEndY = (highY + barHeight * split.topFraction).coerceIn(highY, lowY)
    // Bottom (grey/blue) full-height first, then the clear (baseColor) top painted over it —
    // matches Android's two-segment draw order. Top color is the bar's own baseColor (e.g. the
    // bright snapshot yellow), not the split's gold, exactly as Android keeps its paint color.
    drawLine(
        color = Color(split.bottomColorArgb),
        start = Offset(centerX, highY),
        end = Offset(centerX, lowY),
        strokeWidth = width,
        cap = StrokeCap.Round,
    )
    if (topEndY - highY > 0.5f) {
        drawLine(
            color = baseColor,
            start = Offset(centerX, highY),
            end = Offset(centerX, topEndY),
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
    }
}

private fun forecastColor(day: DesktopDailyDay): Color {
    // Derive flags from the resolved+gated icon name so the bar color matches the displayed icon.
    val flags = WeatherConditionResolver.getConditionFlags(day.iconName)
    return when {
        flags.isRainy -> COLOR_FORECAST_RAINY
        flags.isMixed -> COLOR_FORECAST_SUNNY
        flags.isSunny -> COLOR_FORECAST_SUNNY
        day.cloudCoverRatio != null && day.cloudCoverRatio < 0.6f -> COLOR_FORECAST_SUNNY
        else -> COLOR_FORECAST_CLOUDY
    }
}

/** Layout inputs for daily-graph tap routing (icon bounds + bottom strip). */
internal data class DailyGraphTapLayout(
    val dayWidth: Float,
    val iconSize: Float,
    val iconTops: List<Float?>,
    val bottomStripHeightPx: Float,
    val canvasHeight: Float,
)

/**
 * Bottom strip height for daily graph tap routing (icon + low label + day name), mirroring Android's
 * `graph_bottom_day_zones` band.
 */
internal fun dailyGraphBottomStripHeightPx(
    canvasWidth: Float,
    dayCount: Int,
    scale: Float,
    density: Float,
): Float {
    val dayWidth = canvasWidth / dayCount.coerceAtLeast(1)
    val iconSize = (30f * density * scale).coerceAtMost(dayWidth * 0.6f)
    val lowLabelBand = 11f * scale * 1.4f + 4f * scale
    val dayLabelBand = labelSizeFor(dayWidth).toFloat() * scale * 1.5f + 6f * scale
    return lowLabelBand + iconSize + dayLabelBand + 6f * scale
}

/**
 * Computes per-column weather-icon tops using the same geometry as the draw loop so taps on the
 * rendered icon route to [DayClickResolver.DayTapZone.BOTTOM_ICON] even when the icon sits above
 * the fixed bottom strip (tall bars / cold lows).
 */
internal fun computeDailyGraphTapLayout(
    days: List<DesktopDailyDay>,
    canvasWidth: Float,
    canvasHeight: Float,
    scale: Float,
    density: Float,
    useCelsius: Boolean,
    measureLowLabelHeight: (text: String, baseSp: Float) -> Float = { _, base -> base * 1.4f },
): DailyGraphTapLayout {
    if (days.isEmpty()) {
        return DailyGraphTapLayout(1f, 0f, emptyList(), 0f, canvasHeight)
    }
    val allTemps = days.flatMap {
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
    val rangePad = ((rawMax - rawMin) * 0.04f).coerceAtLeast(1f)
    val minTemp = rawMin - rangePad
    val maxTemp = rawMax + rangePad
    val range = (maxTemp - minTemp).coerceAtLeast(1f)
    val dayWidth = canvasWidth / days.size
    val iconSize = (30f * density * scale).coerceAtMost(dayWidth * 0.6f)
    val lowLabelBand = 11f * scale * 1.4f + 4f * scale
    val dayLabelBand = labelSizeFor(dayWidth).toFloat() * scale * 1.5f + 6f * scale
    val top = 2f * scale
    val bottomReserve = lowLabelBand + iconSize + dayLabelBand + 6f * scale
    val bottom = (canvasHeight - bottomReserve).coerceAtLeast(canvasHeight * 0.4f)
    val graphHeight = (bottom - top).coerceAtLeast(1f)

    fun yAt(temp: Float): Float = top + graphHeight * (1f - (temp - minTemp) / range)

    val iconTops = days.map { day ->
        val lowForLabel = com.weatherwidget.shared.util.DailyDayValueResolver.effectiveLowForLabel(
            isToday = day.isToday,
            solidLow = day.solidLow,
            forecastLow = listOfNotNull(day.forecastLow, day.snapshotLow).minOrNull(),
            nowHour = day.nowHour,
        ) ?: return@map null
        val lowLabelText = formatTemp(lowForLabel, useCelsius)
        val lowTextHeight = measureLowLabelHeight(lowLabelText, 11f * scale)
        val anchorLow = com.weatherwidget.shared.util.DailyDayValueResolver.iconAnchorLow(
            solidLow = day.solidLow,
            forecastLow = day.forecastLow,
            snapshotLow = day.snapshotLow,
        ) ?: lowForLabel
        val iconTopMax = canvasHeight - dayLabelBand - lowTextHeight - 2f * scale - iconSize - 2f * scale
        (yAt(anchorLow) + 4f * scale).coerceAtMost(iconTopMax)
    }
    val bottomStripHeightPx = dailyGraphBottomStripHeightPx(canvasWidth, days.size, scale, density)
    return DailyGraphTapLayout(dayWidth, iconSize, iconTops, bottomStripHeightPx, canvasHeight)
}

internal fun classifyDailyGraphTapZone(
    tapX: Float,
    tapY: Float,
    columnIndex: Int,
    layout: DailyGraphTapLayout,
): DayClickResolver.DayTapZone {
    val iconTop = layout.iconTops.getOrNull(columnIndex)
    if (iconTop != null) {
        val centerX = layout.dayWidth * columnIndex + layout.dayWidth / 2f
        val half = layout.iconSize / 2f
        if (tapX in (centerX - half)..(centerX + half) && tapY in iconTop..(iconTop + layout.iconSize)) {
            return DayClickResolver.DayTapZone.BOTTOM_ICON
        }
    }
    return if (tapY >= layout.canvasHeight - layout.bottomStripHeightPx) {
        DayClickResolver.DayTapZone.BOTTOM_ICON
    } else {
        DayClickResolver.DayTapZone.MAIN_COLUMN
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
private fun formatTemp(v: Float?, useCelsius: Boolean): String {
    if (v == null) return ""
    return com.weatherwidget.shared.util.TempUtils.formatTemp(v, useCelsius) ?: ""
}

/** Temp-label font size: wide 3+ digit temps (100°, 97.7°) draw a further 5% smaller. */
private fun tempFontSize(text: String, base: Float): Float =
    base * (if (DualHighLabel.isWideLabel(text)) DualHighLabel.WIDE_LABEL_FONT_SCALE else 1f)

// When both past-day highs are labeled, the lower one (smaller temp) sits down where the taller
// forecast bar passes through it; bump it 4% larger for legibility. Desktop-only.
private const val LOWER_DUAL_LABEL_FONT_BOOST = 1.08f
