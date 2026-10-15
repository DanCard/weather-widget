package com.weatherwidget.widget

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.weatherwidget.shared.months.MonthsGeometry
import com.weatherwidget.shared.months.MonthsGraphGeometry
import com.weatherwidget.shared.months.MonthsGraphInput
import com.weatherwidget.shared.months.MonthsGraphStyle

/**
 * Android `Canvas` drawing of the months view. Every decision — what to plot, where, which labels —
 * is [MonthsGraphGeometry] in `:shared`; desktop's `MonthsGraph` draws the same geometry with Compose.
 */
class MonthsGraphRenderer(private val density: Float) {
    private val s = MonthsGraphStyle

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = s.LINE_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dash = DashPathEffect(floatArrayOf(s.DASH_ON_DP * density, s.DASH_OFF_DP * density), 0f)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint().apply { strokeWidth = s.GRID_STROKE_DP * density }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.NORMAL_BAND_COLOR; style = Paint.Style.FILL }
    private val todayPaint = Paint().apply { color = s.TODAY_COLOR; strokeWidth = s.TODAY_STROKE_DP * density }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.AXIS_LABEL_COLOR; textSize = s.AXIS_LABEL_SIZE_DP * density }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = s.LABEL_SIZE_DP * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val emptyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = s.EMPTY_TEXT_COLOR
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
    }
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    fun layout(input: MonthsGraphInput, widthPx: Float, heightPx: Float): MonthsGeometry =
        MonthsGraphGeometry.compute(input, widthPx, heightPx, density) { text, sizePx ->
            measurePaint.textSize = sizePx
            measurePaint.measureText(text)
        }

    /**
     * @param caption drawn top right (the window's date range)
     * @param emptyMessage drawn centred when the window has no data
     */
    fun draw(canvas: Canvas, g: MonthsGeometry, caption: String, emptyMessage: String) {
        if (g.normalHigh.size >= 2) {
            val band = Path().apply {
                moveTo(g.normalHigh.first().x, g.normalHigh.first().y)
                g.normalHigh.drop(1).forEach { lineTo(it.x, it.y) }
                g.normalLow.asReversed().forEach { lineTo(it.x, it.y) }
                close()
            }
            canvas.drawPath(band, bandPaint)
        }

        gridPaint.color = s.GRID_COLOR
        axisText.textAlign = Paint.Align.RIGHT
        for (line in g.gridLines) {
            canvas.drawLine(g.plotLeft, line.y, g.plotRight, line.y, gridPaint)
            canvas.drawText(line.label, g.plotLeft - 4f * density, line.y + axisText.textSize / 3f, axisText)
        }

        axisText.textAlign = Paint.Align.LEFT
        for (tick in g.ticks) {
            gridPaint.color = if (tick.major) s.MAJOR_TICK_COLOR else s.GRID_COLOR
            canvas.drawLine(tick.x, g.plotTop, tick.x, g.plotBottom, gridPaint)
            tick.label?.let { canvas.drawText(it, tick.x + 3f * density, g.plotBottom + axisText.textSize + 4f * density, axisText) }
        }

        g.todayX?.let { canvas.drawLine(it, g.plotTop, it, g.plotBottom, todayPaint) }

        for (p in g.pieces) {
            linePaint.color = p.color
            linePaint.pathEffect = if (p.dashed) dash else null
            canvas.drawLine(p.x1, p.y1, p.x2, p.y2, linePaint)
        }
        for (d in g.dots) {
            dotPaint.color = d.color
            canvas.drawCircle(d.x, d.y, s.DOT_RADIUS_DP * density, dotPaint)
        }

        for (label in g.labels) {
            labelText.color = label.color
            canvas.drawText(label.text, label.x, label.baseline, labelText)
        }

        axisText.textAlign = Paint.Align.RIGHT
        canvas.drawText(caption, g.plotRight, (g.plotTop + axisText.textSize) / 2f, axisText)
        axisText.textAlign = Paint.Align.LEFT

        if (!g.hasData) {
            canvas.drawText(emptyMessage, (g.plotLeft + g.plotRight) / 2f, (g.plotTop + g.plotBottom) / 2f, emptyText)
        }
    }
}
