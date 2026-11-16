package com.weatherwidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.weatherwidget.shared.graph.DailyRainLabelPlanner
import com.weatherwidget.shared.graph.InkScan
import java.util.concurrent.ConcurrentHashMap

/**
 * Measured ink for the night rain label placer: glyph bounds of text and the opaque pixels of icons
 * (plans/261116-night-rain-label-scored-placement-redesign.md).
 */
internal object TextInk {
    /**
     * Glyph bounds of [text] drawn with [paint], relative to the left end of its baseline (the origin of
     * a left-aligned draw). Null when the platform returns no bounds (Robolectric has no font engine).
     */
    fun bounds(paint: Paint, text: String): DailyRainLabelPlanner.Box? {
        if (text.isEmpty()) return null
        val left = Paint(paint).apply { textAlign = Paint.Align.LEFT }
        val r = Rect()
        left.getTextBounds(text, 0, text.length, r)
        if (r.isEmpty) return null
        return DailyRainLabelPlanner.Box(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    }

    private val textBandCache = ConcurrentHashMap<String, List<DailyRainLabelPlanner.Box>>()

    /**
     * [text] drawn left-aligned with [paint] (plus an outline [outlineWidth] wide, when > 0), as ink
     * row bands [bandPx] tall ([InkScan.rowBands]), relative to the left end of its baseline. Cached
     * per text, size, typeface, outline and band. Null when nothing rasterizes (Robolectric).
     */
    fun textBands(paint: Paint, text: String, outlineWidth: Float, bandPx: Int): List<DailyRainLabelPlanner.Box>? {
        if (text.isEmpty()) return null
        val key = "$text|${paint.textSize}|${paint.typeface?.hashCode()}|$outlineWidth|$bandPx"
        textBandCache[key]?.let { return it.ifEmpty { null } }
        val fill = Paint(paint).apply {
            textAlign = Paint.Align.LEFT
            clearShadowLayer()
            color = 0xFF000000.toInt()
        }
        // A mocked Paint (renderer unit tests) has no font metrics: nothing to rasterize.
        val metrics: Paint.FontMetrics = fill.fontMetrics ?: return null
        val pad = kotlin.math.ceil(outlineWidth / 2f).toInt() + 2
        val w = kotlin.math.ceil(fill.measureText(text)).toInt() + 2 * pad
        val h = kotlin.math.ceil(metrics.descent - metrics.ascent).toInt() + 2 * pad
        if (w <= 2 * pad || h <= 2 * pad) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baseline = pad - metrics.ascent
        if (outlineWidth > 0f) {
            canvas.drawText(text, pad.toFloat(), baseline, Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = outlineWidth })
        }
        canvas.drawText(text, pad.toFloat(), baseline, fill)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        val bands = InkScan.rowBands(h, 0, w, bandPx) { x, y -> (pixels[y * w + x] ushr 24) > ALPHA_THRESHOLD }
            .map { DailyRainLabelPlanner.Box(it.left - pad, it.top - baseline, it.right - pad, it.bottom - baseline) }
        textBandCache[key] = bands
        return bands.ifEmpty { null }
    }

    private val iconBandCache = ConcurrentHashMap<String, List<DailyRainLabelPlanner.Box>>()

    /**
     * Opaque pixels of [drawable] drawn into a [size]² square as row bands [bandPx] tall, relative to
     * its top-left, cached per [key], size and band. The whole square when nothing is opaque
     * (Robolectric draws nothing).
     */
    fun iconBands(key: String, drawable: Drawable, size: Int, bandPx: Int): List<DailyRainLabelPlanner.Box> =
        iconBandCache.getOrPut("$key|$size|$bandPx") {
            val full = listOf(DailyRainLabelPlanner.Box(0f, 0f, size.toFloat(), size.toFloat()))
            if (size <= 0) return@getOrPut full
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val saved = Rect(drawable.bounds)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            drawable.bounds = saved
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            bitmap.recycle()
            InkScan.rowBands(size, 0, size, bandPx) { x, y -> (pixels[y * size + x] ushr 24) > ALPHA_THRESHOLD }.ifEmpty { full }
        }

    /** Pixels fainter than this (of 255) are anti-aliasing fringe, not ink. */
    private const val ALPHA_THRESHOLD = 25
}
