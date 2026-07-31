package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.weatherwidget.shared.util.DailyDayValueResolver
import com.weatherwidget.widget.DailyForecastGraphRenderer.DailyRainLabelPlacement
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayData
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayLabelDrawnDebug

/** Draws the non-bar content of one resolved daily column. */
internal object DailyColumnRenderer {
    private val COLOR_LABEL_GRAY = 0xFFAAAAAA.toInt()
    private val COLOR_SUNNY = 0xFFFFD60A.toInt()
    private const val ICON_BELOW_BAR_SPACING_DP = 3f
    private const val TEMP_LABEL_SPACING_DP = -1f
    internal const val DAY_LABEL_BOTTOM_MARGIN_DP = 1f

    internal fun draw(
        canvas: Canvas,
        context: Context,
        day: DayData,
        rightNeighbor: DayData?,
        centerX: Float,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
        onRainLabelDrawn: ((DailyRainLabelPlacement) -> Unit)?,
        onDayLabelDrawn: ((DayLabelDrawnDebug) -> Unit)?,
    ) {
        val labelPaint = if (day.isToday) paints.todayTextPaint else paints.textPaint
        val dayLabel = layout.dayLabelTextByDate[day.date] ?: day.label
        val dayLabelBaseline =
            layout.heightPx - DAY_LABEL_BOTTOM_MARGIN_DP.dp(layout.density)
        canvas.drawText(dayLabel, centerX, dayLabelBaseline, labelPaint)
        val labelWidth = DailyTemperatureLabelRenderer.measureTextWidth(labelPaint, dayLabel)
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
        var ownLowLabelBox: DailyForecastRainLabelRenderer.LowLabelBox? = null

        if (lowY != null) {
            val iconY = lowY + ICON_BELOW_BAR_SPACING_DP.dp(layout.density)
            drawWeatherIcon(canvas, context, day, centerX, iconY, layout.iconSize)

            if (displayLow != null) {
                val lowTempY =
                    iconY +
                        layout.iconSize +
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
                    )
                val tempPaint =
                    when {
                        day.isToday -> paints.todayTempTextPaint
                        day.isPast -> paints.pastTempTextPaint
                        else -> paints.tempTextPaint
                    }
                val todayLowSettled =
                    DailyDayValueResolver.isLowTrackingActual(
                        isToday = day.isToday,
                        solidLow = day.solidLineLow,
                        nowHour = day.nowHour,
                    )
                val lowColorOverride =
                    if (todayLowSettled) DailyBarRenderer.COLOR_OBSERVED_RED else null
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
                ownLowLabelBox =
                    DailyForecastRainLabelRenderer.LowLabelBox(
                        left = centerX - lowHalfWidth,
                        top = lowTempY + TemperatureGraphStyle.fontAscent(tempPaint),
                        right = centerX + lowHalfWidth,
                        bottom = lowTempY + TemperatureGraphStyle.fontDescent(tempPaint),
                        baseline = lowTempY,
                    )
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
        DailyForecastRainLabelRenderer.drawNightRainLabel(
            day,
            rightNeighbor,
            centerX,
            layout,
            paints,
            ownLowLabelBox,
            onRainLabelDrawn,
            canvas,
        )
    }

    internal fun resolveLowLabelBaseline(
        day: DayData,
        layout: DailyGraphLayoutInfo,
    ): Float? {
        val lowTemp = resolveIconAnchorLow(day) ?: return null
        val lowY = layout.tempToY(lowTemp)
        val iconY = lowY + ICON_BELOW_BAR_SPACING_DP.dp(layout.density)
        return iconY +
            layout.iconSize +
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
        ) ?: day.bottomStackLow ?: day.solidLineLow

    private fun drawWeatherIcon(
        canvas: Canvas,
        context: Context,
        day: DayData,
        centerX: Float,
        iconY: Float,
        iconSize: Int,
    ) {
        val iconRes = day.iconRes ?: return
        val drawable = ContextCompat.getDrawable(context, iconRes)?.mutate() ?: return
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
    }

    private fun Float.dp(density: Float): Float = this * density
}
