package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyViewGraphClickAlignmentTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `setupGraphDayClickHandlers aligns touch zones with columns when first day is missing`() = runBlocking {
        // GIVEN: 9 columns, but missing data for the first column (Yesterday)
        val now = LocalDateTime.of(2026, 3, 20, 12, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Mock StateManager to return 9 columns and WIDE zoom
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(20)
        stateManager.setHourlyOffset(20, 0)
        stateManager.setZoomLevel(20, ZoomLevel.WIDE)
        stateManager.setViewMode(20, com.weatherwidget.widget.ViewMode.DAILY)
        
        // Mock AppWidgetManager to return size that results in 9 columns
        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 600) // results in 9 cols
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300) // results in graph mode
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
        }
        every { appWidgetManager.getAppWidgetOptions(20) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(20, capture(viewsSlot)) } just runs

        // Weather data missing YESTERDAY (index 0)
        // Today will have index 1, Tomorrow index 2
        val weatherList = listOf(
            createWeather(todayStr),
            createWeather(tomorrowStr)
        )

        // WHEN: Updating the widget
        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 20,
            weatherList = weatherList,
            forecastSnapshots = emptyMap(),
            hourlyForecasts = emptyList(),
            currentTemps = emptyList(),
            dailyActualsBySource = emptyMap(),
            repository = null,
            now = now
        )

        // THEN: Verify the click zones
        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root)

        val zoneIds = listOf(
            R.id.graph_day1_zone, R.id.graph_day2_zone, R.id.graph_day3_zone, R.id.graph_day4_zone,
            R.id.graph_day5_zone, R.id.graph_day6_zone, R.id.graph_day7_zone, R.id.graph_day8_zone,
            R.id.graph_day9_zone, R.id.graph_day10_zone
        )

        // Check visibility: first 9 should be VISIBLE, 10th GONE
        for (i in 0 until 9) {
            assertEquals("Zone $i should be VISIBLE", View.VISIBLE, applied.findViewById<View>(zoneIds[i]).visibility)
        }
        assertEquals("Zone 9 should be GONE", View.GONE, applied.findViewById<View>(zoneIds[9]).visibility)

        // Verify click on Today (Zone 1) triggers the correct action
        val zone1 = applied.findViewById<View>(R.id.graph_day2_zone)
        zone1.performClick()
        
        val broadcasts = shadowOf(context as android.app.Application).broadcastIntents
        val lastBroadcast = broadcasts.last()
        assertEquals("com.weatherwidget.ACTION_DAY_CLICK", lastBroadcast.action)
        assertEquals(todayStr, lastBroadcast.getStringExtra("date"))
        assertEquals(2, lastBroadcast.getIntExtra("index", -1)) // index 1 + 1 = 2
        
        // Verify click on Zone 0 (Yesterday) does NOTHING
        val zone0 = applied.findViewById<View>(R.id.graph_day1_zone)
        val broadcastCountBefore = broadcasts.size
        zone0.performClick()
        assertEquals("Zone 0 should not trigger a broadcast", broadcastCountBefore, broadcasts.size)
    }

    private fun createWeather(date: String): ForecastEntity {
        return ForecastEntity(
            targetDate = date,
            forecastDate = date,
            locationLat = 37.7749,
            locationLon = -122.4194,
            locationName = "Test",
            highTemp = 70f,
            lowTemp = 50f,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            precipProbability = 0,
            fetchedAt = 1L
        )
    }
}
