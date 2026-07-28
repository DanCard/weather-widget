package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log

/**
 * Emits the `WIDGET_RESIZE` VERBOSE breadcrumb for one resize event.
 *
 * Split out of [WidgetIntentRouter] (2026-07-28, third-pass review N7) so the router stops carrying
 * the [WidgetSizeCalculator] arithmetic and options-bundle unpacking inline. The row is dropped at
 * the DAO boundary ([com.weatherwidget.data.local.AppLogDao.log] skips `level == "VERBOSE"`) and
 * reaches logcat only — one row per resize is too frequent for the persistent `app_logs` table.
 *
 * `handleResizeInternal` still calls this BEFORE `refreshWidget` so that a render that throws still
 * leaves an entry-state diagnostic; the value the launcher passed in the options Bundle is captured
 * here regardless of whether the subsequent refresh succeeds.
 */
object ResizeDiagnosticsLogger {
    /**
     * Width/height padding constants — kept private to this object so the message shape stays in
     * one place. Match the layout paddings the renderers subtract; if they ever diverge, this
     * breadcrumb is the place a resize-driven rendering flap will show its arithmetic.
     */
    private const val GRAPH_HORIZONTAL_PADDING_DP = 24
    private const val GRAPH_VERTICAL_PADDING_DP = 16

    suspend fun log(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        viewMode: String,
        appLogDao: AppLogDao,
    ) {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)

        val dimensions = WidgetSizeCalculator.getWidgetSize(context, appWidgetManager, appWidgetId)
        val graphWidthDp = (dimensions.widthDp - GRAPH_HORIZONTAL_PADDING_DP).coerceAtLeast(1)
        val graphHeightDp = (dimensions.heightDp - GRAPH_VERTICAL_PADDING_DP).coerceAtLeast(1)
        val rawWidthPx = WidgetSizeCalculator.dpToPx(context, graphWidthDp).coerceAtLeast(1)
        val rawHeightPx = WidgetSizeCalculator.dpToPx(context, graphHeightDp).coerceAtLeast(1)
        val (scaledWidthPx, scaledHeightPx) =
            WidgetSizeCalculator.getOptimalBitmapSize(context, graphWidthDp, graphHeightDp)
        val downscaled = rawWidthPx != scaledWidthPx || rawHeightPx != scaledHeightPx
        val orientation = context.resources.configuration.orientation

        val message =
            "widgetId=$appWidgetId view=$viewMode orient=$orientation " +
                "options=minW:$minWidth,minH:$minHeight,maxW:$maxWidth,maxH:$maxHeight " +
                "calc=cols:${dimensions.cols},rows:${dimensions.rows},widthDp:${dimensions.widthDp},heightDp:${dimensions.heightDp} " +
                "graphDp=${graphWidthDp}x$graphHeightDp rawPx=${rawWidthPx}x$rawHeightPx " +
                "scaledPx=${scaledWidthPx}x$scaledHeightPx downscaled=$downscaled"
        appLogDao.log("WIDGET_RESIZE", message, "VERBOSE")
    }
}
