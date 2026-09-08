package com.weatherwidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.HourlyTimelineGeometry
import com.weatherwidget.shared.graph.ValueLabelEngine
import java.time.LocalDateTime

/**
 * Draws value labels and day labels on the cloud cover graph.
 * Extracted from [CloudCoverGraphRenderer].
 */
internal object CloudCoverGraphLabels {

    fun drawPercentLabels(
        context: Context,
        canvas: Canvas,
        labelSignal: List<Int>,
        graphPoints: List<ValueLabelEngine.GraphPoint>,
        graphTop: Float,
        graphBottom: Float,
        graphHeight: Float,
        widthPx: Int,
        heightPx: Int,
        paints: CloudCoverGraphStyle.PaintSet,
        cloudLabelAscent: Float,
        cloudLabelDescent: Float,
        drawnIconBounds: List<RectF>,
        drawnLabelBounds: MutableList<RectF>,
        numColumns: Int,
        dpToPx: (Float) -> Float,
        onLabelPlaced: ((CloudCoverGraphRenderer.LabelPlacementDebug) -> Unit)?,
    ) {
        ValueLabelEngine.computePlacements(
            labelSignal = labelSignal,
            points = graphPoints,
            geometry = ValueLabelEngine.Geometry(graphTop, graphBottom, graphHeight, widthPx.toFloat(), heightPx.toFloat()),
            config = ValueLabelEngine.Config.cloud(),
            measureText = { paints.percentLabelPaint.measureText(it) },
            textAscent = cloudLabelAscent,
            textDescent = cloudLabelDescent,
            dpToPx = dpToPx,
            drawnIconBounds = drawnIconBounds.map { GraphRect(it.left, it.top, it.right, it.bottom) },
            numColumns = numColumns,
        ).forEach { p ->
            canvas.drawText(p.text, p.centerX, p.baselineY, paints.percentLabelPaint)
            drawnLabelBounds.add(RectF(p.box.left, p.box.top, p.box.right, p.box.bottom))
            onLabelPlaced?.invoke(
                CloudCoverGraphRenderer.LabelPlacementDebug(
                    index = p.index,
                    cloudCover = labelSignal[p.index],
                    placedAbove = p.placedAbove,
                    isGlobalMax = p.isGlobalMax,
                    isGlobalMin = p.isGlobalMin,
                ),
            )
        }
    }

    fun drawDayLabels(
        canvas: Canvas,
        hours: List<CloudCoverGraphRenderer.CloudHourData>,
        currentTime: LocalDateTime,
        widthPx: Int,
        heightPx: Int,
        graphTop: Float,
        graphBottom: Float,
        paints: CloudCoverGraphStyle.PaintSet,
        drawnLabelBounds: MutableList<RectF>,
        drawnIconBounds: List<RectF>,
        dpToPx: (Float) -> Float,
        onDayLabelPlaced: ((CloudCoverGraphRenderer.DayLabelPlacementDebug) -> Unit)?,
    ) {
        val (today, leftDate, rightDate, leftText, rightText) =
            HourlyTimelineGeometry.dayLabelEndpoints(hours.first().dateTime, hours.last().dateTime, currentTime)

        val leftPaint: Paint = if (leftDate == today) paints.todayDayLabelPaint else paints.dayLabelTextPaint
        val rightPaint: Paint = if (rightDate == today) paints.todayDayLabelPaint else paints.dayLabelTextPaint
        val leftTextWidth = leftPaint.measureText(leftText)
        val rightTextWidth = rightPaint.measureText(rightText)

        HourlyIndicatorRenderer.drawDayLabels(
            canvas = canvas,
            leftDate = leftDate,
            rightDate = rightDate,
            leftText = leftText,
            rightText = rightText,
            leftX = leftTextWidth / 2f,
            rightX = widthPx - rightTextWidth / 2f,
            today = today,
            graphTop = graphTop,
            graphBottom = graphBottom,
            heightPx = heightPx,
            dayLabelTextPaint = paints.dayLabelTextPaint,
            todayDayLabelPaint = paints.todayDayLabelPaint,
            drawnLabelBounds = drawnLabelBounds,
            drawnIconBounds = drawnIconBounds,
            dpToPx = dpToPx,
            onDayLabelPlaced = if (onDayLabelPlaced != null) { side, text, date, x, y, placement, isToday ->
                onDayLabelPlaced.invoke(CloudCoverGraphRenderer.DayLabelPlacementDebug(side, text, date, x, y, placement, isToday))
            } else null,
        )
    }
}
