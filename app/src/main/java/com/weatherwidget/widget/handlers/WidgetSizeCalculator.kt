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
    // Per-column width assumption (dp). Deliberately tighter than a launcher grid cell so the daily
    // view packs more days at every size — e.g. Pixel 7 Pro (~373dp) shows 7 days and the Fold
    // full-width (~574dp) shows 10. Lowering this densifies all widgets uniformly.
    private const val CELL_WIDTH_DP = 60
    private const val CELL_HEIGHT_DP = 90
    private const val ICON_WIDTH_THRESHOLD_DP = 130

    /**
     * Bias added to the reported widget width before dividing by [CELL_WIDTH_DP].
     *
     * Combined with round-to-nearest (not floor), this rounds UP a widget that is within ~(this
     * many) dp of the next column, fitting one more forecast day. With the current [CELL_WIDTH_DP]
     * the 5→6 boundary lands at width >= 300dp, 6→7 at >= 360dp, and 9→10 at >= 540dp.
     *
     * Density is governed mainly by [CELL_WIDTH_DP]; this bias just nudges borderline widgets up.
     * Representative results: Pixel 7 Pro (~373dp) → 7 days, Samsung Fold 4 full-width (~574dp) →
     * 10 days. Days shown == columns (see NavigationUtils.getDayOffsets).
     *
     * Data backing the rightmost columns: at >8 columns the narrow skip-yesterday rule is off
     * (NavigationUtils.NARROW_SKIP_YESTERDAY_COLUMN_THRESHOLD), so a 10-column window is
     * yesterday..+8. +7 is the routine baseline (ForecastHorizon.BASELINE_DAYS = 8 = today + 7);
     * the +8 column (and NWS's +7, since NWS only returns ~today..+6) fall to the climate-normal
     * filler, which is the intended long-range fallback for dates > today+2, plus the on-demand
     * forecast extension the daily view triggers when its visible edge passes real coverage.
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
     * Pure (no Android dependencies) so the column math is directly unit-testable.
     * Round-to-nearest plus [COLUMN_FIT_BIAS_DP] deliberately fits the extra forecast day
     * for widgets near a column boundary (e.g. 6→7 columns at width >= 360dp).
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
