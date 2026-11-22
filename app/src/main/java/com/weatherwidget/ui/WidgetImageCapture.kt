package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.weatherwidget.widget.WidgetViewsHistory
import com.weatherwidget.widget.handlers.WidgetSizeCalculator
import java.io.ByteArrayOutputStream

private const val TAG = "WidgetImageCapture"

/**
 * Draws a widget for a bug report from what the app last sent the launcher
 * ([WidgetViewsHistory]): the full tree inflated at the widget's resolved size, then each partial
 * update replayed in order. Shows header buttons and their visibility exactly as pushed; not the
 * launcher's own cropping, corner rounding or scaling. Must run on the main thread (view inflation).
 */
object WidgetImageCapture {
    /** Backdrop behind the (partly transparent) widget, so text and bars are readable in the PNG. */
    private const val BACKDROP = 0xFF303030.toInt()

    data class Result(val png: ByteArray?, val note: String)

    fun capture(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int): Result {
        val snapshot = WidgetViewsHistory.snapshot(appWidgetId)
            ?: return Result(null, "image: not rendered since app process start")
        return try {
            val dims = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
            val density = context.resources.displayMetrics.density
            val widthPx = (dims.widthDp * density).toInt().coerceAtLeast(1)
            val heightPx = (dims.heightDp * density).toInt().coerceAtLeast(1)

            // Inflate as the launcher does: plain framework views. An AppCompat activity's inflater
            // swaps in AppCompatImageView, whose setImageBitmap is not a @RemotableViewMethod, so
            // RemoteViews refuses it (ActionException, seen on the API 37 emulator 2026-11-21).
            val hostContext = context.applicationContext
            val parent = FrameLayout(hostContext)
            val view = snapshot.full.apply(hostContext, parent)
            snapshot.partials.forEach { it.reapply(hostContext, view) }
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, widthPx, heightPx)

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(BACKDROP)
                view.draw(this)
            }
            val png = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            bitmap.recycle()
            val age = (System.currentTimeMillis() - snapshot.fullAtMs) / 1000
            Result(
                png,
                "image: ${dims.widthDp}x${dims.heightDp}dp from ${snapshot.fullCaller} full push ${age}s ago" +
                    " + ${snapshot.partials.size} partial" + if (snapshot.partialOverflow) " (oldest partials dropped)" else "",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not draw widget $appWidgetId for bug report", e)
            Result(null, "image: failed (${e.javaClass.simpleName})")
        }
    }
}
