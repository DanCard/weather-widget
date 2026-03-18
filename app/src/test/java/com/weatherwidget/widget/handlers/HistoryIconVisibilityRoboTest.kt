package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.app.Application
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
 * Robolectric tests verifying hourly header icon visibility across different view modes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
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
    fun `hourly temperature mode shows home and history icons`() = runBlocking {
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
        
        val homeIcon = applied.findViewById<View>(R.id.home_icon)
        val homeTouchZone = applied.findViewById<View>(R.id.home_touch_zone)
        val historyIcon = applied.findViewById<View>(R.id.history_icon)
        val historyTouchZone = applied.findViewById<View>(R.id.history_touch_zone)

        assertEquals("Home icon should be VISIBLE in hourly view", View.VISIBLE, homeIcon.visibility)
        assertEquals("Home touch zone should be VISIBLE in hourly view", View.VISIBLE, homeTouchZone.visibility)
        assertEquals("History icon should be VISIBLE in hourly view", View.VISIBLE, historyIcon.visibility)
        assertEquals("History touch zone should be VISIBLE in hourly view", View.VISIBLE, historyTouchZone.visibility)
    }

    @Test
    fun `daily mode hides home and history icons`() = runBlocking {
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
            hourlyForecasts = emptyList(),
            currentTemps = emptyList(),
            dailyActualsBySource = emptyMap(),
            repository = null,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root)
        
        val homeIcon = applied.findViewById<View>(R.id.home_icon)
        val homeTouchZone = applied.findViewById<View>(R.id.home_touch_zone)
        val historyIcon = applied.findViewById<View>(R.id.history_icon)
        val historyTouchZone = applied.findViewById<View>(R.id.history_touch_zone)

        assertEquals("Home icon should be GONE in daily mode", View.GONE, homeIcon.visibility)
        assertEquals("Home touch zone should be GONE in daily mode", View.GONE, homeTouchZone.visibility)
        assertEquals("History icon should be GONE in daily mode", View.GONE, historyIcon.visibility)
        assertEquals("History touch zone should be GONE in daily mode", View.GONE, historyTouchZone.visibility)
    }
}
