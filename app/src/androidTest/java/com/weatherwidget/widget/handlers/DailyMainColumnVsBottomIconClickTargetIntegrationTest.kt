package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.testutil.dateEpoch
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Integration test verifying the split click behavior in the daily forecast view.
 *
 * Tapping the main column body of a cloudy day should navigate to TEMPERATURE mode.
 * Tapping the bottom icon zone of the same cloudy day should navigate to CLOUD_COVER mode.
 */
@RunWith(AndroidJUnit4::class)
class DailyMainColumnVsBottomIconClickTargetIntegrationTest : IsolatedIntegrationTest("daily_click_target_split") {

    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 8881

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
        
        runBlocking {
            val todayStr = LocalDate.now().toString()
            val zoneId = java.time.ZoneId.systemDefault()
            val todayMidnight = LocalDate.now().atStartOfDay(zoneId).toInstant().toEpochMilli()
            
            db.forecastDao().insertForecast(
                ForecastEntity(
                    targetDate = dateEpoch(todayStr),
                    forecastDate = dateEpoch(todayStr),
                    locationLat = WeatherWidgetWorker.DEFAULT_LAT,
                    locationLon = WeatherWidgetWorker.DEFAULT_LON,
                    locationName = "Mountain View, CA",
                    highTemp = 72f,
                    lowTemp = 54f,
                    condition = "Cloudy",
                    source = WeatherSource.NWS.id,
                    precipProbability = 0,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            
            // Add at least one hourly forecast to pass the hasHourlyData check
            db.hourlyForecastDao().insertAll(
                listOf(
                    com.weatherwidget.data.local.HourlyForecastEntity(
                        dateTime = todayMidnight + 12 * 3600000L, // Noon
                        locationLat = WeatherWidgetWorker.DEFAULT_LAT,
                        locationLon = WeatherWidgetWorker.DEFAULT_LON,
                        temperature = 70f,
                        condition = "Cloudy",
                        source = WeatherSource.NWS.id,
                        precipProbability = 0,
                        fetchedAt = System.currentTimeMillis()
                    )
                )
            )
        }
    }

    @After
    override fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        super.cleanup()
    }

    @Test
    fun clickingMainColumnBody_onCloudyDay_navigatesToTemperatureMode() = runBlocking {
        // GIVEN: A daily widget in graph mode
        // Starting in PRECIPITATION to ensure we see a change
        stateManager.setViewMode(testWidgetId, ViewMode.PRECIPITATION)
        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))

        val now = LocalDate.now().atTime(12, 0)
        val views = renderDailyGraph(now)
        val applied = applyToRoot(views)
        
        // Ensure the zones and their parents are actually visible for the click to be meaningful
        assertEquals("Parent container should be visible", View.VISIBLE, applied.findViewById<View>(R.id.graph_day_zones).visibility)
        
        // The second column (index 1) is "Today" in our setup
        val mainColumnZone = applied.findViewById<View>(R.id.graph_day2_zone)
        assertNotNull("Main column zone for Today should exist", mainColumnZone)
        assertEquals("Main column zone should be visible", View.VISIBLE, mainColumnZone.visibility)

        // WHEN: Clicking the main column body
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clicked = false
        instrumentation.runOnMainSync {
            clicked = mainColumnZone.performClick()
        }
        assertTrue("performClick should return true", clicked)
        instrumentation.waitForIdleSync()

        // THEN: It should switch to TEMPERATURE mode
        waitForViewMode(ViewMode.TEMPERATURE)
        assertEquals("Should have navigated to TEMPERATURE mode", ViewMode.TEMPERATURE, stateManager.getViewMode(testWidgetId))
    }

    @Test
    fun clickingBottomIconZone_onCloudyDay_navigatesToCloudCoverMode() = runBlocking {
        // GIVEN: A daily widget in graph mode
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))

        val now = LocalDate.now().atTime(12, 0)
        val views = renderDailyGraph(now)
        val applied = applyToRoot(views)
        
        assertEquals("Parent bottom container should be visible", View.VISIBLE, applied.findViewById<View>(R.id.graph_bottom_day_zones).visibility)
        
        // The bottom icon zone for "Today" (index 1)
        val bottomIconZone = applied.findViewById<View>(R.id.graph_bottom_day2_zone)
        assertNotNull("Bottom icon zone for Today should exist", bottomIconZone)
        assertEquals("Bottom icon zone should be visible", View.VISIBLE, bottomIconZone.visibility)

        // WHEN: Clicking the bottom icon zone
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clicked = false
        instrumentation.runOnMainSync {
            clicked = bottomIconZone.performClick()
        }
        assertTrue("performClick should return true", clicked)
        instrumentation.waitForIdleSync()

        // THEN: It should switch to CLOUD_COVER mode
        waitForViewMode(ViewMode.CLOUD_COVER)
        assertEquals("Should have navigated to CLOUD_COVER mode", ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
    }

    private suspend fun renderDailyGraph(now: LocalDateTime): RemoteViews {
        val today = now.toLocalDate()
        
        // We need some weather data to render
        val weatherList = db.forecastDao().getForecastsInRange(
            dateEpoch(today.toString()),
            dateEpoch(today.plusDays(7).toString()),
            WeatherWidgetWorker.DEFAULT_LAT,
            WeatherWidgetWorker.DEFAULT_LON
        )

        // Prepare data for setup handlers
        val displaySource = WeatherSource.NWS
        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherList.associateBy { f -> LocalDate.ofEpochDay(f.targetDate / (24 * 60 * 60 * 1000L)) },
            forecastSnapshots = emptyMap(),
            numColumns = 5,
            displaySource = displaySource,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            stateManager = stateManager,
            appWidgetId = testWidgetId
        )

        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        
        // Explicitly set parent visibility as DailyViewHandler.updateWidget would
        views.setViewVisibility(R.id.graph_day_zones, View.VISIBLE)
        views.setViewVisibility(R.id.graph_bottom_day_zones, View.VISIBLE)

        DailyViewHandler.setupGraphDayClickHandlers(
            context, views, testWidgetId, now, days, 
            WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON, 
            displaySource, 5
        )
        DailyViewHandler.setupGraphBottomDayClickHandlers(
            context, views, testWidgetId, now, days,
            WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON,
            displaySource, 5
        )
        
        return views
    }

    private fun applyToRoot(views: RemoteViews): View {
        val root = FrameLayout(context)
        val applied = views.apply(context, root as ViewGroup)
        // Ensure standard dimensions for touch zones to be clickable
        applied.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), 
                        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        applied.layout(0, 0, 1000, 1000)
        return applied
    }

    private fun waitForViewMode(expected: ViewMode) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (stateManager.getViewMode(testWidgetId) == expected) {
                return
            }
            Thread.sleep(100)
        }
    }
}
