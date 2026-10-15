package com.weatherwidget.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.months.MonthsDailyJump
import com.weatherwidget.shared.months.MonthsSourceDepth
import com.weatherwidget.shared.months.SourceDepth
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.shared.months.MonthsDayValue
import com.weatherwidget.shared.months.MonthsGeometry
import com.weatherwidget.shared.months.MonthsGraphGeometry
import com.weatherwidget.shared.months.MonthsGraphInput
import com.weatherwidget.shared.months.MonthsGraphStyle
import com.weatherwidget.shared.months.MonthsSeriesBuilder
import com.weatherwidget.shared.months.MonthsSpan
import com.weatherwidget.shared.months.MonthsViewport
import com.weatherwidget.shared.months.MonthsWindow
import com.weatherwidget.shared.months.MonthsZoomAction
import com.weatherwidget.shared.months.MonthsZoomController
import java.time.LocalDate
import java.time.MonthDay

/** What the desktop months view shows; built from the popup's snapshot, which already holds 547 days. */
data class DesktopMonthsState(
    val span: MonthsSpan,
    val window: MonthsWindow,
    val today: LocalDate,
    val values: List<MonthsDayValue>,
    val bounds: ClosedRange<LocalDate>?,
    val normals: Map<MonthDay, Pair<Float, Float>>,
    /** Another visible source whose history here reaches much further back ([MonthsSourceDepth]). */
    val moreHistory: SourceDepth? = null,
)

/**
 * Desktop glue for the shared months view: reads the snapshot and turns shared zoom actions into
 * config. Every rule is in `:shared` (`com.weatherwidget.shared.months`).
 */
object DesktopMonthsModel {

    fun build(config: DesktopConfig, snapshot: ForecastSnapshot, today: LocalDate = LocalDate.now()): DesktopMonthsState {
        val values = MonthsSeriesBuilder.values(
            pastActuals = snapshot.raw.dailyActuals.mapKeys { LocalDate.parse(it.key) },
            forecasts = snapshot.raw.daily
                .filter { !it.isClimateNormal }
                .associate { LocalDate.parse(it.date) to (it.highTemp to it.lowTemp) },
            today = today,
        )
        val bounds = MonthsSeriesBuilder.bounds(values)
        val span = MonthsSpan.fromMonths(config.monthsSpan)
        val requestedEnd = config.monthsEnd?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: MonthsViewport.defaultEnd(today, bounds)
        val end = MonthsViewport.clampEnd(requestedEnd, span, bounds)
        val moreHistory = MonthsSourceDepth.suggestion(
            currentId = WeatherSource.fromDisplaySource(config.displaySource).id,
            depths = snapshot.raw.historyDepths,
            visibleIds = config.effectiveSources.map { WeatherSource.fromDisplaySource(it).id },
        )
        return DesktopMonthsState(
            span, MonthsViewport.window(span, end), today, values, bounds, snapshot.raw.climateNormals, moreHistory,
        )
    }

    /** "NWS has history back to Jun 2. Click to switch." — the year only when it is not this year. */
    fun moreHistoryText(depth: SourceDepth, today: LocalDate): String {
        val pattern = if (depth.first.year == today.year) "MMM d" else "MMM d, yyyy"
        val date = depth.first.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.getDefault()))
        return "${WeatherSource.fromId(depth.sourceId).shortDisplayName} has history back to $date. Click to switch."
    }

    /**
     * Applies a zoom action to [config]. The daily view's column count is asked per candidate offset
     * ([DesktopDailyForecastModel.displayColumns]): it draws one fewer while its Today overlay is on.
     */
    fun apply(
        action: MonthsZoomAction,
        config: DesktopConfig,
        today: LocalDate,
        dimensions: DesktopWidgetDimensions,
    ): DesktopConfig {
        val skipYesterday = NavigationUtils.shouldSkipYesterday(dimensions.cols)
        fun columnsAt(extraHistory: Int) = { offset: Int ->
            DesktopDailyForecastModel.displayColumns(
                config.copy(dateOffset = offset, dailyExtraHistory = extraHistory), dimensions, today,
            )
        }
        return when (action) {
            MonthsZoomAction.None, MonthsZoomAction.AddHistoryColumn -> config
            is MonthsZoomAction.OpenMonths ->
                config.copy(monthsView = true, monthsSpan = action.span.months, monthsEnd = action.endDate.toString())
            is MonthsZoomAction.SetSpan -> config.copy(monthsSpan = action.span.months)
            // Back in: keep the zoom-out history the user scrolled through; only the right edge moves
            // (zoom-out history is prepended on the left, so it never moves the right edge).
            is MonthsZoomAction.BackToDaily -> config.copy(
                monthsView = false,
                dateOffset = MonthsDailyJump.offsetWithRightEdge(
                    action.rightEdge, today, skipYesterday, columnsAt(config.dailyExtraHistory),
                ),
            )
            is MonthsZoomAction.OpenDaily -> config.copy(
                monthsView = false,
                dailyExtraHistory = 0,
                dateOffset = MonthsDailyJump.offsetCentring(action.date, today, skipYesterday, columnsAt(0)),
            )
        }
    }

    /** Pans by [days], clamped to the data. */
    fun pan(config: DesktopConfig, state: DesktopMonthsState, days: Int): DesktopConfig {
        val end = MonthsViewport.pan(state.window.end, days, state.span, state.bounds)
        return if (end == state.window.end) config else config.copy(monthsEnd = end.toString())
    }

    /** Days the ←/→ keys and nav arrows move the view. */
    const val KEY_PAN_DAYS = 7
}

/**
 * The months line graph: wheel zooms (shared [MonthsZoomController]), drag pans, click opens the daily
 * view on that day. Draws [MonthsGraphGeometry] only.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MonthsGraph(
    state: DesktopMonthsState,
    useCelsius: Boolean,
    scale: Float,
    onAction: (MonthsZoomAction) -> Unit,
    onPanDays: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Tapped the "another source has more history" line: switch to that source id. */
    onSwitchSource: (String) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val currentState by rememberUpdatedState(state)
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnPan by rememberUpdatedState(onPanDays)
    // Latest laid-out geometry, for hit-testing a click against exactly what was drawn.
    val lastGeometry = remember { arrayOfNulls<MonthsGeometry>(1) }

    androidx.compose.foundation.layout.Box(modifier) {
        Canvas(
            Modifier.matchParentSize()
                .testTag("months_graph")
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    val s = currentState
                    when {
                        dy > 0f -> currentOnAction(MonthsZoomController.monthsZoomOut(s.span))
                        dy < 0f -> currentOnAction(MonthsZoomController.monthsZoomIn(s.span, s.window))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val geometry = lastGeometry[0] ?: return@detectTapGestures
                        currentOnAction(MonthsZoomController.monthsTap(geometry.dateAt(offset.x)))
                    }
                }
                .pointerInput(Unit) {
                    var accumPx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { accumPx = 0f },
                        onDragEnd = { accumPx = 0f },
                        onDragCancel = { accumPx = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        val geometry = lastGeometry[0] ?: return@detectHorizontalDragGestures
                        accumPx += dragAmount
                        val days = MonthsViewport.dragDays(accumPx, geometry.plotWidth, currentState.window)
                        if (days != 0) {
                            accumPx += days * geometry.plotWidth / currentState.window.dayCount
                            currentOnPan(days)
                        }
                    }
                },
        ) {
            val unit = density * scale
            val geometry = MonthsGraphGeometry.compute(
                MonthsGraphInput(state.window, state.span, state.today, state.values, state.normals, useCelsius),
                size.width,
                size.height,
                unit,
            ) { text, sizePx -> textMeasurer.measure(text, TextStyle(fontSize = (sizePx / density).toSp())).size.width.toFloat() }
            lastGeometry[0] = geometry
            drawMonths(geometry, textMeasurer, unit, state.span)
        }
        state.moreHistory?.let { depth ->
            androidx.compose.material3.Text(
                text = DesktopMonthsModel.moreHistoryText(depth, state.today),
                color = Color(0xFF81D4FA),
                fontSize = (12f * scale).sp,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(bottom = (30f * scale).dp)
                    .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .clickable { onSwitchSource(depth.sourceId) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("months_more_history"),
            )
        }
    }
}

private fun argb(color: Int) = Color(color)

private fun DrawScope.drawMonths(g: MonthsGeometry, tm: TextMeasurer, unit: Float, span: MonthsSpan) {
    val s = MonthsGraphStyle
    val axisStyle = TextStyle(color = argb(s.AXIS_LABEL_COLOR), fontSize = (s.AXIS_LABEL_SIZE_DP * unit / density).toSp())

    // Normal band.
    if (g.normalHigh.size >= 2) {
        val band = Path().apply {
            moveTo(g.normalHigh.first().x, g.normalHigh.first().y)
            g.normalHigh.drop(1).forEach { lineTo(it.x, it.y) }
            g.normalLow.asReversed().forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(band, argb(s.NORMAL_BAND_COLOR))
    }

    // Horizontal grid + y labels.
    for (line in g.gridLines) {
        drawLine(argb(s.GRID_COLOR), Offset(g.plotLeft, line.y), Offset(g.plotRight, line.y), s.GRID_STROKE_DP * unit)
        val layout = tm.measure(line.label, axisStyle)
        drawText(layout, topLeft = Offset(g.plotLeft - layout.size.width - 4f * unit, line.y - layout.size.height / 2f))
    }

    // Month / day ticks.
    for (tick in g.ticks) {
        val color = argb(if (tick.major) s.MAJOR_TICK_COLOR else s.GRID_COLOR)
        drawLine(color, Offset(tick.x, g.plotTop), Offset(tick.x, g.plotBottom), s.GRID_STROKE_DP * unit)
        tick.label?.let {
            val layout = tm.measure(it, axisStyle)
            drawText(layout, topLeft = Offset(tick.x + 3f * unit, g.plotBottom + 4f * unit))
        }
    }

    // Today marker.
    g.todayX?.let {
        drawLine(argb(s.TODAY_COLOR), Offset(it, g.plotTop), Offset(it, g.plotBottom), s.TODAY_STROKE_DP * unit)
    }

    // Lines.
    val dash = PathEffect.dashPathEffect(floatArrayOf(s.DASH_ON_DP * unit, s.DASH_OFF_DP * unit), 0f)
    for (p in g.pieces) {
        drawLine(
            argb(p.color), Offset(p.x1, p.y1), Offset(p.x2, p.y2), s.LINE_STROKE_DP * unit,
            pathEffect = if (p.dashed) dash else null,
        )
    }
    for (d in g.dots) drawCircle(argb(d.color), s.DOT_RADIUS_DP * unit, Offset(d.x, d.y))

    // Extreme labels.
    for (label in g.labels) {
        val layout = tm.measure(label.text, TextStyle(color = argb(label.color), fontSize = (s.LABEL_SIZE_DP * unit / density).toSp()))
        drawText(layout, topLeft = Offset(label.x, label.baseline - layout.firstBaseline))
    }

    // Span caption, top right.
    val caption = tm.measure(if (span.months == 1) "1 month" else "${span.months} months", axisStyle)
    drawText(caption, topLeft = Offset(g.plotRight - caption.size.width, (g.plotTop - caption.size.height) / 2f))

    if (!g.hasData) {
        val empty = tm.measure("No daily history in this range", TextStyle(color = argb(s.EMPTY_TEXT_COLOR), fontSize = (13f * unit / density).toSp()))
        drawText(
            empty,
            topLeft = Offset((g.plotLeft + g.plotRight - empty.size.width) / 2f, (g.plotTop + g.plotBottom - empty.size.height) / 2f),
        )
    }
}
