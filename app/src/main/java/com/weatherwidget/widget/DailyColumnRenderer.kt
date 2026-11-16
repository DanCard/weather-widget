package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.weatherwidget.shared.graph.DailyRainLabelPlanner
import com.weatherwidget.shared.util.DailyDayValueResolver
import com.weatherwidget.widget.DailyForecastGraphRenderer.DailyRainLabelPlacement
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayData
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayLabelDrawnDebug
import kotlin.math.roundToInt

/** Draws the non-bar content of one resolved daily column. */
internal object DailyColumnRenderer {
    internal data class DrawnBounds(
        val dayLabel: RectF,
        val icon: RectF?,
        val lowLabel: RectF?,
        /** Measured ink of the icon, the low label (row bands) and the day name, for the night rain label placer. */
        val nightObstacles: List<DailyRainLabelPlanner.Box> = emptyList(),
        /** Ink of the whole low label; null when no low label is drawn. */
        val lowInk: DailyRainLabelPlanner.Box? = null,
        /** Top of the day name's ink: a night rain label's floor. */
        val dayNameInkTop: Float = dayLabel.top,
    )

    private val COLOR_LABEL_GRAY = 0xFFAAAAAA.toInt()
    private val COLOR_SUNNY = 0xFFFFD60A.toInt()
    private const val TEMP_LABEL_SPACING_DP = -1f
    internal const val DAY_LABEL_BOTTOM_MARGIN_DP = 1f

    internal fun draw(
        canvas: Canvas,
        context: Context,
        day: DayData,
        centerX: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
        onRainLabelDrawn: ((DailyRainLabelPlacement) -> Unit)?,
        onDayLabelDrawn: ((DayLabelDrawnDebug) -> Unit)?,
    ): DrawnBounds {
        val labelPaint = if (day.isToday) paints.todayTextPaint else paints.textPaint
        val dayLabel = layout.dayLabelTextByDate[day.date] ?: day.label
        val dayLabelBaseline =
            layout.heightPx - DAY_LABEL_BOTTOM_MARGIN_DP.dp(layout.density)
        canvas.drawText(dayLabel, centerX, dayLabelBaseline, labelPaint)
        val labelWidth = DailyTemperatureLabelRenderer.measureTextWidth(labelPaint, dayLabel)
        val dayLabelBounds =
            RectF(
                centerX - labelWidth / 2f,
                dayLabelBaseline + TemperatureGraphStyle.fontAscent(labelPaint),
                centerX + labelWidth / 2f,
                dayLabelBaseline + TemperatureGraphStyle.fontDescent(labelPaint),
            )
        val dayNameInk =
            TextInk.bounds(labelPaint, dayLabel)?.let {
                val left = centerX - labelWidth / 2f
                DailyRainLabelPlanner.Box(left + it.left, dayLabelBaseline + it.top, left + it.right, dayLabelBaseline + it.bottom)
            } ?: dayLabelBounds.toBox()
        val dayNameInkTop = dayNameInk.top
        onDayLabelDrawn?.invoke(
            DayLabelDrawnDebug(
                date = day.date,
                text = dayLabel,
                centerX = centerX,
                baselineY = dayLabelBaseline,
                leftX = centerX - labelWidth / 2f,
                rightX = centerX + labelWidth / 2f,
                textSize = labelPaint.textSize,
            ),
        )

        val anchorLow = resolveIconAnchorLow(day)
        val displayLow = resolveBottomStackLow(day)
        val lowY = anchorLow?.let(layout::tempToY)
        var lowLabelBounds: RectF? = null
        var lowLabelObstacles: List<DailyRainLabelPlanner.Box> = emptyList()
        var iconBounds: RectF? = null
        var iconInk: List<DailyRainLabelPlanner.Box> = emptyList()

        if (lowY != null) {
            val iconY = layout.bottomStack.iconTop(lowY)
            iconInk = drawWeatherIcon(canvas, context, day, centerX, iconY, layout.iconSize, com.weatherwidget.shared.graph.InkScan.NIGHT_OBSTACLE_BAND_PX)
            if (day.iconRes != null) {
                iconBounds =
                    RectF(
                        centerX - layout.iconSize / 2f,
                        iconY,
                        centerX + layout.iconSize / 2f,
                        iconY + layout.iconSize,
                    )
            }

            if (displayLow != null) {
                val lowTempY =
                    layout.bottomStack.lowLabelTop(iconY) +
                        layout.tempLabelHeight +
                        TEMP_LABEL_SPACING_DP.dp(layout.density)
                val forceInteger =
                    displayLow <= layout.minTemp + 0.01f &&
                        day.rainData.nightRainLabelText != null
                val lowLabelText =
                    DailyTemperatureLabelRenderer.format(
                        displayLow,
                        forceInteger = forceInteger,
                        useCelsius = layout.useCelsius,
                        wholeDegrees = day.printsWholeDegrees,
                    )
                val tempPaint =
                    when {
                        day.isToday -> paints.todayTempTextPaint
                        // A forecast-promoted past day reads as a forecast, not an observed actual.
                        day.isPast && !day.solidIsForecastFallback -> paints.pastTempTextPaint
                        else -> paints.tempTextPaint
                    }
                // Thermostat color whenever the printed number IS today's observed low —
                // before the low settles too, when the night already went colder than forecast.
                val todayLowIsActual =
                    DailyDayValueResolver.isLowLabelActual(
                        isToday = day.isToday,
                        printedLow = displayLow,
                        // A forecast stand-in low (forecast-only sources) must never read
                        // as an actual — keep the label white unless an actual low
                        // was genuinely observed.
                        actualLow = if (day.todayHasActualLow) day.solidLineLow else null,
                    )
                val lowColorOverride =
                    if (todayLowIsActual) DailyBarRenderer.COLOR_OBSERVED_RED else null
                DailyTemperatureLabelRenderer.draw(
                    canvas = canvas,
                    text = lowLabelText,
                    centerX = centerX,
                    baselineY = lowTempY,
                    base = tempPaint,
                    colorOverride = lowColorOverride,
                    drawOutline = day.isPast,
                    maxWidthPx = layout.tempLabelMaxWidthPx,
                )

                val lowHalfWidth =
                    DailyTemperatureLabelRenderer.measureTextWidth(tempPaint, lowLabelText) / 2f
                lowLabelBounds =
                    RectF(
                        centerX - lowHalfWidth,
                        lowTempY + TemperatureGraphStyle.fontAscent(tempPaint),
                        centerX + lowHalfWidth,
                        lowTempY + TemperatureGraphStyle.fontDescent(tempPaint),
                    )
                lowLabelObstacles =
                    DailyTemperatureLabelRenderer.inkBoxes(
                        text = lowLabelText,
                        centerX = centerX,
                        baselineY = lowTempY,
                        base = tempPaint,
                        drawOutline = day.isPast,
                        maxWidthPx = layout.tempLabelMaxWidthPx,
                        bandPx = com.weatherwidget.shared.graph.InkScan.NIGHT_OBSTACLE_BAND_PX,
                    ) ?: listOf(lowLabelBounds.toBox())
            }
        }

        DailyForecastRainLabelRenderer.drawDailyRainLabel(
            day,
            centerX,
            layout,
            paints,
            onRainLabelDrawn,
            canvas,
        )
        // The night rain label is drawn by the facade's second pass, once the right neighbour's
        // low label and icon exist to collide with (DailyForecastRainLabelRenderer.drawNightRainLabels).
        return DrawnBounds(
            dayLabel = dayLabelBounds,
            icon = iconBounds,
            lowLabel = lowLabelBounds,
            nightObstacles = iconInk + lowLabelObstacles + dayNameInk,
            lowInk =
                lowLabelObstacles.takeIf { it.isNotEmpty() }?.let { boxes ->
                    DailyRainLabelPlanner.Box(
                        boxes.minOf { it.left },
                        boxes.minOf { it.top },
                        boxes.maxOf { it.right },
                        boxes.maxOf { it.bottom },
                    )
                },
            dayNameInkTop = dayNameInkTop,
        )
    }

    private fun RectF.toBox() = DailyRainLabelPlanner.Box(left, top, right, bottom)

    internal fun resolveLowLabelBaseline(
        day: DayData,
        layout: DailyGraphLayoutInfo,
    ): Float? {
        val lowTemp = resolveIconAnchorLow(day) ?: return null
        val lowY = layout.tempToY(lowTemp)
        val iconY = layout.bottomStack.iconTop(lowY)
        return layout.bottomStack.lowLabelTop(iconY) +
            layout.tempLabelHeight +
            TEMP_LABEL_SPACING_DP.dp(layout.density)
    }

    internal fun resolveBottomStackLow(day: DayData): Float? =
        day.bottomStackLow ?: day.solidLineLow

    internal fun resolveIconAnchorLow(day: DayData): Float? =
        DailyDayValueResolver.iconAnchorLow(
            solidLow = day.solidLineLow,
            forecastLow = day.dashedLineLow,
            snapshotLow = day.snapshotLow,
            solidHigh = day.solidLineHigh,
            snapshotBarDrawn = day.isToday || day.isPast,
        ) ?: day.bottomStackLow ?: day.solidLineLow

    /** Draws the day's icon; returns its opaque pixels as row bands in canvas coordinates. */
    private fun drawWeatherIcon(
        canvas: Canvas,
        context: Context,
        day: DayData,
        centerX: Float,
        iconY: Float,
        iconSize: Int,
        bandPx: Int,
    ): List<DailyRainLabelPlanner.Box> {
        val iconRes = day.iconRes ?: return emptyList()
        val drawable = ContextCompat.getDrawable(context, iconRes)?.mutate() ?: return emptyList()
        val iconX = centerX - iconSize / 2f
        drawable.setBounds(
            iconX.toInt(),
            iconY.toInt(),
            (iconX + iconSize).toInt(),
            (iconY + iconSize).toInt(),
        )
        if (!day.isRainy && !day.isMixed) {
            drawable.setTint(if (day.isSunny) COLOR_SUNNY else COLOR_LABEL_GRAY)
        }
        drawable.draw(canvas)
        val left = iconX.toInt().toFloat()
        val top = iconY.toInt().toFloat()
        return TextInk.iconBands("$iconRes", drawable, iconSize, bandPx).map {
            DailyRainLabelPlanner.Box(left + it.left, top + it.top, left + it.right, top + it.bottom)
        }
    }

}
