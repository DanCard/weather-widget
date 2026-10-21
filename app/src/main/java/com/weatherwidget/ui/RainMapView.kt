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
import com.weatherwidget.shared.rainmap.BaseMap
import com.weatherwidget.shared.rainmap.RainGrid
import com.weatherwidget.shared.rainmap.RainGridRaster
import com.weatherwidget.shared.rainmap.RainLayer

/**
 * The Open-Meteo rain map: base map lines, the rain raster for [hourPos] scaled up with filtering,
 * and the pin at the centre (the widget's location). Square, fitted to the view.
 *
 * One reused bitmap and pixel buffer: the raster is redrawn on every animation tick.
 */
class RainMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var grid: RainGrid? = null
        set(value) {
            field = value
            basePaths = null
            invalidate()
        }
    var layer: RainLayer = RainLayer.AMOUNT
        set(value) {
            field = value
            invalidate()
        }
    var hourPos: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }
    var pinLabel: String = ""

    private val size = RainGridRaster.DEFAULT_SIZE_PX
    private val pixels = IntArray(size * size)
    private val raster = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val mapRect = RectF()
    private var basePaths: List<Pair<BaseMap.Kind, Path>>? = null

    private val density = resources.displayMetrics.density
    private val rasterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else minOf(w, h)
        setMeasuredDimension(w, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) h else side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = minOf(w, h).toFloat()
        mapRect.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f)
        basePaths = null
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(mapRect, landBg)
        val g = grid ?: return
        canvas.save()
        canvas.clipRect(mapRect)
        val paths = basePaths ?: buildPaths(g).also { basePaths = it }
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
        RainGridRaster.render(g, layer, hourPos, size, size, pixels)
        raster.setPixels(pixels, 0, size, 0, 0, size, size)
        canvas.drawBitmap(raster, null, mapRect, rasterPaint)
        canvas.restore()

        val cx = mapRect.centerX()
        val cy = mapRect.centerY()
        canvas.drawCircle(cx, cy, 7f * density, pinOuter)
        canvas.drawCircle(cx, cy, 5f * density, pinInner)
        if (pinLabel.isNotEmpty()) canvas.drawText(pinLabel, cx + 10f * density, cy + 5f * density, labelPaint)
    }

    private fun buildPaths(g: RainGrid): List<Pair<BaseMap.Kind, Path>> =
        BaseMap.linesFor(g).map { line ->
            val path = Path()
            for (k in line.lats.indices) {
                val x = mapRect.left + BaseMap.x(g, line.lons[k], mapRect.width())
                val y = mapRect.top + BaseMap.y(g, line.lats[k], mapRect.height())
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            line.kind to path
        }
}
