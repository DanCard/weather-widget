package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.DailyForecastGraphRenderer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Instrumented integration test that verifies touch zone alignment on a real device/emulator.
 * Confirms that the physical touch targets compact to the rendered day count when
 * data is missing at the start of the window.
 */
@RunWith(AndroidJUnit4::class)
class DailyGraphTouchZoneAlignmentInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun setupGraphDayClickHandlers_compactsVisibleZones_onDevice() {
        // GIVEN: 9 columns available, but only 2 rendered days (Today and Tomorrow)
        val now = LocalDateTime.of(2026, 3, 20, 12, 0)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)

        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val appWidgetId = 5005
        val numColumns = 9

        // Weather data missing YESTERDAY (index 0)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = today,
                label = "Today",
                high = 70f,
                low = 50f,
                isToday = true,
                columnIndex = 1
            ),
            DailyForecastGraphRenderer.DayData(
                date = tomorrow,
                label = "Sat",
                high = 72f,
                low = 52f,
                columnIndex = 2
            )
        )

        // WHEN: Calling setupGraphDayClickHandlers directly
        DailyViewHandler.setupGraphDayClickHandlers(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            now = now,
            days = days,
            lat = 37.7749,
            lon = -122.4194,
            displaySource = WeatherSource.NWS,
            numColumns = numColumns
        )

        // THEN: Verify the view hierarchy after application
        val root = FrameLayout(context)
        val applied = views.apply(context, root)

        val zoneIds = listOf(
            R.id.graph_day1_zone, R.id.graph_day2_zone, R.id.graph_day3_zone, R.id.graph_day4_zone,
            R.id.graph_day5_zone, R.id.graph_day6_zone, R.id.graph_day7_zone, R.id.graph_day8_zone,
            R.id.graph_day9_zone, R.id.graph_day10_zone
        )

        // Only the two rendered day zones should be visible.
        for (i in 0 until 2) {
            assertEquals("Zone $i should be VISIBLE", View.VISIBLE, applied.findViewById<View>(zoneIds[i]).visibility)
        }
        for (i in 2 until zoneIds.size) {
            assertEquals("Zone $i should be GONE", View.GONE, applied.findViewById<View>(zoneIds[i]).visibility)
        }
        
        // Verify that the compacted first zone is clickable for Today.
        val zone0 = applied.findViewById<View>(R.id.graph_day1_zone)
        assertEquals("Today zone should be clickable", true, zone0.hasOnClickListeners())
        
        // Verify that the compacted second zone is clickable for Tomorrow.
        val zone1 = applied.findViewById<View>(R.id.graph_day2_zone)
        assertEquals("Tomorrow zone should be clickable", true, zone1.hasOnClickListeners())

        // Verify that hidden trailing zones are not clickable.
        val hiddenZone = applied.findViewById<View>(R.id.graph_day3_zone)
        assertEquals("Hidden trailing zone should NOT be clickable", false, hiddenZone.hasOnClickListeners())
    }
}
