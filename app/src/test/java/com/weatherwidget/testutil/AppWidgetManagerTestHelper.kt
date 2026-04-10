package com.weatherwidget.testutil

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.widget.RemoteViews
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs

data class CapturedWidgetViews(
    val appWidgetManager: AppWidgetManager,
    val viewsSlot: CapturingSlot<RemoteViews>,
)

fun mockAppWidgetManager(
    widgetId: Int,
    widthDp: Int = 200,
    heightDp: Int = 90,
): CapturedWidgetViews {
    val appWidgetManager = mockk<AppWidgetManager>()
    val options = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
    }
    every { appWidgetManager.getAppWidgetOptions(widgetId) } returns options
    val viewsSlot = CapturingSlot<RemoteViews>()
    every { appWidgetManager.updateAppWidget(widgetId, capture(viewsSlot)) } just runs
    return CapturedWidgetViews(appWidgetManager, viewsSlot)
}