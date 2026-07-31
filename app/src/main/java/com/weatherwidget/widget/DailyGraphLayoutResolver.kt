package com.weatherwidget.widget

import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.remote.NwsTemperaturePlausibility.isPlausibleF
import com.weatherwidget.shared.graph.TodayColumnHighlight
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayData
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

internal data class DailyGraphLayoutInfo(
    val widthPx: Int,
    val heightPx: Int,
    val columns: Int,
    val minTemp: Float,
    val maxTemp: Float,
    val tempRange: Float,
    val scaleFactor: Float,
    val graphTop: Float,
    val graphBottom: Float,
    val graphHeight: Float,
    val dayWidth: Float,
    val tempLabelMaxWidthPx: Float,
    val horizontalPadding: Float,
    val tripleBarOffset: Float,
    val forecastBarOffset: Float,
    val iconSize: Int,
    val dayLabelHeight: Float,
    val tempLabelHeight: Float,
    val bulbRadius: Float,
    val bitmapScale: Float,
    val minBarHeightPx: Float,
    val dayLabelTextByDate: Map<LocalDate, String>,
    val density: Float,
    val useCelsius: Boolean,
) {
    fun tempToY(temp: Float): Float =
        (graphTop + graphHeight * (1 - (temp - minTemp) / tempRange))
            .coerceIn(graphTop, graphBottom)
}

@VisibleForTesting
internal data class DailyDayLabelInput(
    val date: LocalDate,
    val label: String,
    val isToday: Boolean = false,
)

@VisibleForTesting
internal data class DailyDayLabelLayoutResult(
    val textSizePx: Float,
    val textByDate: Map<LocalDate, String>,
    val scale: Float,
    val shortenedLabels: Boolean,
)

/** Resolves all daily-graph geometry from normalized data and primitive display inputs. */
internal object DailyGraphLayoutResolver {
    private const val TAG = "DailyGraphLayout"

    internal const val DAY_LABEL_SIZE_MULTIPLIER = 1.15f
    private const val DAY_LABEL_TEXT_SCALE = 1.5f
    private const val BASE_DAY_WIDTH_DP = 70f
    private const val MIN_DAY_LABEL_WIDTH_SCALE = 0.96f
    private const val MAX_DAY_LABEL_WIDTH_SCALE = 1.04f
    private const val MIN_DYNAMIC_DAY_LABEL_SCALE = 0.72f
    private const val DAY_LABEL_HORIZONTAL_GAP_DP = 4f
    private const val TEMP_LABEL_OVERLAP_ALLOWANCE_DP = 6f
    private const val MIN_BAR_HEIGHT_DP = 1f
    private const val TEMP_LABEL_TEXT_SIZE_DP = 24f
    private const val TOP_PADDING_DP = 50f
    private const val ICON_STACK_SPACING_DP = 4f
    private const val DAY_LABEL_BASE_SIZE_DP = 17f
    private const val ICON_BASE_SIZE_DP = 36f
    private const val COLUMN_EDGE_MARGIN_DP = 2f

    internal fun resolve(
        days: List<DayData>,
        widthPx: Int,
        heightPx: Int,
        columns: Int,
        bitmapScale: Float,
        density: Float,
        useCelsius: Boolean,
    ): DailyGraphLayoutInfo {
        var minTemp = Float.POSITIVE_INFINITY
        var maxTemp = Float.NEGATIVE_INFINITY
        var rejected = 0
        var firstRejected = Float.NaN

        fun include(value: Float?) {
            if (value == null) return
            if (!isPlausibleF(value)) {
                if (rejected == 0) firstRejected = value
                rejected++
                return
            }
            if (value < minTemp) minTemp = value
            if (value > maxTemp) maxTemp = value
        }

        days.forEach { day ->
            include(day.solidLineHigh)
            include(day.solidLineLow)
            include(day.dashedLineHigh)
            include(day.dashedLineLow)
            include(day.snapshotHigh)
            include(day.snapshotLow)
            include(day.ghostLineHigh)
        }
        if (rejected > 0) {
            Log.w(
                TAG,
                "resolve: excluded $rejected invalid temperature(s) from axis" +
                    " first=$firstRejected days=${days.size}",
            )
        }
        if (!minTemp.isFinite()) minTemp = 0f
        if (!maxTemp.isFinite()) maxTemp = 100f
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)

        val widthDp = widthPx / density
        val heightDp = heightPx / density
        val dayWidthDp = widthDp / columns
        val widthScaleFactor = (dayWidthDp / BASE_DAY_WIDTH_DP).coerceIn(1f, 1.2f)
        val dayLabelWidthScale = computeDayLabelWidthScale(dayWidthDp)
        val heightScaleFactor = if (heightDp < 150f) 0.92f else 1f
        val scaleFactor = widthScaleFactor
        val labelScale = bitmapScale.coerceAtMost(1f)
        val horizontalPadding = 0f
        val topPadding = (TOP_PADDING_DP * labelScale).dp(density)
        val dayLabelScale = labelScale * dayLabelWidthScale
        val baseDayLabelTextSizePx =
            (DAY_LABEL_BASE_SIZE_DP * dayLabelScale * DAY_LABEL_TEXT_SCALE).dp(density)
        val dayWidth = (widthPx - 2 * horizontalPadding) / columns
        val dayLabelLayout =
            resolveDayLabelLayout(
                labels = days.map { DailyDayLabelInput(it.date, it.label, it.isToday) },
                baseTextSizePx = baseDayLabelTextSizePx,
                maxTextWidthPx =
                    (dayWidth - (DAY_LABEL_HORIZONTAL_GAP_DP * labelScale).dp(density))
                        .coerceAtLeast(1f),
                minScale = MIN_DYNAMIC_DAY_LABEL_SCALE,
            )
        val dayLabelHeight = dayLabelLayout.textSizePx * DAY_LABEL_SIZE_MULTIPLIER
        val tempLabelHeight =
            dailyForecastTempLabelSizePx(density, heightScaleFactor, bitmapScale)
        val iconSize = (ICON_BASE_SIZE_DP * labelScale).dp(density).toInt()
        val attachedStackHeight =
            tempLabelHeight + iconSize + (ICON_STACK_SPACING_DP * labelScale).dp(density)
        val graphTop = topPadding
        val requestedGraphBottom = heightPx - dayLabelHeight - attachedStackHeight
        val graphBottom = requestedGraphBottom.coerceAtLeast(graphTop + 1f)
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        if (requestedGraphBottom < graphTop + 1f) {
            Log.w(
                TAG,
                "resolve: clamping undersized graph area widthPx=$widthPx heightPx=$heightPx" +
                    " graphTop=$graphTop requestedGraphBottom=$requestedGraphBottom" +
                    " graphBottom=$graphBottom",
            )
        }

        val barWidth =
            DailyBarRenderer.dailyBarStrokeWidthPx(density, scaleFactor, bitmapScale)
        val tripleBarWidth =
            DailyBarRenderer.todayTripleBarStrokeWidthPx(density, scaleFactor, bitmapScale)
        val tripleBarOffset =
            TodayColumnHighlight.tripleBarSpacing(
                centerBarWidthPx = tripleBarWidth,
                flankBarWidthPx = tripleBarWidth,
                dayWidthPx = dayWidth,
                columnEdgeMarginPx = COLUMN_EDGE_MARGIN_DP.dp(density),
            )

        if (dayLabelLayout.scale < 0.999f || dayLabelLayout.shortenedLabels) {
            Log.v(
                TAG,
                "dayLabel layout adjusted: widthPx=$widthPx columns=$columns dayWidth=$dayWidth" +
                    " baseTextSize=$baseDayLabelTextSizePx" +
                    " finalTextSize=${dayLabelLayout.textSizePx}" +
                    " scale=${dayLabelLayout.scale}" +
                    " shortened=${dayLabelLayout.shortenedLabels}" +
                    " labels=${dayLabelLayout.textByDate.values.joinToString(",")}",
            )
        }

        return DailyGraphLayoutInfo(
            widthPx = widthPx,
            heightPx = heightPx,
            columns = columns,
            minTemp = minTemp,
            maxTemp = maxTemp,
            tempRange = tempRange,
            scaleFactor = scaleFactor,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            dayWidth = dayWidth,
            tempLabelMaxWidthPx =
                dayWidth + (TEMP_LABEL_OVERLAP_ALLOWANCE_DP * labelScale).dp(density),
            horizontalPadding = horizontalPadding,
            tripleBarOffset = tripleBarOffset,
            forecastBarOffset = barWidth * DailyBarRenderer.FORECAST_BAR_OFFSET_SCALE,
            iconSize = iconSize,
            dayLabelHeight = dayLabelHeight,
            tempLabelHeight = tempLabelHeight,
            bulbRadius = tripleBarWidth * DailyBarRenderer.BULB_RADIUS_SCALE,
            bitmapScale = bitmapScale,
            minBarHeightPx = MIN_BAR_HEIGHT_DP.dp(density),
            dayLabelTextByDate = dayLabelLayout.textByDate,
            density = density,
            useCelsius = useCelsius,
        )
    }

    @VisibleForTesting
    internal fun resolveDayLabelLayout(
        labels: List<DailyDayLabelInput>,
        baseTextSizePx: Float,
        maxTextWidthPx: Float,
        minScale: Float = MIN_DYNAMIC_DAY_LABEL_SCALE,
    ): DailyDayLabelLayoutResult {
        if (labels.isEmpty() || baseTextSizePx <= 0f) {
            return DailyDayLabelLayoutResult(
                textSizePx = baseTextSizePx,
                textByDate = labels.associate { it.date to it.label },
                scale = 1f,
                shortenedLabels = false,
            )
        }

        val originalTextByDate = labels.associate { it.date to it.label }
        val originalScale =
            fittingScale(labels, originalTextByDate, baseTextSizePx, maxTextWidthPx)
        if (originalScale >= minScale) {
            return DailyDayLabelLayoutResult(
                textSizePx = baseTextSizePx * originalScale,
                textByDate = originalTextByDate,
                scale = originalScale,
                shortenedLabels = false,
            )
        }

        val shortenedTextByDate =
            labels.associate { input ->
                val label =
                    if (input.isToday) {
                        input.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    } else {
                        input.label
                    }
                input.date to label
            }
        val shortenedScale =
            fittingScale(labels, shortenedTextByDate, baseTextSizePx, maxTextWidthPx)
                .coerceAtLeast(minScale)
        return DailyDayLabelLayoutResult(
            textSizePx = baseTextSizePx * shortenedScale,
            textByDate = shortenedTextByDate,
            scale = shortenedScale,
            shortenedLabels = shortenedTextByDate != originalTextByDate,
        )
    }

    private fun fittingScale(
        labels: List<DailyDayLabelInput>,
        textByDate: Map<LocalDate, String>,
        baseTextSizePx: Float,
        maxTextWidthPx: Float,
    ): Float {
        val regularPaint = dayLabelMeasurePaint(baseTextSizePx, bold = false)
        val todayPaint = dayLabelMeasurePaint(baseTextSizePx, bold = true)
        val widest =
            labels.maxOfOrNull { input ->
                val text = textByDate[input.date].orEmpty()
                val paint = if (input.isToday) todayPaint else regularPaint
                DailyTemperatureLabelRenderer.measureTextWidth(paint, text)
            } ?: 0f
        if (widest <= 0f || widest <= maxTextWidthPx) return 1f
        return (maxTextWidthPx / widest).coerceAtMost(1f)
    }

    private fun dayLabelMeasurePaint(
        textSizePx: Float,
        bold: Boolean,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            textAlign = Paint.Align.CENTER
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    @VisibleForTesting
    internal fun computeDayLabelWidthScale(dayWidthDp: Float): Float =
        (dayWidthDp / BASE_DAY_WIDTH_DP)
            .coerceIn(MIN_DAY_LABEL_WIDTH_SCALE, MAX_DAY_LABEL_WIDTH_SCALE)

    @VisibleForTesting
    internal fun dailyForecastTempLabelSizePx(
        density: Float,
        heightScaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float {
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        return (TEMP_LABEL_TEXT_SIZE_DP * heightScaleFactor * labelScale).dp(density)
    }

    private fun Float.dp(density: Float): Float = this * density
}
