package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.shared.graph.DualHighLabel
import com.weatherwidget.shared.graph.TodayColumnHighlight
import com.weatherwidget.shared.graph.TodayColumnOverlayBlocks
import com.weatherwidget.shared.graph.TodayColumnOverlayPlanner
import com.weatherwidget.shared.graph.TodayColumnOverlayStyle
import com.weatherwidget.shared.graph.WeightedColumnLayout
import com.weatherwidget.shared.actuals.TodayColumnOverlayContent
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

/** Base size of the desktop daily high temperature labels (+30%); see the `DESKTOP_TEMP_LABEL_BASE_SP * scale` call sites. */
private const val DESKTOP_TEMP_LABEL_BASE_SP = 14f

/** Base size of the desktop daily low temperature labels (+30%); see the `DESKTOP_LOW_TEMP_LABEL_BASE_SP * scale` call sites. */
private const val DESKTOP_LOW_TEMP_LABEL_BASE_SP = 14f

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
    // Zones the Today overlay used on the previous draw, feeding the planner's hysteresis so label
    // jitter cannot migrate a block between zones between frames. A plain remembered map (not
    // mutableStateOf) on purpose: it is written during draw and must not trigger recomposition.
    val overlayZoneMemo = remember { mutableMapOf<String, TodayColumnOverlayPlanner.Zone>() }
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
                    widenToday = state.largeTodayOverlayEnabled,
                    measureLowLabelHeight = { text, base ->
                        textMeasurer.measure(text, TextStyle(fontSize = tempFontSize(text, base).sp)).size.height.toFloat()
                    },
                )
                val index = layout.columns.indexAt(offset.x).coerceIn(0, displayDays.lastIndex)
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
        val todayColumnIndex = displayDays.indexOfFirst { it.isToday }.takeIf { it >= 0 }
        val columns =
            WeightedColumnLayout.resolve(
                totalWidth = size.width,
                columnCount = displayDays.size,
                todayColumnIndex = todayColumnIndex,
                widenToday = state.largeTodayOverlayEnabled,
            )
        val dayWidth = columns.normalWidth
        val iconSize = (30.dp.toPx() * scale).coerceAtMost(dayWidth * 0.6f)
        // Top: tiny reserve — the hottest bar runs to the top, and its high/rain labels ride up a
        // little past the canvas top into the header row (by design; the Canvas isn't clipped, so
        // the overflow paints over the header). Bottom: reserve enough for the low label + icon +
        // day name so those sit below the bars without overlapping them.
        val lowLabelBand = DESKTOP_LOW_TEMP_LABEL_BASE_SP * scale * 1.4f + 4f * scale
        val dayLabelBand = labelSizeFor(dayWidth) * scale * 1.5f + 6f * scale
        val top = 2f * scale
        // How far labels may ride above the canvas top, overlapping the header (~high-label height
        // + gap, so the hottest day's high label fully clears its bar).
        val headerBleed = DESKTOP_TEMP_LABEL_BASE_SP * scale * 1.4f + 4f * scale
        val bottomReserve = lowLabelBand + iconSize + dayLabelBand + 6f * scale
        val bottom = (size.height - bottomReserve).coerceAtLeast(size.height * 0.4f)
        val iconFloorTop = size.height - dayLabelBand - iconSize
        val graphHeight = (bottom - top).coerceAtLeast(1f)
        val barWidth = (7.dp.toPx() * scale).coerceAtMost(dayWidth * 0.22f)
        val thinWidth = barWidth * 0.65f
        val compactTodayBarWidth = (6.dp.toPx() * scale).coerceAtMost(dayWidth * 0.22f)
        // Touching triple bars, shared with Android. Desktop's flanking bars are thinner (thinWidth)
        // than the centre thermostat (barWidth), so the touching offset is their average — the shared
        // formula handles the unequal widths.
        val tripleOffset = TodayColumnHighlight.tripleBarSpacing(
            centerBarWidthPx = barWidth,
            flankBarWidthPx = thinWidth,
            dayWidthPx = dayWidth,
            columnEdgeMarginPx = 2.dp.toPx(),
        )
        val compactTodayTripleOffset = TodayColumnHighlight.tripleBarSpacing(
            centerBarWidthPx = compactTodayBarWidth,
            flankBarWidthPx = compactTodayBarWidth,
            dayWidthPx = todayColumnIndex?.let(columns.widths::get) ?: dayWidth,
            columnEdgeMarginPx = 2.dp.toPx(),
        )

        val todayHardObstacles = mutableListOf<TodayColumnOverlayPlanner.Bounds>()
        var todayBarTop = Float.NaN
        var todayBarBottom = Float.NaN
        val todayLeft = todayColumnIndex?.let(columns.lefts::get)
        val todayRight = todayColumnIndex?.let { columns.lefts[it] + columns.widths[it] }
        fun recordTodayObstacle(left: Float, obstacleTop: Float, right: Float, obstacleBottom: Float) {
            val panelLeft = todayLeft ?: return
            val panelRight = todayRight ?: return
            if (left < panelRight && panelLeft < right && obstacleTop < obstacleBottom) {
                todayHardObstacles += TodayColumnOverlayPlanner.Bounds(left, obstacleTop, right, obstacleBottom)
            }
        }

        fun yAt(temp: Float): Float = top + graphHeight * (1f - (temp - minTemp) / range)

        fun resolveLowLabelY(idx: Int): Float? {
            val d = displayDays.getOrNull(idx) ?: return null
            val lowVal = com.weatherwidget.shared.util.DailyDayValueResolver.effectiveLowForLabel(
                isToday = d.isToday,
                solidLow = d.solidLow,
                forecastLow = listOfNotNull(d.forecastLow, d.snapshotLow).minOrNull(),
                nowHour = d.nowHour,
                actualLow = d.actual?.computedLowTemp,
            ) ?: return null

            val lowLabelText = formatTemp(lowVal)
            val lowSize = tempFontSize(lowLabelText, DESKTOP_LOW_TEMP_LABEL_BASE_SP * scale)
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
            val columnWidth = columns.widths[index]
            val centerX = columns.centers[index]
            val baseColor = forecastColor(day)

            if (day.isToday) {
                // Frosted-glass focal panel behind the three today bars (shared geometry + fill with
                // Android). Drawn first so the bars, bulb, and labels sit on top of it.
                val panel = TodayColumnHighlight.panelBounds(
                    centerXPx = centerX,
                    tripleBarOffsetPx = compactTodayTripleOffset,
                    flankBarWidthPx = compactTodayBarWidth,
                    dayWidthPx = columnWidth,
                    graphTopPx = top,
                    canvasHeightPx = size.height,
                    dayLabelBandPx = dayLabelBand,
                    horizontalPaddingPx = TodayColumnHighlight.PANEL_HORIZONTAL_PADDING_DP.dp.toPx(),
                    topMarginPx = TodayColumnHighlight.PANEL_TOP_MARGIN_DP.dp.toPx(),
                )
                drawRoundRect(
                    color = Color(TodayColumnHighlight.PANEL_FILL_ARGB),
                    topLeft = Offset(panel.left, panel.top),
                    size = Size(panel.width, panel.height),
                    cornerRadius = CornerRadius(TodayColumnHighlight.PANEL_CORNER_RADIUS_DP.dp.toPx()),
                )

                val snapshotFlags = WeatherIcon.getConditionFlags(day.snapshot?.condition)
                val snapshotColor = com.weatherwidget.shared.util.WeatherColors
                    .snapshotBarOverrideArgb(snapshotFlags.isRainy)
                    ?.let { Color(it) } ?: Color.Yellow

                // Both today bars carry today's cloud-cover ratio (same day); the rain-vs-cloud
                // bottom color follows each bar's own condition (live forecast vs 24h-prior snapshot).
                drawAdaptiveBar(centerX + compactTodayTripleOffset, day.forecastHigh, day.forecastLow, ::yAt, compactTodayBarWidth, baseColor, day.cloudCoverRatio, day.iconCondition)
                drawAdaptiveBar(centerX - compactTodayTripleOffset, day.snapshotHigh, day.snapshotLow, ::yAt, compactTodayBarWidth, snapshotColor, day.cloudCoverRatio, day.snapshot?.condition)
                // The thermostat: solid red "mercury" (current temp), a faint ghost reaching up to
                // the day's high-water mark, and a round bulb at the low end. Drawn last so the
                // mercury and bulb sit on top of the forecast/snapshot bars.
                val solidHigh = day.solidHigh
                val ghostHigh = day.ghostHigh
                if (ghostHigh != null && solidHigh != null && ghostHigh > solidHigh) {
                    drawRangeLine(centerX, ghostHigh, solidHigh, ::yAt, COLOR_OBSERVED.copy(alpha = GHOST_BAR_ALPHA), compactTodayBarWidth)
                }
                drawRangeLine(centerX, day.solidHigh, day.solidLow, ::yAt, COLOR_OBSERVED, compactTodayBarWidth)
                day.solidLow?.let { low ->
                    val bulbRadius = compactTodayBarWidth * BULB_RADIUS_SCALE
                    drawCircle(
                        color = COLOR_OBSERVED,
                        radius = bulbRadius,
                        center = Offset(centerX, yAt(low) + bulbRadius * BULB_VERTICAL_CENTER_FRACTION),
                    )
                }
                val todayHighs = listOfNotNull(day.solidHigh, day.forecastHigh, day.snapshotHigh, day.ghostHigh)
                val todayLows = listOfNotNull(day.solidLow, day.forecastLow, day.snapshotLow)
                todayBarTop = todayHighs.maxOrNull()?.let(::yAt) ?: (top + graphHeight * 0.35f)
                todayBarBottom =
                    (todayLows.minOrNull()?.let(::yAt)?.plus(compactTodayBarWidth * BULB_RADIUS_SCALE * 1.5f)
                        ?: (bottom - graphHeight * 0.25f)).coerceAtMost(bottom)
            } else if (day.isPast) {
                // Forecast overlay carries the cloud/rain split (matches Android's past-day overlay);
                // the observed actual bar stays solid. A forecast-promoted past day (no actuals) has
                // no observation to paint red — its solid line reads as a forecast instead.
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
                val solidBarColor = if (day.solidIsForecastFallback) forecastColor(day) else COLOR_OBSERVED
                drawRangeLine(centerX, day.solidHigh, day.solidLow, ::yAt, solidBarColor, barWidth * 0.72f)
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
                actualLow = day.actual?.computedLowTemp,
            )

            // History — and today once its high is settled (past the 5pm cutoff) — label BOTH the
            // actual high (thermostat pink) and the forecast high (yellow, matching the forecast bar)
            // when they differ enough and there's room (DualHighLabel); otherwise fall through to the
            // single high label below. Today's actual is the observed peak (max of solid/ghost).
            // Horizontal anchor (matches Android): today centers BOTH high labels on the column
            // (centerX, like its single-high label) — its forecast bar sits a full +tripleOffset right
            // of the thermostat, so labeling over it would stagger the two stacked numbers; color marks
            // forecast vs actual. History keeps +tripleOffset (its overlay sits right beside the bar).
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
            // Matching Android: both highs take the 2% two-label shrink; the forecast — the
            // secondary number — shrinks further ONLY when the two labels would collide at full
            // size (DualHighLabel.forecastFontScale), decided inside the room-test block below.
            val dualBase = DESKTOP_TEMP_LABEL_BASE_SP * scale * DualHighLabel.TWO_LABEL_FONT_SCALE
            var dualForecastScale = 1f
            // Extra raise for the higher-valued label when the pair falls just short of the room
            // test, decided in the room-test block and reused verbatim by the draw below so both
            // measure the same positions. See DualHighLabel.extraUpperPushPx.
            var dualExtraPush = 0f
            // Role-based pinning (DualHighLabel): the actual always sits ON its own bar top; the
            // forecast pins to its own bar when it ran cooler (bottoms then follow bar tops and can
            // never cross) and keeps a raised gap when it ran hotter.
            val dualOffsets = if (dualActualHigh != null && dualForecastHigh != null)
                DualHighLabel.bottomOffsetsDp(dualActualHigh, dualForecastHigh, normalGapDp = DUAL_NORMAL_GAP)
            else null
            // Round caps extend a bar's ink half its stroke width ABOVE the geometric endpoint, so a
            // label pinned to the bare endpoint would overlap the cap. Pinned dualTop calls subtract
            // the matching bar's ink radius so the label bottom touches the visible ink top.
            val actualInkRadiusPx = if (day.isPast) barWidth * 0.72f / 2f else compactTodayBarWidth / 2f
            val forecastInkRadiusPx = if (day.isToday) compactTodayBarWidth / 2f else thinWidth / 2f
            val forecastPinnedInkRadiusPx =
                if (dualOffsets != null && dualOffsets.forecastDp == 0f) forecastInkRadiusPx else 0f
            // pushPx raises the label further (Y grows downward); the header clamp still wins, so a
            // push that would run off the top is simply absorbed and the room test sees that.
            fun dualTop(temp: Float, height: Float, offsetDp: Float, pushPx: Float = 0f, inkRadiusPx: Float = 0f): Float =
                (yAt(temp) + offsetDp * scale - height - pushPx - inkRadiusPx).coerceAtLeast(-headerBleed)
            // Only the higher-valued label is raised, so the pair can never cross.
            val dualActualIsUpper = dualActualHigh != null && dualForecastHigh != null &&
                dualActualHigh >= dualForecastHigh
            val showDualHighs = if (dualActualHigh != null && dualForecastHigh != null && dualOffsets != null) {
                val aText = formatTemp(dualActualHigh)
                val fText = formatTemp(dualForecastHigh)
                val aH = textMeasurer.measure(aText, TextStyle(fontSize = tempFontSize(aText, dualBase).sp)).size.height.toFloat()
                // Collision test at FULL size decides whether the forecast shrinks at all; the room
                // test then measures the boxes at the size they will actually be drawn.
                val fHFull = textMeasurer.measure(fText, TextStyle(fontSize = tempFontSize(fText, dualBase).sp)).size.height.toFloat()
                val aTop = dualTop(dualActualHigh, aH, dualOffsets.actualDp, inkRadiusPx = actualInkRadiusPx)
                val fTopFull = dualTop(dualForecastHigh, fHFull, dualOffsets.forecastDp, inkRadiusPx = forecastPinnedInkRadiusPx)
                dualForecastScale = DualHighLabel.forecastFontScale(aTop, fTopFull, maxOf(aH, fHFull))
                val fH = if (dualForecastScale == 1f) fHFull else
                    textMeasurer.measure(fText, TextStyle(fontSize = tempFontSize(fText, dualBase * dualForecastScale).sp)).size.height.toFloat()
                val fTop = dualTop(dualForecastHigh, fH, dualOffsets.forecastDp, inkRadiusPx = forecastPinnedInkRadiusPx)
                // A small miss leaves the pair nearly on top of each other no matter how the fixed
                // nudges are tuned; spend the empty space above the upper label rather than
                // dropping a label or printing the two numbers over each other. dualTop's
                // -headerBleed clamp is the hard ceiling, so no separate headroom cap is needed.
                dualExtraPush = DualHighLabel.extraUpperPushPx(
                    currentGapPx = kotlin.math.abs(aTop - fTop),
                    labelHeightPx = maxOf(aH, fH),
                    maxExtraPushPx = maxOf(aH, fH) * DualHighLabel.DUAL_UPPER_MAX_EXTRA_PUSH_FRACTION,
                )
                val aTopPushed = if (dualActualIsUpper) dualTop(dualActualHigh, aH, dualOffsets.actualDp, dualExtraPush, actualInkRadiusPx) else aTop
                val fTopPushed = if (dualActualIsUpper) fTop else dualTop(dualForecastHigh, fH, dualOffsets.forecastDp, dualExtraPush, forecastPinnedInkRadiusPx)
                DualHighLabel.showBoth(dualActualHigh, dualForecastHigh, aTopPushed, fTopPushed, maxOf(aH, fH))
            } else false

            if (showDualHighs && dualActualHigh != null && dualForecastHigh != null && dualOffsets != null) {
                val aText = formatTemp(dualActualHigh)
                val aSize = tempFontSize(aText, dualBase)
                val aLayout = textMeasurer.measure(aText, TextStyle(fontSize = aSize.sp, color = COLOR_OBSERVED))
                // Same pushed positions the room test measured (dualExtraPush applies to the
                // higher-valued label only).
                val aY = dualTop(
                    dualActualHigh,
                    aLayout.size.height.toFloat(),
                    dualOffsets.actualDp,
                    if (dualActualIsUpper) dualExtraPush else 0f,
                    actualInkRadiusPx,
                )
                val aX = centerX - aLayout.size.width / 2f
                drawOutlinedText(textMeasurer, aLayout, Offset(aX, aY))
                recordTodayObstacle(aX, aY, aX + aLayout.size.width, aY + aLayout.size.height)

                val fText = formatTemp(dualForecastHigh)
                val fSize = tempFontSize(fText, dualBase * dualForecastScale)
                val fLayout = textMeasurer.measure(fText, TextStyle(fontSize = fSize.sp, color = forecastColor(day)))
                val fY = dualTop(
                    dualForecastHigh,
                    fLayout.size.height.toFloat(),
                    dualOffsets.forecastDp,
                    if (dualActualIsUpper) 0f else dualExtraPush,
                    forecastPinnedInkRadiusPx,
                )
                val fLabelX = if (day.isToday) centerX else centerX + tripleOffset
                val fX = fLabelX - fLayout.size.width / 2f
                drawOutlinedText(textMeasurer, fLayout, Offset(fX, fY))
                recordTodayObstacle(fX, fY, fX + fLayout.size.width, fY + fLayout.size.height)
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
                    val highSize = tempFontSize(highLabelText, DESKTOP_TEMP_LABEL_BASE_SP * scale)
                    // Once today's high is settled (past 5pm) the single number tracks the observed
                    // actual — recolor it the thermostat (observed) color so it reads as a real
                    // reading, not a forecast. Mirrors the dual-label gate above (and Android).
                    val highColor = when {
                        todayHighSettled -> COLOR_OBSERVED
                        day.isToday -> Color.Yellow
                        day.isPast && !day.solidIsForecastFallback -> COLOR_OBSERVED
                        else -> Color.White
                    }
                    val highText = textMeasurer.measure(
                        highLabelText,
                        TextStyle(fontSize = highSize.sp, color = highColor)
                    )
                    // Sit above the bar top; for the hottest bar this rides up past the canvas top into
                    // the header (a little overlap is welcome) rather than dropping onto the bar.
                    // A genuine past actual pins to its bar top instead (zero padding, mirroring
                    // Android/DualHighLabel): a promoted-forecast fallback reads as a forecast and
                    // keeps the float. The ink radius lifts the pinned label onto the round cap's
                    // visible top instead of inside it.
                    val pinnedSingle = day.isPast && !day.solidIsForecastFallback
                    val singleGapScale = if (pinnedSingle) 0f else 3f
                    val singleInkRadiusPx = if (pinnedSingle) barWidth * 0.72f / 2f else 0f
                    val highLabelY = (yAt(singleHigh) - highText.size.height - singleGapScale * scale - singleInkRadiusPx).coerceAtLeast(-headerBleed)
                    highLabelTopAtCenter = highLabelY
                    val highTopLeft = Offset(centerX - highText.size.width / 2f, highLabelY)
                    // History and today get the thin outline (today's headline sits over the triple
                    // bars, like history's dual labels); future days stay plain.
                    if (day.isPast || day.isToday) drawOutlinedText(textMeasurer, highText, highTopLeft)
                    else drawText(highText, topLeft = highTopLeft)
                    recordTodayObstacle(
                        highTopLeft.x,
                        highTopLeft.y,
                        highTopLeft.x + highText.size.width,
                        highTopLeft.y + highText.size.height,
                    )
                }
            }
            if (lowForLabel != null) {
                val lowLabelText = formatTemp(lowForLabel)
                val lowSize = tempFontSize(lowLabelText, DESKTOP_LOW_TEMP_LABEL_BASE_SP * scale)
                // Matches the single-high recolor above: a past day's low is an actual reading, and
                // so is today's once the overnight low is settled (past the 9am cutoff) — both read
                // as the thermostat/observed color rather than plain white.
                val todayLowSettled = com.weatherwidget.shared.util.DailyDayValueResolver.isLowTrackingActual(
                    isToday = day.isToday,
                    solidLow = day.solidLow,
                    nowHour = day.nowHour,
                    // A forecast stand-in low (forecast-only sources) must never read as a
                    // settled actual — keep the label white unless an actual low exists.
                    actualLow = day.actual?.computedLowTemp,
                )
                val lowColor = if ((day.isPast && !day.solidIsForecastFallback) || todayLowSettled) COLOR_OBSERVED else Color.White.copy(alpha = 0.78f)
                val lowText = textMeasurer.measure(
                    lowLabelText,
                    TextStyle(fontSize = lowSize.sp, color = lowColor)
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
                recordTodayObstacle(centerX - iconSize / 2f, iconTop, centerX + iconSize / 2f, iconTop + iconSize)

                val lowLabelY = iconTop + iconSize + 2f * scale
                val lowTopLeft = Offset(centerX - lowText.size.width / 2f, lowLabelY)
                if (day.isPast) drawOutlinedText(textMeasurer, lowText, lowTopLeft)
                else drawText(lowText, topLeft = lowTopLeft)
                recordTodayObstacle(
                    lowTopLeft.x,
                    lowTopLeft.y,
                    lowTopLeft.x + lowText.size.width,
                    lowTopLeft.y + lowText.size.height,
                )
            }

            val dayText = textMeasurer.measure(
                day.label,
                TextStyle(fontSize = (labelSizeFor(columnWidth) * scale).sp, color = if (day.isToday) Color.Yellow else COLOR_LABEL_GRAY)
            )
            val dayTextTopLeft = Offset(centerX - dayText.size.width / 2f, size.height - dayText.size.height - 6f * scale)
            drawText(dayText, topLeft = dayTextTopLeft)
            recordTodayObstacle(
                dayTextTopLeft.x,
                dayTextTopLeft.y,
                dayTextTopLeft.x + dayText.size.width,
                dayTextTopLeft.y + dayText.size.height,
            )

            // Daytime rain label: sits on top of the bar, above the high-temp label.
            val rainText = day.dailyRainLabelText
            if (rainText != null) {
                // Probability-weighted (and, for future/today, distance-weighted) font size — the same
                // shared rule Android uses, applied to the desktop base size. History scales by
                // probability only (no distance term).
                val rainScale = DailyRainLabels.rainLabelFontScale(day.isPast, day.dayPrecipProbability, day.daysFromToday)
                val rainLayout = textMeasurer.measure(rainText, TextStyle(fontSize = (11.7f * scale * rainScale).sp, color = COLOR_FORECAST_RAINY))
                // Anchor to the high label's actual rendered top (shared rule: rain bottom = high top -
                // gap; negative gap = slight overlap). Falls back to a small inset only if no high label.
                val gapPx = DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP * scale
                val anchorY = highLabelTopAtCenter?.let { it - gapPx - rainLayout.size.height } ?: (top + 10f)
                // Stays above the high-temp label; may ride a little further into the header than it.
                val rainFloor = -headerBleed - rainLayout.size.height - 2f * scale
                val rainTopLeft = Offset(centerX - rainLayout.size.width / 2f, anchorY.coerceAtLeast(rainFloor))
                drawText(rainLayout, topLeft = rainTopLeft)
                recordTodayObstacle(
                    rainTopLeft.x,
                    rainTopLeft.y,
                    rainTopLeft.x + rainLayout.size.width,
                    rainTopLeft.y + rainLayout.size.height,
                )
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
                    val lowSize = tempFontSize(lowLabelText, DESKTOP_LOW_TEMP_LABEL_BASE_SP * scale)
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
                    val shiftedCenterX = centerX + columnWidth / 2f - hNudgePx + 1f * scale + roomyRightPx

                    // Base rain layout. Same probability/distance font scaling as the day label,
                    // times NIGHT_SCALE (history = probability only, no distance term).
                    val nightFontScale = DailyRainLabels.rainLabelFontScale(day.isPast, day.nightPrecipProbability, day.daysFromToday)
                    var finalPaintStyle = TextStyle(fontSize = (DESKTOP_LOW_TEMP_LABEL_BASE_SP * scale * DailyRainLabels.NIGHT_SCALE * nightFontScale).sp, color = COLOR_FORECAST_RAINY)
                    var finalLayout = textMeasurer.measure(nightText, finalPaintStyle)
                    val edgeMargin = 2f * scale
                    val halfWidth = finalLayout.size.width / 2f

                    var finalX = shiftedCenterX - halfWidth

                    val canShiftStandard = (shiftedCenterX + halfWidth <= size.width - edgeMargin) && (shiftedCenterX - halfWidth >= edgeMargin)
                    if (!canShiftStandard) {
                        // Try reduced scaling (extraScale = 0.85f)
                        val reducedStyle = TextStyle(fontSize = (DESKTOP_LOW_TEMP_LABEL_BASE_SP * scale * DailyRainLabels.NIGHT_SCALE * nightFontScale * 0.85f).sp, color = COLOR_FORECAST_RAINY)
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
                        recordTodayObstacle(
                            finalX,
                            drawnTopY,
                            finalX + finalLayout.size.width,
                            drawnTopY + finalLayout.size.height,
                        )
                    }
                }
            }
        }

        val overlayContent = state.todayOverlay
        if (
            state.largeTodayOverlayEnabled && overlayContent != null &&
            todayLeft != null && todayRight != null
        ) {
            drawDesktopTodayOverlay(
                textMeasurer = textMeasurer,
                content = overlayContent,
                columnLeft = todayLeft,
                columnRight = todayRight,
                graphTop = top,
                graphBottom = size.height - dayLabelBand,
                barTop = todayBarTop.takeIf(Float::isFinite) ?: (top + graphHeight * 0.35f),
                barBottom = todayBarBottom.takeIf(Float::isFinite) ?: (bottom - graphHeight * 0.25f),
                hardObstacles = todayHardObstacles,
                scale = scale,
                previousZones = overlayZoneMemo.toMap(),
                onZonesResolved = { resolved ->
                    overlayZoneMemo.clear()
                    overlayZoneMemo.putAll(resolved)
                },
            )
        }
    }
}

private data class DesktopOverlayRow(
    val value: String,
    val caption: String? = null,
) {
    fun displayText(): String = listOfNotNull(value, caption).joinToString(" ")
}

private data class DesktopOverlayBlock(
    val key: String,
    val rows: List<DesktopOverlayRow>,
)

private data class MeasuredDesktopOverlayBlock(
    val spec: DesktopOverlayBlock,
    val layout: TextLayoutResult,
)

private fun DrawScope.drawDesktopTodayOverlay(
    textMeasurer: TextMeasurer,
    content: TodayColumnOverlayContent,
    columnLeft: Float,
    columnRight: Float,
    graphTop: Float,
    graphBottom: Float,
    barTop: Float,
    barBottom: Float,
    hardObstacles: List<TodayColumnOverlayPlanner.Bounds>,
    scale: Float,
    previousZones: Map<String, TodayColumnOverlayPlanner.Zone>,
    onZonesResolved: (Map<String, TodayColumnOverlayPlanner.Zone>) -> Unit,
) {
    // Block selection (including the independent temp/age toggles) and the ordered content variants
    // for the planner's degradation ladder are pure and shared with the Android renderer via
    // TodayColumnOverlayBlocks.
    val variants =
        TodayColumnOverlayBlocks.variants(
            deltaValueText = content.deltaValueText,
            deltaCaptionText = content.deltaCaptionText,
            dominantTempText = content.dominantTempText,
            dominantAgeText = content.dominantAgeText,
        ).map { blocks ->
            blocks.map { block ->
                DesktopOverlayBlock(
                    key = block.key,
                    rows = block.rows.map { DesktopOverlayRow(it.text, it.caption) },
                )
            }
        }
    if (variants.isEmpty()) return

    val horizontalPadding = TodayColumnOverlayStyle.HORIZONTAL_PADDING_DP.dp.toPx()

    fun annotated(spec: DesktopOverlayBlock, fontSizeSp: Float): AnnotatedString =
        buildAnnotatedString {
            spec.rows.forEachIndexed { index, row ->
                if (index > 0) append("\n")
                append(row.value)
                row.caption?.let { caption ->
                    append(" ")
                    withStyle(SpanStyle(fontSize = (fontSizeSp * TodayColumnOverlayStyle.INLINE_CAPTION_TEXT_SCALE).sp)) {
                        append(caption)
                    }
                }
            }
        }

    fun layoutAt(spec: DesktopOverlayBlock, fontSize: Float): TextLayoutResult =
        textMeasurer.measure(
            text = annotated(spec, fontSize),
            style =
                TextStyle(
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize + TodayColumnOverlayStyle.ROW_SPACING_DP * scale).sp,
                    color = Color.White,
                ),
        )


    // The planner searches variant x zone x grouping in cost order and reports back which content
    // variant it settled on; text is always measured and drawn at the one fixed size (no font-shrink
    // ladder — removed at user request, matching Android). The old `combined` retry — merging every
    // block into one spec when a block failed to place — is gone: the planner lays the stack out as
    // a unit, which is what it approximated.
    val measuredCache = HashMap<Int, List<MeasuredDesktopOverlayBlock>>()
    fun measuredFor(variantIndex: Int): List<MeasuredDesktopOverlayBlock> =
        measuredCache.getOrPut(variantIndex) {
            // Sized against DESKTOP's own temp-label base (12f), not the raw shared dp: TEXT_SIZE_DP
            // is tuned for Android's 24dp temperature labels, so using it directly here made the
            // overlay 1.42x its neighbouring labels instead of 0.71x — visibly oversized.
            val fontSize =
                DESKTOP_TEMP_LABEL_BASE_SP *
                    TodayColumnOverlayStyle.TEXT_SIZE_FRACTION_OF_TEMP_LABEL *
                    1.2f *
                    scale
            variants[variantIndex].map { spec -> MeasuredDesktopOverlayBlock(spec, layoutAt(spec, fontSize)) }
        }

    val result =
        TodayColumnOverlayPlanner.layout(
            variantCount = variants.size,
            measureAt = { variantIndex ->
                measuredFor(variantIndex).map { block ->
                    TodayColumnOverlayPlanner.Line(
                        key = block.spec.key,
                        text = block.spec.rows.joinToString("\n", transform = DesktopOverlayRow::displayText),
                        width = block.layout.size.width.toFloat(),
                        height = block.layout.size.height.toFloat(),
                    )
                }
            },
            input =
                TodayColumnOverlayPlanner.Input(
                    columnLeft = columnLeft,
                    columnRight = columnRight,
                    graphTop = graphTop,
                    graphBottom = graphBottom,
                    barTop = barTop,
                    barBottom = barBottom,
                    hardObstacles = hardObstacles,
                    horizontalPadding = horizontalPadding,
                    padding = TodayColumnOverlayStyle.VERTICAL_PADDING_DP.dp.toPx(),
                    verticalStep = 2.dp.toPx(),
                    rowSpacing = TodayColumnOverlayStyle.ROW_SPACING_DP * scale,
                    previousZones = previousZones,
                    // Matches Android: `graphTop`/`graphBottom` are already the graph area's
                    // margins, so only the bar cap gets `padding`. See TodayColumnOverlayPlanner.
                    edgeInset = 0f,
                ),
        )
    val placements = result.placements
    if (!result.fromLastResort) {
        onZonesResolved(placements.associate { it.key to it.zone })
    }
    Log.v(
        TAG,
        "todayOverlay layout variant=${result.variantIndex}/${variants.size} " +
            "content=delta:${content.deltaValueText},temp:${content.dominantTempText},age:${content.dominantAgeText} " +
            "variants=${variants.map { v -> v.map { it.key } }} " +
            "lines=${measuredFor(result.variantIndex).map { "${it.spec.key}:${it.layout.size.width}x${it.layout.size.height}" }} " +
            "column=$columnLeft..$columnRight graph=$graphTop..$graphBottom bars=$barTop..$barBottom " +
            "obstacles=${hardObstacles.map { "${it.left},${it.top},${it.right},${it.bottom}" }} " +
            "prevZones=$previousZones " +
            "placements=${placements.map { "${it.key}:${it.zone}" }}",
    )

    val byKey = measuredFor(result.variantIndex).associateBy { it.spec.key }
    placements.forEach { placement ->
        val layout = byKey.getValue(placement.key).layout
        drawOutlinedText(textMeasurer, layout, Offset(placement.bounds.left, placement.bounds.top))
        Log.v(
            TAG,
            "todayOverlay placement key=${placement.key} zone=${placement.zone} text=${placement.text} " +
                "bounds=${placement.bounds.left},${placement.bounds.top},${placement.bounds.right},${placement.bounds.bottom}",
        )
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
    val columns: WeightedColumnLayout,
    val iconSize: Float,
    val iconTops: List<Float?>,
    val bottomStripHeightPx: Float,
    val canvasHeight: Float,
) {
    val dayWidth: Float get() = columns.normalWidth
}

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
    val lowLabelBand = DESKTOP_LOW_TEMP_LABEL_BASE_SP * scale * 1.4f + 4f * scale
    val dayLabelBand = labelSizeFor(dayWidth) * scale * 1.5f + 6f * scale
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
    widenToday: Boolean = false,
    measureLowLabelHeight: (text: String, baseSp: Float) -> Float = { _, base -> base * 1.4f },
): DailyGraphTapLayout {
    if (days.isEmpty()) {
        return DailyGraphTapLayout(
            WeightedColumnLayout.resolve(canvasWidth, 1, null, false),
            0f,
            emptyList(),
            0f,
            canvasHeight,
        )
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
    val todayIndex = days.indexOfFirst { it.isToday }.takeIf { it >= 0 }
    val columns = WeightedColumnLayout.resolve(canvasWidth, days.size, todayIndex, widenToday)
    val dayWidth = columns.normalWidth
    val iconSize = (30f * density * scale).coerceAtMost(dayWidth * 0.6f)
    val lowLabelBand = DESKTOP_LOW_TEMP_LABEL_BASE_SP * scale * 1.4f + 4f * scale
    val dayLabelBand = labelSizeFor(dayWidth) * scale * 1.5f + 6f * scale
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
            actualLow = day.actual?.computedLowTemp,
        ) ?: return@map null
        val lowLabelText = formatTemp(lowForLabel, useCelsius)
        val lowTextHeight = measureLowLabelHeight(lowLabelText, DESKTOP_LOW_TEMP_LABEL_BASE_SP * scale)
        val anchorLow = com.weatherwidget.shared.util.DailyDayValueResolver.iconAnchorLow(
            solidLow = day.solidLow,
            forecastLow = day.forecastLow,
            snapshotLow = day.snapshotLow,
        ) ?: lowForLabel
        val iconTopMax = canvasHeight - dayLabelBand - lowTextHeight - 2f * scale - iconSize - 2f * scale
        (yAt(anchorLow) + 4f * scale).coerceAtMost(iconTopMax)
    }
    val bottomStripHeightPx = dailyGraphBottomStripHeightPx(canvasWidth, days.size, scale, density)
    return DailyGraphTapLayout(columns, iconSize, iconTops, bottomStripHeightPx, canvasHeight)
}

internal fun classifyDailyGraphTapZone(
    tapX: Float,
    tapY: Float,
    columnIndex: Int,
    layout: DailyGraphTapLayout,
): DayClickResolver.DayTapZone {
    val iconTop = layout.iconTops.getOrNull(columnIndex)
    if (iconTop != null) {
        val centerX = layout.columns.centers[columnIndex]
        val half = layout.iconSize / 2f
        if (tapX in (centerX - half)..(centerX + half) && tapY in iconTop..(iconTop + layout.iconSize)) {
            return DayClickResolver.DayTapZone.BOTTOM_ICON
        }
    }
    return when {
        tapY >= layout.canvasHeight - layout.bottomStripHeightPx ->
            DayClickResolver.DayTapZone.BOTTOM_ICON
        // The nav chevrons centre on the canvas midpoint, so this is the line the user sees.
        // Checked after the icon rect above: an icon floated up here is still an aimed tap on a
        // glyph, not a body tap.
        tapY < layout.canvasHeight / 2f -> DayClickResolver.DayTapZone.MAIN_COLUMN_UPPER
        else -> DayClickResolver.DayTapZone.MAIN_COLUMN
    }
}

private fun labelSizeFor(dayWidth: Float): Float =
    when {
        dayWidth < 34f -> 9f
        dayWidth < 46f -> 10f
        else -> 11f
    }

// Show the tenth for any non-integer value (".0" suppressed by TempUtils.formatTemp), for
// forecasts/future and actuals alike — matches the Android daily view. NWS integer forecasts
// stay clean; climate normals and decimal sources reveal their tenth.
private fun formatTemp(v: Float?, useCelsius: Boolean): String {
    if (v == null) return ""
    return com.weatherwidget.shared.util.TempUtils.formatTemp(v, useCelsius) ?: ""
}

/** Temp-label font size: wide 3+ digit temps (100°, 97.7°) draw a further 10% smaller. */
private fun tempFontSize(text: String, base: Float): Float =
    base * (if (DualHighLabel.isWideLabel(text)) DualHighLabel.WIDE_LABEL_FONT_SCALE else 1f)

// Usual gap between a high label's bottom and its bar top, in the same units as `scale`. Fed to
// DualHighLabel.bottomOffsetsDp so an upper (ran-hot) forecast raises by the same room Android
// gives (Android's HIGH_LABEL_OFFSET_DP is 8); pinned labels are unaffected by it.
private const val DUAL_NORMAL_GAP = 3f
