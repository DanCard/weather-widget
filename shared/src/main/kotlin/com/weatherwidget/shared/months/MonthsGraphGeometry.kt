package com.weatherwidget.shared.months

import com.weatherwidget.shared.graph.AxisScale
import com.weatherwidget.shared.graph.NiceAxisScale
import com.weatherwidget.shared.util.TempUtils
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything the months view needs to draw one frame. Values are °F; geometry is in display units. */
data class MonthsGraphInput(
    val window: MonthsWindow,
    val span: MonthsSpan,
    val today: LocalDate,
    val values: List<MonthsDayValue>,
    val normals: Map<MonthDay, Pair<Float, Float>>,
    val useCelsius: Boolean,
)

/** Pixel geometry for one frame. Platforms only walk this and issue draw calls. */
data class MonthsGeometry(
    val plotLeft: Float,
    val plotTop: Float,
    val plotRight: Float,
    val plotBottom: Float,
    val window: MonthsWindow,
    /** One straight piece per pair of adjacent days, coloured by its mean temperature. */
    val pieces: List<Piece>,
    /** Days with no neighbour on that line — drawn as a dot so they are not lost. */
    val dots: List<Dot>,
    /** The climate-normal band: high edge left→right; fill to [normalLow] reversed. */
    val normalHigh: List<Pt>,
    val normalLow: List<Pt>,
    val gridLines: List<GridLine>,
    val ticks: List<XTick>,
    val todayX: Float?,
    val labels: List<PlacedLabel>,
    val hasData: Boolean,
) {
    data class Pt(val x: Float, val y: Float)
    data class Piece(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val dashed: Boolean)
    data class Dot(val x: Float, val y: Float, val color: Int)
    data class GridLine(val y: Float, val label: String)
    data class XTick(val x: Float, val label: String?, val major: Boolean)

    /** [x] is the label's left edge, [baseline] its text baseline. */
    data class PlacedLabel(val text: String, val x: Float, val baseline: Float, val color: Int)

    val plotWidth: Float get() = plotRight - plotLeft

    fun dateAt(x: Float): LocalDate = MonthsViewport.dateAtX(x, window, plotLeft, plotWidth)
}

object MonthsGraphGeometry {

    /**
     * @param density px per dp
     * @param measureText width in px of a string at a text size in px (platform font metrics)
     */
    fun compute(
        input: MonthsGraphInput,
        widthPx: Float,
        heightPx: Float,
        density: Float,
        measureText: (text: String, sizePx: Float) -> Float,
    ): MonthsGeometry {
        val s = MonthsGraphStyle
        val left = s.PADDING_LEFT_DP * density
        val top = s.PADDING_TOP_DP * density
        val right = widthPx - s.PADDING_RIGHT_DP * density
        val bottom = heightPx - s.PADDING_BOTTOM_DP * density
        val plotWidth = max(right - left, 1f)
        val plotHeight = max(bottom - top, 1f)
        val window = input.window
        val display = { f: Float -> TempUtils.display(f, input.useCelsius) }

        val visible = input.values.filter { it.date in window }
        val windowNormals = window.dates().mapNotNull { date -> input.normals[MonthDay.from(date)]?.let { date to it } }.toList()
        val temps = visible.flatMap { listOfNotNull(it.high, it.low) } +
            windowNormals.flatMap { listOf(it.second.first, it.second.second) }
        val scale: AxisScale = if (temps.isEmpty()) {
            NiceAxisScale.compute(display(40f), display(80f), targetTickCount = 4)
        } else {
            NiceAxisScale.compute(display(temps.min()), display(temps.max()), targetTickCount = 4, minRange = 10f)
        }
        fun x(date: LocalDate) = MonthsViewport.xForDate(date, window, left, plotWidth)
        fun y(tempF: Float) = scale.valueToY(display(tempF), top, plotHeight)

        val pieces = mutableListOf<MonthsGeometry.Piece>()
        val dots = mutableListOf<MonthsGeometry.Dot>()
        for (line in MonthsLine.entries) {
            for (segment in MonthsSeriesBuilder.segments(visible, line)) {
                if (segment.points.size == 1) {
                    val (date, t) = segment.points.single()
                    dots += MonthsGeometry.Dot(x(date), y(t), s.lineColor(t))
                    continue
                }
                segment.points.zipWithNext { (d1, t1), (d2, t2) ->
                    pieces += MonthsGeometry.Piece(x(d1), y(t1), x(d2), y(t2), s.lineColor((t1 + t2) / 2f), segment.dashed)
                }
            }
        }

        val gridLines = scale.ticks.map { tick ->
            MonthsGeometry.GridLine(scale.valueToY(tick, top, plotHeight), "${tick.roundToInt()}°")
        }
        val ticks = MonthsAxis.ticks(window, input.span).map { MonthsGeometry.XTick(x(it.date) - plotWidth / window.dayCount / 2f, it.label, it.major) }
        val todayX = if (input.today in window) x(input.today) else null

        val labels = placeLabels(visible, window, ::x, ::y, input.useCelsius, density, measureText, left, right, top, bottom)

        return MonthsGeometry(
            plotLeft = left,
            plotTop = top,
            plotRight = right,
            plotBottom = bottom,
            window = window,
            pieces = pieces,
            dots = dots,
            normalHigh = windowNormals.map { (date, n) -> MonthsGeometry.Pt(x(date), y(n.first)) },
            normalLow = windowNormals.map { (date, n) -> MonthsGeometry.Pt(x(date), y(n.second)) },
            gridLines = gridLines,
            ticks = ticks,
            todayX = todayX,
            labels = labels,
            hasData = visible.isNotEmpty(),
        )
    }

    /** One label candidate: a month's extreme high or low. */
    internal data class LabelCandidate(val date: LocalDate, val tempF: Float, val line: MonthsLine, val priority: Int)

    /**
     * Each calendar month's highest high and lowest low in the window. The window's overall extremes
     * come first, then months in order; months with under a week on screen are skipped.
     */
    internal fun labelCandidates(values: List<MonthsDayValue>, window: MonthsWindow): List<LabelCandidate> {
        val byMonth = values.filter { it.date in window }.groupBy { YearMonth.from(it.date) }
        val candidates = mutableListOf<LabelCandidate>()
        for ((month, days) in byMonth) {
            val first = maxOf(month.atDay(1), window.start)
            val last = minOf(month.atEndOfMonth(), window.end)
            if (java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1 < MIN_DAYS_FOR_MONTH_LABEL) continue
            days.filter { it.high != null }.maxByOrNull { it.high!! }?.let {
                candidates += LabelCandidate(it.date, it.high!!, MonthsLine.HIGH, 1)
            }
            days.filter { it.low != null }.minByOrNull { it.low!! }?.let {
                candidates += LabelCandidate(it.date, it.low!!, MonthsLine.LOW, 1)
            }
        }
        val top = candidates.filter { it.line == MonthsLine.HIGH }.maxByOrNull { it.tempF }
        val bottom = candidates.filter { it.line == MonthsLine.LOW }.minByOrNull { it.tempF }
        return candidates
            .map { if (it == top || it == bottom) it.copy(priority = 0) else it }
            .sortedWith(compareBy<LabelCandidate> { it.priority }.thenBy { it.date })
    }

    private const val MIN_DAYS_FOR_MONTH_LABEL = 7

    /** Greedy: highest priority first; a label that would overlap one already placed is dropped. */
    private fun placeLabels(
        values: List<MonthsDayValue>,
        window: MonthsWindow,
        x: (LocalDate) -> Float,
        y: (Float) -> Float,
        useCelsius: Boolean,
        density: Float,
        measureText: (String, Float) -> Float,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
    ): List<MonthsGeometry.PlacedLabel> {
        val sizePx = MonthsGraphStyle.LABEL_SIZE_DP * density
        val gap = MonthsGraphStyle.LABEL_GAP_DP * density
        val textHeight = sizePx * TEXT_HEIGHT_RATIO
        val placed = mutableListOf<Pair<MonthsGeometry.PlacedLabel, FloatArray>>()
        for (candidate in labelCandidates(values, window)) {
            val text = TempUtils.formatTemp(candidate.tempF, useCelsius) ?: continue
            val width = measureText(text, sizePx)
            val px = x(candidate.date)
            val py = y(candidate.tempF)
            val labelLeft = (px - width / 2f).coerceIn(plotLeft, max(plotLeft, plotRight - width))
            val baseline = if (candidate.line == MonthsLine.HIGH) py - gap else py + gap + textHeight
            val rect = floatArrayOf(labelLeft, baseline - textHeight, labelLeft + width, baseline)
            if (rect[1] < plotTop - textHeight || rect[3] > plotBottom + textHeight) continue
            if (placed.any { overlaps(it.second, rect) }) continue
            placed += MonthsGeometry.PlacedLabel(text, labelLeft, baseline, MonthsGraphStyle.lineColor(candidate.tempF)) to rect
        }
        return placed.map { it.first }
    }

    private const val TEXT_HEIGHT_RATIO = 0.8f

    private fun overlaps(a: FloatArray, b: FloatArray): Boolean =
        min(a[2], b[2]) > max(a[0], b[0]) && min(a[3], b[3]) > max(a[1], b[1])

    private fun MonthsWindow.dates(): Sequence<LocalDate> = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
}
