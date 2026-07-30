package com.weatherwidget.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.shared.graph.NowIndicatorGeometry
import java.time.LocalDate

/** NOW-line and endpoint day-label placement/drawing for Android hourly graphs. */
internal object HourlyIndicatorRenderer {
    private const val TAG = "HourlyIndicator"
    private const val NOW_TEXT = "NOW"
    private const val BOTTOM_FALLBACK_INSET_DP = 14f

    data class NowLabelResult(
        val labelY: Float,
        val bounds: RectF,
    )

    data class DayLabelPlacement(
        val side: String,
        val text: String,
        val date: LocalDate,
        val x: Float,
        val y: Float,
        val placement: String,
        val isToday: Boolean,
        val bounds: GraphRect,
    )

    fun computeNowLabelBounds(
        nowX: Float,
        graphTop: Float,
        graphHeight: Float,
        textWidth: Float,
        fontAscent: Float,
        fontDescent: Float,
        drawnBounds: List<RectF> = emptyList(),
        dpToPx: (Float) -> Float,
    ): NowLabelResult? {
        val placement = NowIndicatorGeometry.computeNowLabel(
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            labelWidth = textWidth,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            drawnBounds = drawnBounds.map { it.toGraphRect() },
            dpToPx = dpToPx,
        ) ?: return null
        return NowLabelResult(
            labelY = placement.baselineY,
            bounds = placement.box.toRectF(),
        )
    }

    fun drawNowLine(
        canvas: Canvas,
        nowX: Float?,
        graphTop: Float,
        graphHeight: Float,
        currentTimePaint: Paint,
    ) {
        if (nowX == null) return
        val line = NowIndicatorGeometry.computeNowLine(graphTop, graphHeight)
        canvas.drawLines(
            floatArrayOf(nowX, line.lineTop, nowX, line.lineBottom),
            currentTimePaint,
        )
    }

    fun drawNowIndicator(
        canvas: Canvas,
        nowX: Float?,
        graphTop: Float,
        graphHeight: Float,
        currentTimePaint: Paint,
        nowLabelTextPaint: Paint,
        drawnBounds: List<RectF> = emptyList(),
        drawLine: Boolean = true,
        dpToPx: (Float) -> Float,
    ) {
        if (nowX == null) return
        if (drawLine) drawNowLine(canvas, nowX, graphTop, graphHeight, currentTimePaint)
        val fontMetrics = nowLabelTextPaint.fontMetrics
        val ascent =
            fontMetrics?.ascent?.takeIf { it != 0f } ?: -nowLabelTextPaint.textSize
        val descent =
            fontMetrics?.descent?.takeIf { it != 0f } ?: nowLabelTextPaint.textSize * 0.2f
        computeNowLabelBounds(
            nowX = nowX,
            graphTop = graphTop,
            graphHeight = graphHeight,
            textWidth = nowLabelTextPaint.measureText(NOW_TEXT),
            fontAscent = ascent,
            fontDescent = descent,
            drawnBounds = drawnBounds,
            dpToPx = dpToPx,
        )?.let { result ->
            canvas.drawText(NOW_TEXT, nowX, result.labelY, nowLabelTextPaint)
        }
    }

    fun computeDayLabelPlacements(
        leftDate: LocalDate,
        rightDate: LocalDate,
        leftText: String,
        rightText: String,
        leftX: Float,
        rightX: Float,
        today: LocalDate,
        graphTop: Float,
        graphBottom: Float,
        heightPx: Int,
        dayLabelFontMetrics: Pair<Float, Float>,
        todayLabelFontMetrics: Pair<Float, Float>,
        measureDayText: (String, Boolean) -> Float,
        drawnLabelBounds: List<GraphRect>,
        drawnIconBounds: List<GraphRect>,
        dpToPx: (Float) -> Float,
    ): List<DayLabelPlacement> {
        data class Candidate(
            val side: String,
            val date: LocalDate,
            val x: Float,
            val text: String,
        )

        val placedBounds = mutableListOf<GraphRect>()
        fun collides(bounds: GraphRect): Boolean =
            drawnLabelBounds.any { it.intersects(bounds) } ||
                drawnIconBounds.any { it.intersects(bounds) } ||
                placedBounds.any { it.intersects(bounds) }

        val candidates =
            listOf(
                Candidate("LEFT", leftDate, leftX, leftText),
                Candidate("RIGHT", rightDate, rightX, rightText),
            )
        val graphHeight = graphBottom - graphTop
        val verticalFractions = (5..92 step 2).map { it / 100f }

        return buildList {
            candidates.forEach { candidate ->
                val isToday = candidate.date == today
                val (ascent, descent) =
                    if (isToday) todayLabelFontMetrics else dayLabelFontMetrics
                val textWidth = measureDayText(candidate.text, isToday)
                fun boundsAt(baselineY: Float) =
                    GraphRect(
                        candidate.x - textWidth / 2f,
                        baselineY + ascent,
                        candidate.x + textWidth / 2f,
                        baselineY + descent,
                    )

                var placement: DayLabelPlacement? = null
                for (fraction in verticalFractions) {
                    val baselineY = graphTop + graphHeight * fraction
                    val bounds = boundsAt(baselineY)
                    if (!collides(bounds)) {
                        placement =
                            DayLabelPlacement(
                                side = candidate.side,
                                text = candidate.text,
                                date = candidate.date,
                                x = candidate.x,
                                y = baselineY,
                                placement = "yFrac=$fraction",
                                isToday = isToday,
                                bounds = bounds,
                            )
                        break
                    }
                }
                if (placement == null) {
                    val baselineY = heightPx - dpToPx(BOTTOM_FALLBACK_INSET_DP)
                    placement =
                        DayLabelPlacement(
                            side = candidate.side,
                            text = candidate.text,
                            date = candidate.date,
                            x = candidate.x,
                            y = baselineY,
                            placement = "BOTTOM_FALLBACK",
                            isToday = isToday,
                            bounds = boundsAt(baselineY),
                        )
                }
                add(placement)
                placedBounds.add(placement.bounds)
            }
        }
    }

    fun drawDayLabels(
        canvas: Canvas,
        leftDate: LocalDate,
        rightDate: LocalDate,
        leftText: String,
        rightText: String,
        leftX: Float,
        rightX: Float,
        today: LocalDate,
        graphTop: Float,
        graphBottom: Float,
        heightPx: Int,
        dayLabelTextPaint: Paint,
        todayDayLabelPaint: Paint,
        drawnLabelBounds: List<RectF>,
        drawnIconBounds: List<RectF>,
        dpToPx: (Float) -> Float,
        onDayLabelPlaced: ((
            side: String,
            text: String,
            date: LocalDate,
            x: Float,
            y: Float,
            placement: String,
            isToday: Boolean,
        ) -> Unit)? = null,
    ) {
        val dayMetrics = dayLabelTextPaint.metricsPair()
        val todayMetrics = todayDayLabelPaint.metricsPair()
        val placements = computeDayLabelPlacements(
            leftDate = leftDate,
            rightDate = rightDate,
            leftText = leftText,
            rightText = rightText,
            leftX = leftX,
            rightX = rightX,
            today = today,
            graphTop = graphTop,
            graphBottom = graphBottom,
            heightPx = heightPx,
            dayLabelFontMetrics = dayMetrics,
            todayLabelFontMetrics = todayMetrics,
            measureDayText = { text, isToday ->
                (if (isToday) todayDayLabelPaint else dayLabelTextPaint).measureText(text)
            },
            drawnLabelBounds = drawnLabelBounds.map { it.toGraphRect() },
            drawnIconBounds = drawnIconBounds.map { it.toGraphRect() },
            dpToPx = dpToPx,
        )
        placements.forEach { placement ->
            val paint = if (placement.isToday) todayDayLabelPaint else dayLabelTextPaint
            canvas.drawText(placement.text, placement.x, placement.y, paint)
            Log.v(
                TAG,
                "${placement.side} \"${placement.text}\" at y=${placement.y} " +
                    "${placement.placement} iconBounds=${drawnIconBounds.size} " +
                    "labelBounds=${drawnLabelBounds.size}",
            )
            onDayLabelPlaced?.invoke(
                placement.side,
                placement.text,
                placement.date,
                placement.x,
                placement.y,
                placement.placement,
                placement.isToday,
            )
        }
    }

    private fun Paint.metricsPair(): Pair<Float, Float> {
        val metrics = fontMetrics
        return if (metrics != null && (metrics.ascent != 0f || metrics.descent != 0f)) {
            metrics.ascent to metrics.descent
        } else {
            -textSize to textSize * 0.2f
        }
    }

    private fun RectF.toGraphRect(): GraphRect = GraphRect(left, top, right, bottom)
    private fun GraphRect.toRectF(): RectF = RectF(left, top, right, bottom)
}
