package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import kotlin.math.roundToInt

/**
 * Data class representing widget dimensions.
 */
data class WidgetDimensions(
    val cols: Int,
    val rows: Int,
    val widthDp: Int,
    val heightDp: Int,
)

/**
 * Calculator for widget size and bitmap dimensions.
 */
object WidgetSizeCalculator {
    private const val CELL_WIDTH_DP = 70
    private const val CELL_HEIGHT_DP = 90
    private const val MAX_BITMAP_PIXELS = 225_000 // Limit bitmap to ~900KB (ARGB_8888 is 4 bytes/px)

    /**
     * Calculate widget dimensions based on AppWidgetOptions.
     */
    fun getWidgetSize(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ): WidgetDimensions {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)

        // Android reports both min and max widget dimensions:
        //   Portrait:  actual size ≈ minWidth × maxHeight
        //   Landscape: actual size ≈ maxWidth × minHeight
        val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val width = if (isPortrait) minWidth else maxWidth
        val height = if (isPortrait) maxHeight else minHeight

        // Standard Android widget size formula: (size + padding) / cell_size with rounding
        // Using +15/+25 padding and proper rounding to handle widgets that are "almost" N rows/cols
        val cols = ((width + 15).toFloat() / CELL_WIDTH_DP).roundToInt().coerceAtLeast(1)
        val rows = ((height + 25).toFloat() / CELL_HEIGHT_DP).roundToInt().coerceAtLeast(1)

        return WidgetDimensions(cols, rows, width, height)
    }

    /**
     * Get optimal bitmap size for the widget, applying downscaling if needed.
     */
    fun getOptimalBitmapSize(
        context: Context,
        widthDp: Int,
        heightDp: Int,
    ): Pair<Int, Int> {
        val rawWidth = dpToPx(context, widthDp)
        val rawHeight = dpToPx(context, heightDp)
        val rawPixels = rawWidth * rawHeight
        val rawMemoryKB = rawPixels * 4 / 1024

        return if (rawPixels > MAX_BITMAP_PIXELS) {
            val scale = kotlin.math.sqrt(MAX_BITMAP_PIXELS.toFloat() / rawPixels)
            val newWidth = (rawWidth * scale).roundToInt()
            val newHeight = (rawHeight * scale).roundToInt()
            val newPixels = newWidth * newHeight
            val newMemoryKB = newPixels * 4 / 1024
            android.util.Log.d(
                "WidgetSizeCalculator",
                "${widthDp}dp×${heightDp}dp → Downscaling from ${rawWidth}x${rawHeight}px (${rawMemoryKB}KB) to ${newWidth}x${newHeight}px (${newMemoryKB}KB), scale=$scale, rawPixels=$rawPixels",
            )
            newWidth to newHeight
        } else {
            android.util.Log.d(
                "WidgetSizeCalculator",
                "${widthDp}dp×${heightDp}dp → No downscaling needed: ${rawWidth}x${rawHeight}px (${rawMemoryKB}KB), rawPixels=$rawPixels",
            )
            rawWidth to rawHeight
        }
    }

    /**
     * Convert DP to pixels.
     */
    fun dpToPx(
        context: Context,
        dp: Int,
    ): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
    }

    /**
     * Convert DP float to pixels float.
     */
    fun dpToPx(
        context: Context,
        dp: Float,
    ): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
    }
}
