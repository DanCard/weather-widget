package com.weatherwidget.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.weatherwidget.shared.months.MonthsGeometry
import com.weatherwidget.shared.months.MonthsGraphInput
import com.weatherwidget.widget.MonthsGraphRenderer

/**
 * The months screen's drawing surface: the months line graph, or the daily column bitmap. It owns
 * no state; it draws what it is given and reports gestures to [listener].
 */
class MonthsScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** Fingers together (zoom out) when [zoomOut], apart otherwise. One call per pinch step. */
        fun onPinchStep(zoomOut: Boolean)

        /** A horizontal drag moved [dxPx] since the last call. */
        fun onDrag(dxPx: Float)

        fun onDragEnd()

        fun onTap(x: Float, y: Float)
    }

    var listener: Listener? = null

    private val renderer = MonthsGraphRenderer(resources.displayMetrics.density)
    private var monthsInput: MonthsGraphInput? = null
    private var caption: String = ""
    private var emptyMessage: String = ""
    private var dailyBitmap: Bitmap? = null

    /** The geometry last drawn, so a tap is hit-tested against exactly what is on screen. */
    var lastGeometry: MonthsGeometry? = null
        private set

    fun showMonths(input: MonthsGraphInput, caption: String, emptyMessage: String) {
        monthsInput = input
        this.caption = caption
        this.emptyMessage = emptyMessage
        dailyBitmap = null
        invalidate()
    }

    fun showDaily(bitmap: Bitmap?) {
        monthsInput = null
        lastGeometry = null
        dailyBitmap = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        monthsInput?.let { input ->
            val geometry = renderer.layout(input, width.toFloat(), height.toFloat())
            lastGeometry = geometry
            renderer.draw(canvas, geometry, caption, emptyMessage)
            return
        }
        dailyBitmap?.let { bitmap ->
            val dst = dailyRect(bitmap)
            canvas.drawBitmap(bitmap, null, dst, null)
        }
    }

    /**
     * The widget card at its own size, centred; shrunk to fit only when the screen is smaller (a
     * widget never grows past its home-screen size here, so it reads the same).
     */
    private fun dailyRect(bitmap: Bitmap): android.graphics.RectF {
        val scale = minOf(1f, width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return android.graphics.RectF(left, top, left + w, top + h)
    }

    /** Width of the drawn column card, for drag steps; the view width before one is shown. */
    fun dailyDrawnWidthPx(): Float = dailyBitmap?.let { dailyRect(it).width() } ?: width.toFloat()

    private var scaleAccum = 1f
    private var scaling = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                scaleAccum = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleAccum *= detector.scaleFactor
                // Steps, not continuous zoom: each ~20 % of pinch is one span / one column.
                while (scaleAccum < 1f / PINCH_STEP) {
                    listener?.onPinchStep(zoomOut = true)
                    scaleAccum *= PINCH_STEP
                }
                while (scaleAccum > PINCH_STEP) {
                    listener?.onPinchStep(zoomOut = false)
                    scaleAccum /= PINCH_STEP
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaleAccum = 1f
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                listener?.onTap(e.x, e.y)
                performClick()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaling) return false
                listener?.onDrag(-distanceX)
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount == 1) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!scaling) listener?.onDragEnd()
            scaling = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val PINCH_STEP = 1.2f
    }
}
