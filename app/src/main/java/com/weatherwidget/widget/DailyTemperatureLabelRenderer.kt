package com.weatherwidget.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.weatherwidget.shared.graph.DualHighLabel
import com.weatherwidget.shared.util.TempUtils
import kotlin.math.roundToInt

/** Shared temperature-label formatting, fitting, measurement, and Canvas drawing for daily graphs. */
internal object DailyTemperatureLabelRenderer {
    private const val MIN_TEMP_LABEL_FIT_SCALE = 0.7f
    private const val LABEL_OUTLINE_STROKE_FRACTION = 0.12f

    internal fun draw(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        base: Paint,
        extraScale: Float = 1f,
        colorOverride: Int? = null,
        drawOutline: Boolean = false,
        maxWidthPx: Float = Float.MAX_VALUE,
    ): RectF {
        val scale = drawScale(base, text, extraScale, maxWidthPx)
        val paint =
            if (colorOverride == null && scale == 1f) {
                base
            } else {
                Paint(base).apply {
                    if (colorOverride != null) color = colorOverride
                    textSize = base.textSize * scale
                }
            }
        if (drawOutline) {
            val outlinePaint =
                Paint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = paint.textSize * LABEL_OUTLINE_STROKE_FRACTION
                    color = 0xFF000000.toInt()
                    clearShadowLayer()
                }
            canvas.drawText(text, centerX, baselineY, outlinePaint)
        }
        canvas.drawText(text, centerX, baselineY, paint)
        val halfWidth = measureTextWidth(paint, text) / 2f
        return RectF(
            centerX - halfWidth,
            baselineY + TemperatureGraphStyle.fontAscent(paint),
            centerX + halfWidth,
            baselineY + TemperatureGraphStyle.fontDescent(paint),
        )
    }

    internal fun fitScaleForWidth(
        measuredWidthAtScale: Float,
        maxWidthPx: Float,
        currentScale: Float,
        minScale: Float = MIN_TEMP_LABEL_FIT_SCALE,
    ): Float {
        if (maxWidthPx <= 0f || measuredWidthAtScale <= maxWidthPx) return currentScale
        return (currentScale * (maxWidthPx / measuredWidthAtScale))
            .coerceAtLeast(currentScale * minScale)
    }

    internal fun drawScale(
        base: Paint,
        text: String,
        extraScale: Float,
        maxWidthPx: Float,
    ): Float {
        val baseScale =
            extraScale *
                if (DualHighLabel.isWideLabel(text)) {
                    DualHighLabel.WIDE_LABEL_FONT_SCALE
                } else {
                    1f
                }
        return fitScaleForWidth(
            measuredWidthAtScale = measureTextWidth(base, text) * baseScale,
            maxWidthPx = maxWidthPx,
            currentScale = baseScale,
        )
    }

    internal fun measureTextWidth(
        paint: Paint,
        text: String,
    ): Float {
        val measured = paint.measureText(text)
        if (measured > 0f) return measured
        return text.length * paint.textSize * 0.55f
    }

    internal fun format(
        value: Float,
        forceInteger: Boolean = false,
        useCelsius: Boolean,
    ): String {
        val displayValue = if (useCelsius) TempUtils.fahrenheitToCelsius(value) else value
        if (forceInteger) return "${displayValue.roundToInt()}°"
        return TempUtils.formatTemp(value, useCelsius).orEmpty()
    }
}
