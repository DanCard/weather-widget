package com.weatherwidget.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.weatherwidget.shared.radar.RadarBox
import com.weatherwidget.shared.rainmap.BaseMap

/**
 * The radar map: one radar frame, base map lines over it, and the pin at the centre (the
 * widget's location). Square, fitted to the view — the twin of [RainMapView], fed whole frames
 * instead of a grid. See `plans/261021-radar-button-on-precip-graph.md`.
 */
class RadarMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var box: RadarBox? = null
        set(value) {
            field = value
            basePaths = null
            invalidate()
        }
    var frame: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    var pinLabel: String = ""

    private val mapRect = RectF()
    private var basePaths: List<Pair<BaseMap.Kind, Path>>? = null

    private val density = resources.displayMetrics.density
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val landBg = Paint().apply { color = 0xFF1E293B.toInt() }
    private val coastPaint = line(0xFFCBD5E1.toInt(), 1.2f)
    private val countryPaint = line(0xFF94A3B8.toInt(), 1f)
    private val statePaint = line(0x8094A3B8.toInt(), 0.7f)
    private val pinOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pinInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEF4444.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        isFakeBoldText = true
        setShadowLayer(3f * density, 0f, 1f * density, Color.BLACK)
    }

    private fun line(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = minOf(w, h).toFloat()
        mapRect.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f)
        basePaths = null
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(mapRect, landBg)
        val b = box ?: return
        canvas.save()
        canvas.clipRect(mapRect)
        // Lines over the radar, as on radar.weather.gov: heavy rain must not erase the coast.
        frame?.let { canvas.drawBitmap(it, null, mapRect, framePaint) }
        val paths = basePaths ?: buildPaths(b).also { basePaths = it }
        for ((kind, path) in paths) {
            canvas.drawPath(
                path,
                when (kind) {
                    BaseMap.Kind.COAST, BaseMap.Kind.LAKE -> coastPaint
                    BaseMap.Kind.COUNTRY -> countryPaint
                    BaseMap.Kind.STATE -> statePaint
                },
            )
        }
        canvas.restore()

        val cx = mapRect.left + b.x(b.centerLon.toFloat(), mapRect.width())
        val cy = mapRect.top + b.y(b.centerLat.toFloat(), mapRect.height())
        canvas.drawCircle(cx, cy, 7f * density, pinOuter)
        canvas.drawCircle(cx, cy, 5f * density, pinInner)
        if (pinLabel.isNotEmpty()) canvas.drawText(pinLabel, cx + 10f * density, cy + 5f * density, labelPaint)
    }

    private fun buildPaths(b: RadarBox): List<Pair<BaseMap.Kind, Path>> =
        BaseMap.linesIn(b.minLat, b.maxLat, b.minLon, b.maxLon).map { line ->
            val path = Path()
            for (k in line.lats.indices) {
                val x = mapRect.left + b.x(line.lons[k], mapRect.width())
                val y = mapRect.top + b.y(line.lats[k], mapRect.height())
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            line.kind to path
        }
}
