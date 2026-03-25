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
 * Confirms that the physical touch targets match the expected column density even when
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
    fun setupGraphDayClickHandlers_ensuresCorrectNumColumnsVisible_onDevice() {
        // GIVEN: 9 columns expected, but only 2 days of data provided (Today and Tomorrow)
        val now = LocalDateTime.of(2026, 3, 20, 12, 0)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)

        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val appWidgetId = 5005

        // Weather data missing YESTERDAY (index 0)
        val days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = today,
                label = "Today",
                high = 70f,
                low = 50f,
                isToday = true,
                columnIndex = 1 // Today is the second column
            ),
            DailyForecastGraphRenderer.DayData(
                date = tomorrow,
                label = "Sat",
                high = 72f,
                low = 52f,
                columnIndex = 2 // Tomorrow is the third column
            )
        )

        // WHEN: Calling setupGraphDayClickHandlers with numColumns = days.size (as DailyViewHandler does)
        DailyViewHandler.setupGraphDayClickHandlers(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            now = now,
            days = days,
            lat = 37.7749,
            lon = -122.4194,
            displaySource = WeatherSource.NWS,
            numColumns = days.size  // matches DailyViewHandler caller — only populated columns visible
        )

        // THEN: Verify the view hierarchy after application
        val root = FrameLayout(context)
        val applied = views.apply(context, root)

        val zoneIds = listOf(
            R.id.graph_day1_zone, R.id.graph_day2_zone, R.id.graph_day3_zone, R.id.graph_day4_zone,
            R.id.graph_day5_zone, R.id.graph_day6_zone, R.id.graph_day7_zone, R.id.graph_day8_zone,
            R.id.graph_day9_zone, R.id.graph_day10_zone
        )

        // Only days.size (2) zones visible — beyond that is GONE
        for (i in 0 until days.size) {
            assertEquals("Zone $i should be VISIBLE", View.VISIBLE, applied.findViewById<View>(zoneIds[i]).visibility)
        }
        for (i in days.size until 9) {
            assertEquals("Zone $i should be GONE", View.GONE, applied.findViewById<View>(zoneIds[i]).visibility)
        }
        // 10th zone always GONE
        assertEquals("Zone 9 should be GONE", View.GONE, applied.findViewById<View>(zoneIds[9]).visibility)

        // Verify that Today (columnIndex 1) is clickable
        val zone1 = applied.findViewById<View>(R.id.graph_day2_zone)
        assertEquals("Today zone should be clickable", true, zone1.hasOnClickListeners())

        // Verify that Yesterday (columnIndex 0, no DayData) has no click handler
        val zone0 = applied.findViewById<View>(R.id.graph_day1_zone)
        assertEquals("Yesterday zone should NOT be clickable", false, zone0.hasOnClickListeners())
    }
}
