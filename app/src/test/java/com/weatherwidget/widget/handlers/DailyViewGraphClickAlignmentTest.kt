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
import com.weatherwidget.testutil.TestData.dateEpoch
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
        // GIVEN: 9 columns with yesterday, today, and tomorrow populated
        val now = LocalDateTime.of(2026, 3, 20, 12, 0)
        val today = now.toLocalDate()
        val yesterdayStr = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
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

        // Yesterday, today, tomorrow all have data; days beyond tomorrow omitted
        val weatherList = listOf(
            createWeather(yesterdayStr),
            createWeather(todayStr),
            createWeather(tomorrowStr),
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

        // Sequential columnIndex: yesterday=zone0, today=zone1, tomorrow=zone2, zone3+ GONE
        assertEquals("Zone 0 should be VISIBLE", View.VISIBLE, applied.findViewById<View>(R.id.graph_day1_zone).visibility)
        assertEquals("Zone 1 should be VISIBLE", View.VISIBLE, applied.findViewById<View>(R.id.graph_day2_zone).visibility)
        assertEquals("Zone 2 should be VISIBLE", View.VISIBLE, applied.findViewById<View>(R.id.graph_day3_zone).visibility)
        assertEquals("Zone 3 should be GONE (beyond days.size)", View.GONE, applied.findViewById<View>(R.id.graph_day4_zone).visibility)

        // Zone 1 = today (colIndex 1) — click fires broadcast
        val zone1 = applied.findViewById<View>(R.id.graph_day2_zone)
        zone1.performClick()
        val broadcasts = shadowOf(context as android.app.Application).broadcastIntents
        assertEquals(WeatherWidgetProvider.ACTION_DAY_CLICK, broadcasts.last().action)
        assertEquals(todayStr, broadcasts.last().getStringExtra("date"))
        assertEquals(2, broadcasts.last().getIntExtra("index", -1)) // colIndex 1 + 1 = 2

        // Zone 0 = yesterday (colIndex 0) — click fires broadcast for yesterday
        val zone0 = applied.findViewById<View>(R.id.graph_day1_zone)
        zone0.performClick()
        assertEquals(WeatherWidgetProvider.ACTION_DAY_CLICK, broadcasts.last().action)
        assertEquals(yesterdayStr, broadcasts.last().getStringExtra("date"))
    }

    @Test
    fun `setupGraphDayClickHandlers aligns touch zones with columns when last day is missing`() = runBlocking {
        // GIVEN: 9 columns, data for today and today+6, but NOT today+7 (last column)
        // With offsets [-1, 0, 1..7], column 7 = today+6 (columnIndex 7), column 8 = today+7 (no data)
        val now = LocalDateTime.of(2026, 3, 20, 12, 0)
        val today = now.toLocalDate()
        val col7Str = today.plusDays(6).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(21)
        stateManager.setZoomLevel(21, ZoomLevel.WIDE)
        stateManager.setViewMode(21, com.weatherwidget.widget.ViewMode.DAILY)

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
        }
        every { appWidgetManager.getAppWidgetOptions(21) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(21, capture(viewsSlot)) } just runs

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 21,
            weatherList = listOf(
                createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
                createWeather(col7Str),
                // today+7 intentionally omitted — this is the last column slot with no data
            ),
            forecastSnapshots = emptyMap(),
            hourlyForecasts = emptyList(),
            currentTemps = emptyList(),
            dailyActualsBySource = emptyMap(),
            repository = null,
            now = now,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root)
        val broadcasts = shadowOf(context as android.app.Application).broadcastIntents

        // Sequential columnIndex: today=zone0, today+6=zone1, zone2+ GONE
        // Zone 1 (graph_day2_zone) = today+6 — click must fire a broadcast
        val zone1 = applied.findViewById<View>(R.id.graph_day2_zone)
        assertEquals("Zone 1 should be VISIBLE", View.VISIBLE, zone1.visibility)
        val countBefore = broadcasts.size
        zone1.performClick()
        assertEquals("Zone 1 click should fire a broadcast", countBefore + 1, broadcasts.size)
        assertEquals(WeatherWidgetProvider.ACTION_DAY_CLICK, broadcasts.last().action)
        assertEquals(col7Str, broadcasts.last().getStringExtra("date"))

        // Zone 2 (graph_day3_zone) is beyond days.size — must be GONE
        val zone2 = applied.findViewById<View>(R.id.graph_day3_zone)
        assertEquals("Zone 2 should be GONE", View.GONE, zone2.visibility)
    }

    private fun createWeather(date: String): ForecastEntity {
        return ForecastEntity(
            targetDate = dateEpoch(date),
            forecastDate = dateEpoch(date),
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
