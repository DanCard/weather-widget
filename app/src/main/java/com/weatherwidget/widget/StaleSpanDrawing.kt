package com.weatherwidget.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.weatherwidget.shared.graph.StaleForecastSpans

/**
 * Draws a forecast line with its stale hours dimmed: one clipped pass per [StaleForecastSpans] span.
 * Paths are never split, so dash phase and curve shape match the undimmed line exactly. Desktop's
 * counterpart is `DesktopGraphUtils.drawDimmingStale`.
 */
object StaleSpanDrawing {
    private const val TAG = "StaleSpanDrawing"

    /** Calls [draw] once per span, clipped to it, with whether that span is stale. */
    inline fun forEachSpan(
        canvas: Canvas,
        xs: List<Float>,
        stale: List<Boolean>,
        widthPx: Float,
        heightPx: Float,
        draw: (stale: Boolean) -> Unit,
    ) {
        val spans = StaleForecastSpans.spans(xs, stale, 0f, widthPx)
        if (spans.size == 1 && !spans[0].stale) {
            draw(false)
            return
        }
        logSpans(spans)
        for (span in spans) {
            canvas.save()
            canvas.clipRect(span.start, 0f, span.end, heightPx)
            draw(span.stale)
            canvas.restore()
        }
    }

    fun drawDimmingStale(
        canvas: Canvas,
        path: Path,
        paint: Paint,
        xs: List<Float>,
        stale: List<Boolean>,
        widthPx: Float,
        heightPx: Float,
    ) {
        val baseAlpha = paint.alpha
        forEachSpan(canvas, xs, stale, widthPx, heightPx) { isStale ->
            paint.alpha = StaleForecastSpans.alphaFor(baseAlpha, isStale)
            canvas.drawPath(path, paint)
        }
        paint.alpha = baseAlpha
    }

    @PublishedApi
    internal fun logSpans(spans: List<StaleForecastSpans.Span>) {
        Log.v(TAG, "dimmed stale spans=${spans.filter { it.stale }.map { "${it.start.toInt()}..${it.end.toInt()}" }}")
    }
}
