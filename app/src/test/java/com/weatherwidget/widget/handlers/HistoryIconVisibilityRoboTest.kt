package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * Robolectric tests verifying the visibility of the history icon across different view modes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryIconVisibilityRoboTest {
    private lateinit var context: Context
    private val appWidgetId = 42

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
    }

    @Test
    fun `history icon is visible in hourly temperature mode`() {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        }
        
        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = emptyList(),
            centerTime = LocalDateTime.now(),
            displaySource = WeatherSource.NWS,
            precipProbability = 0
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root)
        
        val historyIcon = applied.findViewById<View>(R.id.history_icon)
        val historyTouchZone = applied.findViewById<View>(R.id.history_touch_zone)
        
        assertEquals("History icon should be VISIBLE in hourly view", View.VISIBLE, historyIcon.visibility)
        assertEquals("History touch zone should be VISIBLE in hourly view", View.VISIBLE, historyTouchZone.visibility)
    }

    @Test
    fun `history icon is hidden in daily mode`() = runBlocking {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        }
        
        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit

        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS))

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            weatherList = emptyList(),
            forecastSnapshots = emptyMap(),
            hourlyForecasts = emptyList()
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root)
        
        val historyIcon = applied.findViewById<View>(R.id.history_icon)
        val historyTouchZone = applied.findViewById<View>(R.id.history_touch_zone)
        
        assertEquals("History icon should be GONE in daily mode", View.GONE, historyIcon.visibility)
        assertEquals("History touch zone should be GONE in daily mode", View.GONE, historyTouchZone.visibility)
    }
}
