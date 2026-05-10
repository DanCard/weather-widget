package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.widget.WidgetActions
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
import org.junit.Assert.assertTrue
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
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
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

        // Sequential columnIndex: yesterday=zone0, today=zone1, tomorrow=zone2, zone3+ still visible but empty
        assertEquals("Zone 0 should be VISIBLE", View.VISIBLE, applied.findViewById<View>(R.id.graph_day1_zone).visibility)
        assertEquals("Zone 1 should be VISIBLE", View.VISIBLE, applied.findViewById<View>(R.id.graph_day2_zone).visibility)
        assertEquals("Zone 2 should be VISIBLE", View.VISIBLE, applied.findViewById<View>(R.id.graph_day3_zone).visibility)
        assertEquals("Zone 3 should be VISIBLE (empty but present for grid stability)", View.VISIBLE, applied.findViewById<View>(R.id.graph_day4_zone).visibility)

        // Zone 1 = today (colIndex 1) — click fires broadcast
        val zone1 = applied.findViewById<View>(R.id.graph_day2_zone)
        zone1.performClick()
        val broadcasts = shadowOf(context as android.app.Application).broadcastIntents
        assertEquals(WidgetActions.ACTION_DAY_CLICK, broadcasts.last().action)
        assertEquals(todayStr, broadcasts.last().getStringExtra("date"))
        assertEquals(2, broadcasts.last().getIntExtra("index", -1)) // colIndex 1 + 1 = 2

        val bottomZone1 = applied.findViewById<View>(R.id.graph_bottom_day2_zone)
        assertEquals(View.VISIBLE, bottomZone1.visibility)
        bottomZone1.performClick()
        assertEquals(WidgetActions.ACTION_DAY_CLICK, broadcasts.last().action)
        assertEquals(todayStr, broadcasts.last().getStringExtra("date"))

        // Zone 0 = yesterday (colIndex 0) — click fires broadcast for yesterday
        val zone0 = applied.findViewById<View>(R.id.graph_day1_zone)
        zone0.performClick()
        assertEquals(WidgetActions.ACTION_DAY_CLICK, broadcasts.last().action)
        assertEquals(yesterdayStr, broadcasts.last().getStringExtra("date"))

        val bottomZone0 = applied.findViewById<View>(R.id.graph_bottom_day1_zone)
        assertEquals(View.VISIBLE, bottomZone0.visibility)
        bottomZone0.performClick()
        assertEquals(WidgetActions.ACTION_DAY_CLICK, broadcasts.last().action)
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
        // Zone 7 (graph_day8_zone) = today+6 (offset 6) — click must fire a broadcast
        val zone7 = applied.findViewById<View>(R.id.graph_day8_zone)
        assertEquals("Zone 7 should be VISIBLE", View.VISIBLE, zone7.visibility)
        val countBefore = broadcasts.size
        zone7.performClick()
        assertEquals("Zone 7 click should fire a broadcast", countBefore + 1, broadcasts.size)
        assertEquals(WidgetActions.ACTION_DAY_CLICK, broadcasts.last().action)
        assertEquals(col7Str, broadcasts.last().getStringExtra("date"))

        // Zone 8 (graph_day9_zone) is empty — must still be VISIBLE for grid stability
        val zone8 = applied.findViewById<View>(R.id.graph_day9_zone)
        assertEquals("Zone 8 should be VISIBLE", View.VISIBLE, zone8.visibility)
        val bottomZone8 = applied.findViewById<View>(R.id.graph_bottom_day9_zone)
        assertEquals("Bottom zone 8 should be VISIBLE", View.VISIBLE, bottomZone8.visibility)
    }

    @Test
    fun `setupGraphDayClickHandlers aligns touch zones with columns when middle days are missing`() = runBlocking {
        // GIVEN: 9 columns, data for yesterday and today+2 only (today and tomorrow absent)
        // dayOffsets = [-1, 0, 1, 2, 3, 4, 5, 6, 7]; yesterday hits index 0, today+2 hits index 3
        // With current behavior, all 9 slots are returned. yesterday=0, today+2=3
        val now = LocalDateTime.of(2026, 3, 20, 12, 0)
        val today = now.toLocalDate()
        val yesterdayStr = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val skippedStr = today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(22)
        stateManager.setZoomLevel(22, ZoomLevel.WIDE)
        stateManager.setViewMode(22, com.weatherwidget.widget.ViewMode.DAILY)

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
        }
        every { appWidgetManager.getAppWidgetOptions(22) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(22, capture(viewsSlot)) } just runs

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 22,
            weatherList = listOf(
                createWeather(yesterdayStr),
                createWeather(skippedStr),   // today and tomorrow absent
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

        // yesterday=zone0, skipped=zone3, other zones still visible but empty
        assertEquals("Zone 0 should be VISIBLE", View.VISIBLE, applied.findViewById<View>(R.id.graph_day1_zone).visibility)
        assertEquals("Zone 3 should be VISIBLE", View.VISIBLE, applied.findViewById<View>(R.id.graph_day4_zone).visibility)
        assertEquals("Bottom zone 0 should be VISIBLE", View.VISIBLE, applied.findViewById<View>(R.id.graph_bottom_day1_zone).visibility)
        assertEquals("Bottom zone 3 should be VISIBLE", View.VISIBLE, applied.findViewById<View>(R.id.graph_bottom_day4_zone).visibility)

        // Zone 0 = yesterday — click fires broadcast
        applied.findViewById<View>(R.id.graph_day1_zone).performClick()
        assertEquals(WidgetActions.ACTION_DAY_CLICK, broadcasts.last().action)
        assertEquals(yesterdayStr, broadcasts.last().getStringExtra("date"))

        // Zone 3 = today+2 (skippedStr) — click fires broadcast
        applied.findViewById<View>(R.id.graph_day4_zone).performClick()
        assertEquals(WidgetActions.ACTION_DAY_CLICK, broadcasts.last().action)
        assertEquals(skippedStr, broadcasts.last().getStringExtra("date"))
    }

    @Test
    fun `daily graph column count stays stable when navigating backward gains extra data`() = runBlocking {
        // GIVEN: 9-column widget.  At offset 0 only 3 days have data (yesterday, today, tomorrow).
        // At offset -1 all 9 slots happen to have data → days.size would be 9 without capping.
        val now = LocalDateTime.of(2026, 3, 20, 12, 0)
        val today = now.toLocalDate()

        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(30)
        stateManager.setZoomLevel(30, ZoomLevel.WIDE)
        stateManager.setViewMode(30, com.weatherwidget.widget.ViewMode.DAILY)

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
        }
        every { appWidgetManager.getAppWidgetOptions(30) } returns options
        val capturedViews = mutableListOf<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(30, any()) } answers {
            capturedViews += secondArg<android.widget.RemoteViews>()
        }

        // Offset 0 render: 3 days of data → baseline = 3
        val sparseWeather = listOf(
            createWeather(today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)),
            createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            createWeather(today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)),
        )
        DailyViewHandler.updateWidget(
            context = context, appWidgetManager = appWidgetManager, appWidgetId = 30,
            weatherList = sparseWeather, forecastSnapshots = emptyMap(),
            hourlyForecasts = emptyList(), currentTemps = emptyList(),
            dailyActualsBySource = emptyMap(), repository = null, now = now,
        )

        // Offset -1 render: more data available (7 days) but should cap to baseline of 3
        stateManager.setDateOffset(30, -1)
        val fullWeather = (-3L..5L).map { offset ->
            createWeather(today.plusDays(offset).format(DateTimeFormatter.ISO_LOCAL_DATE))
        }
        DailyViewHandler.updateWidget(
            context = context, appWidgetManager = appWidgetManager, appWidgetId = 30,
            weatherList = fullWeather, forecastSnapshots = emptyMap(),
            hourlyForecasts = emptyList(), currentTemps = emptyList(),
            dailyActualsBySource = emptyMap(), repository = null, now = now,
        )

        assertEquals(2, capturedViews.size)
        val firstApplied = capturedViews[0].apply(context, FrameLayout(context))
        val secondApplied = capturedViews[1].apply(context, FrameLayout(context))

        // Count visible zones in each render — must be equal
        val zoneIds = listOf(
            R.id.graph_day1_zone, R.id.graph_day2_zone, R.id.graph_day3_zone, R.id.graph_day4_zone,
            R.id.graph_day5_zone, R.id.graph_day6_zone, R.id.graph_day7_zone, R.id.graph_day8_zone,
            R.id.graph_day9_zone, R.id.graph_day10_zone,
        )
        val bottomZoneIds = listOf(
            R.id.graph_bottom_day1_zone, R.id.graph_bottom_day2_zone, R.id.graph_bottom_day3_zone, R.id.graph_bottom_day4_zone,
            R.id.graph_bottom_day5_zone, R.id.graph_bottom_day6_zone, R.id.graph_bottom_day7_zone, R.id.graph_bottom_day8_zone,
            R.id.graph_bottom_day9_zone, R.id.graph_bottom_day10_zone,
        )
        val firstVisibleCount = zoneIds.count { firstApplied.findViewById<View>(it).visibility == View.VISIBLE }
        val secondVisibleCount = zoneIds.count { secondApplied.findViewById<View>(it).visibility == View.VISIBLE }
        val firstBottomVisibleCount = bottomZoneIds.count { firstApplied.findViewById<View>(it).visibility == View.VISIBLE }
        val secondBottomVisibleCount = bottomZoneIds.count { secondApplied.findViewById<View>(it).visibility == View.VISIBLE }

        assertEquals(
            "Column count must stay stable across navigation offsets",
            firstVisibleCount, secondVisibleCount,
        )
        assertEquals(
            "Bottom row count must stay stable across navigation offsets",
            firstBottomVisibleCount, secondBottomVisibleCount,
        )
    }

    @Test
    fun `night rain overlay stays within bottom band so cloud icon taps are not blocked`() = runBlocking {
        val now = LocalDateTime.of(2026, 5, 1, 12, 0)

        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(31)
        stateManager.setHourlyOffset(31, 0)
        stateManager.setZoomLevel(31, ZoomLevel.WIDE)
        stateManager.setViewMode(31, com.weatherwidget.widget.ViewMode.DAILY)

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
        }
        every { appWidgetManager.getAppWidgetOptions(31) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(31, capture(viewsSlot)) } just runs

        val weatherList = listOf(
            createWeather("2026-04-30"),
            createWeather("2026-05-01"),
            createWeather("2026-05-02"),
            createWeather("2026-05-03"),
            createWeather("2026-05-04"),
        )

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 31,
            weatherList = weatherList,
            forecastSnapshots = emptyMap(),
            hourlyForecasts = emptyList(),
            currentTemps = emptyList(),
            dailyActualsBySource = emptyMap(),
            repository = null,
            now = now,
        )

        val applied = applyMeasuredViews(viewsSlot.captured)
        val nightRainOverlay = applied.findViewById<View>(R.id.graph_night_rain_zones)
        val bottomDayZones = applied.findViewById<View>(R.id.graph_bottom_day_zones)

        assertEquals(View.VISIBLE, nightRainOverlay.visibility)
        assertEquals(View.VISIBLE, bottomDayZones.visibility)
        assertTrue(
            "Night rain overlay should start no higher than the bottom icon/label band or it can swallow cloud-icon taps",
            nightRainOverlay.top >= bottomDayZones.top,
        )
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

    private fun applyMeasuredViews(views: android.widget.RemoteViews): View {
        val root = FrameLayout(context)
        val applied = views.apply(context, root as ViewGroup)
        val widthSpec = MeasureSpec.makeMeasureSpec(600, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY)
        applied.measure(widthSpec, heightSpec)
        applied.layout(0, 0, applied.measuredWidth, applied.measuredHeight)
        return applied
    }
}
