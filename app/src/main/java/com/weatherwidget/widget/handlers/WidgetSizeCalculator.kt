package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Data class representing widget dimensions.
 *
 * `isIconWidth` flags widgets that are approximately one launcher-icon wide
 * (widthDp <= ICON_WIDTH_THRESHOLD_DP). Independent of `cols` (data columns).
 */
data class WidgetDimensions(
    val cols: Int,
    val rows: Int,
    val widthDp: Int,
    val heightDp: Int,
    val isIconWidth: Boolean,
)

/**
 * Calculator for widget size and bitmap dimensions.
 */
object WidgetSizeCalculator {
    private const val CELL_WIDTH_DP = 70
    private const val CELL_HEIGHT_DP = 90
    private const val ICON_WIDTH_THRESHOLD_DP = 130

    /**
     * Bias added to the reported widget width before dividing by [CELL_WIDTH_DP].
     *
     * Combined with round-to-nearest (not floor), this intentionally rounds UP a widget
     * that is within ~(this many) dp of the next column, so we fit one more forecast day.
     * With this value the 5→6 boundary lands at width >= 355dp and the 8→9 boundary at
     * width >= 565dp.
     *
     * Sized so the widest practical real widget — the Samsung Fold 4 full-width (6-span)
     * widget, which reports ~574dp — reaches its **9th** day column. Nine columns is still
     * fully inside the forecast baseline: at >8 columns the narrow skip-yesterday rule is
     * off (see NavigationUtils.NARROW_SKIP_YESTERDAY_COLUMN_THRESHOLD), so the window is
     * yesterday..+7 — and +7 == ForecastHorizon.BASELINE_DAYS (8 = today + 7). So the extra
     * column is backed by routine data for sources that return a full week (Open-Meteo);
     * NWS (~today..+6) leaves the +7 column to the climate-normal fallback, which is the
     * intended long-range filler for dates > today+2. Days shown == columns.
     *
     * Verified (against live device options, June 2026) that raising this from 15 to 30
     * flips only the 574dp Fold widget to 9; the Pixel 7 Pro (~373dp → 6) and every other
     * observed widget size are unchanged. Widths > ~643dp would reach 10 columns (+8, past
     * baseline) and fall to climate-normal there too, but no current launcher reports them.
     */
    private const val COLUMN_FIT_BIAS_DP = 30

    /**
     * Row-count analogue of [COLUMN_FIT_BIAS_DP]: biases height toward the next row so a
     * widget that is nearly tall enough is treated as the taller layout.
     */
    private const val ROW_FIT_BIAS_DP = 25
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

        val cols = columnsForWidthDp(width)
        val rows = rowsForHeightDp(height)
        val isIconWidth = width <= ICON_WIDTH_THRESHOLD_DP

        return WidgetDimensions(cols, rows, width, height, isIconWidth)
    }

    /**
     * Maps a widget width (dp) to the number of day columns the daily view shows.
     *
     * Pure (no Android dependencies) so the round-up bias is directly unit-testable.
     * Round-to-nearest plus [COLUMN_FIT_BIAS_DP] deliberately fits the extra forecast day
     * for widgets within ~half a cell of the next column (e.g. 5→6 columns at width >= 370dp).
     */
    fun columnsForWidthDp(widthDp: Int): Int =
        ((widthDp + COLUMN_FIT_BIAS_DP).toFloat() / CELL_WIDTH_DP).roundToInt().coerceAtLeast(1)

    /**
     * Maps a widget height (dp) to the number of rows, biased toward the taller layout.
     * Pure counterpart to [columnsForWidthDp]; see [ROW_FIT_BIAS_DP].
     */
    fun rowsForHeightDp(heightDp: Int): Int =
        ((heightDp + ROW_FIT_BIAS_DP).toFloat() / CELL_HEIGHT_DP).roundToInt().coerceAtLeast(1)

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

    data class BitmapDimensions(
        val widthPx: Int,
        val heightPx: Int,
        val bitmapScale: Float,
    )

    fun computeBitmapDimensions(
        context: Context,
        widgetWidthDp: Int,
        widgetHeightDp: Int,
        widthPaddingDp: Int = 24,
        heightPaddingDp: Int = 16,
    ): BitmapDimensions {
        val widthDp = widgetWidthDp - widthPaddingDp
        val heightDp = widgetHeightDp - heightPaddingDp
        val (widthPx, heightPx) = getOptimalBitmapSize(context, widthDp, heightDp)
        val rawWidthPx = dpToPx(context, widthDp).coerceAtLeast(1)
        val rawHeightPx = dpToPx(context, heightDp).coerceAtLeast(1)
        val bitmapScale = min(widthPx.toFloat() / rawWidthPx.toFloat(), heightPx.toFloat() / rawHeightPx.toFloat())
        return BitmapDimensions(widthPx, heightPx, bitmapScale)
    }
}
