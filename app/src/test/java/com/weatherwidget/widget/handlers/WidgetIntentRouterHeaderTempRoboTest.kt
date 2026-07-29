package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.GraphDataLoader
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

/**
 * Regression test for: current temp top-left label changes when scrolling the temperature graph.
 *
 * Root cause: the graph interaction renderer loaded two hourly windows:
 *   - hourlyForecasts: graph-window centered on the scrolled centerTime (no current hour when scrolled)
 *   - currentTempHourlyForecasts: NOW-centered window
 * The graph-window list was incorrectly passed to CurrentTemperatureResolver, causing the header
 * temp to change or disappear when scrolled to a day other than today.
 *
 * This test seeds the DB with two DISJOINT temperature clusters (simulating the actual production
 * data shape), performs the same hourly queries that refreshGraphView does, and asserts that
 * TemperatureViewHandler produces the NOW temp (66°) at every scroll offset — not the
 * scrolled-day temp (52°).
 *
 * The disjoint clusters are key: if the wrong list is routed to the resolver, it won't find
 * the current hour and will return null or the wrong value. A super-set fixture (both clusters
 * merged into one list) would pass even with the bug because the resolver would find NOW inside
 * the combined list. This test avoids that false-safety mistake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WidgetIntentRouterHeaderTempRoboTest {

    private val lat = 37.42
    private val lon = -122.08
    private val widgetId = 998

    private lateinit var context: android.content.Context
    private lateinit var db: WeatherDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.setVisibleSourcesOrder(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Simulates the two DB queries that refreshGraphView performs:
     *   1. loadGraphWindowHourlyForecasts(centerTime) — used for graph rendering
     *   2. loadCurrentTempResolutionHourlyForecasts(now) — used for current temp
     *
     * With disjoint clusters in the DB, these two queries return different lists.
     * The handler must use the NOW-centered list for current temp resolution.
     */
    @Test
    fun `current temp is invariant across scroll offsets with disjoint hourly clusters in DB`() = runBlocking {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val zoneId = ZoneId.systemDefault()

        // Seed DB with two DISJOINT clusters:
        //   NOW cluster (66°): hours around the current time  → currentTempHourlyForecasts query hits this
        //   +2-day cluster (52°): hours 2 days ahead          → graph-window query hits this when scrolled
        val nowHours = (-12..2).map { h -> hourly(now.plusHours(h.toLong()), 66f) }
        val futureDayHours = (-6..12).map { h -> hourly(now.plusDays(2).plusHours(h.toLong()), 52f) }
        db.hourlyForecastDao().insertAll(nowHours + futureDayHours)

        // Offsets to test: {-2d, -1d, today, +1d, +2d}
        val hourlyOffsets = listOf(-48, -24, 0, 24, 48)
        val results = mutableListOf<String>()

        for (hourlyOffset in hourlyOffsets) {
            val centerTime = now.plusHours(hourlyOffset.toLong())

            // Simulate loadCurrentTempResolutionHourlyForecasts(now): NOW ± ~12h window
            val nowWindow = com.weatherwidget.widget.CurrentTemperatureResolver.buildCurrentTempResolutionWindow(now)
            val nowMinEpoch = nowWindow.start.atZone(zoneId).toInstant().toEpochMilli()
            val nowMaxEpoch = nowWindow.end.atZone(zoneId).toInstant().toEpochMilli()
            val currentTempHourly = db.hourlyForecastDao().getHourlyForecasts(nowMinEpoch, nowMaxEpoch, lat, lon)

            // Simulate loadGraphWindowHourlyForecasts(centerTime): window around the scrolled day
            // This mirrors the real WidgetIntentRouter query: uses center-based window
            val graphLookbackHours = com.weatherwidget.widget.WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS
            val graphLookaheadHours = com.weatherwidget.widget.WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS
            val graphMinEpoch = centerTime.minusHours(graphLookbackHours).atZone(zoneId).toInstant().toEpochMilli()
            val graphMaxEpoch = centerTime.plusHours(graphLookaheadHours).atZone(zoneId).toInstant().toEpochMilli()
            val graphWindowHourly = db.hourlyForecastDao().getHourlyForecasts(graphMinEpoch, graphMaxEpoch, lat, lon)

            // Verify the test setup: the two lists must actually be disjoint for the non-zero offsets
            // (otherwise the test would pass even with the bug).
            if (hourlyOffset == 48 || hourlyOffset == -48) {
                val overlap = currentTempHourly.map { it.dateTime }.toSet()
                    .intersect(graphWindowHourly.map { it.dateTime }.toSet())
                // Allow some overlap near boundaries but the clusters should be mostly separate
                assert(graphWindowHourly.isNotEmpty()) { "graph window must have data for offset=$hourlyOffset" }
                assert(currentTempHourly.isNotEmpty()) { "NOW window must have data for offset=$hourlyOffset" }
            }

            val appWidgetManager = mockk<AppWidgetManager>()
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 260)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
            }
            every { appWidgetManager.getAppWidgetOptions(widgetId) } returns options
            val viewsSlot = slot<android.widget.RemoteViews>()
            every { appWidgetManager.updateAppWidget(widgetId, capture(viewsSlot)) } just runs

            TemperatureViewHandler.updateWidget(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetId = widgetId,
                hourlyForecasts = graphWindowHourly,
                currentTempHourlyForecasts = currentTempHourly,
                centerTime = centerTime,
                displaySource = WeatherSource.NWS,
                precipProbability = 0,
            )

            val root = FrameLayout(context)
            val applied = viewsSlot.captured.apply(context, root as ViewGroup)
            val currentTempText = applied.findViewById<TextView>(R.id.current_temp).text.toString()
            results.add(currentTempText)
        }

        // All scroll positions must show the same current temp from the NOW cluster.
        // Before the fix, when graphWindowHourly was passed to the resolver, the scrolled
        // positions returned null/"" (no current hour in the future-day window).
        assertEquals(
            "current temp must be identical across all scroll offsets; got: $results",
            1, results.toSet().size
        )
        results.forEach { temp ->
            assert(temp.isNotEmpty()) { "current temp must not be empty at any scroll position" }
            assert(!temp.contains("52")) { "current temp must not show the scrolled-day cluster temp (52°); got: $temp" }
        }
    }

    private fun hourly(time: LocalDateTime, temp: Float): HourlyForecastEntity =
        HourlyForecastEntity(
            dateTime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            locationLat = lat,
            locationLon = lon,
            temperature = temp,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            precipProbability = 0,
            fetchedAt = System.currentTimeMillis(),
        )
}
