package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context

/**
 * Reorders widget IDs during batch updates so the most relevant widget renders first:
 * 1. The most recently interacted widget (if present in the batch).
 * 2. On foldable / wide-screen devices, widgets whose dimensions match the active display mode.
 * 3. Remaining widgets in stable order.
 */
internal object WidgetPaintPrioritizer {
    private const val FOLDABLE_WIDE_SW_DP = 600
    private const val WIDE_WIDGET_MIN_WIDTH_DP = 450

    fun prioritize(
        context: Context,
        appWidgetIds: IntArray,
        lastInteractedId: Int? = null,
        appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context),
    ): IntArray {
        if (appWidgetIds.size <= 1) return appWidgetIds

        val targetId = lastInteractedId?.takeIf { it in appWidgetIds }
        val remaining = appWidgetIds.filter { it != targetId }

        val isLargeScreen = context.resources.configuration.smallestScreenWidthDp >= FOLDABLE_WIDE_SW_DP

        val sortedRemaining = remaining.sortedWith(
            compareByDescending { widgetId ->
                val options = runCatching { appWidgetManager.getAppWidgetOptions(widgetId) }.getOrNull()
                if (options != null) {
                    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                    val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
                    val isWide = minWidth >= WIDE_WIDGET_MIN_WIDTH_DP || maxWidth >= WIDE_WIDGET_MIN_WIDTH_DP
                    if (isLargeScreen == isWide) 1 else 0
                } else {
                    0
                }
            }
        )

        return if (targetId != null) {
            intArrayOf(targetId) + sortedRemaining.toIntArray()
        } else {
            sortedRemaining.toIntArray()
        }
    }
}
