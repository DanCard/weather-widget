package com.weatherwidget.widget.handlers

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.ObservationEntity
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
import java.time.ZoneId

/**
 * Integrated test to reproduce failure when clicking on a historical day (3 days back)
 * that only has observations but no hourly forecast data.
 */
@RunWith(AndroidJUnit4::class)
class DailyHistoryClickIntegrationTest : IsolatedIntegrationTest("daily_history_click") {

    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 8882

    @Before
    override fun setup() {
        super.setup()
        
        // Use the same preferences name as the app process (set by WeatherWidgetTestRunner)
        WidgetStateManager.setPrefsNameOverrideForTesting(WidgetStateManager.DEFAULT_TEST_PREFS_NAME)
        
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        
        // Setup historical data: 3 days ago
        val threeDaysAgo = LocalDate.now().minusDays(3)
        val zoneId = ZoneId.systemDefault()
        val startOfDay = threeDaysAgo.atStartOfDay(zoneId).toInstant().toEpochMilli()
        
        runBlocking {
            // 1. Insert daily forecast for 3 days ago (needed for visibility in daily view)
            db.forecastDao().insertAll(
                listOf(
                    ForecastEntity(
                        targetDate = dateEpoch(threeDaysAgo.toString()),
                        forecastDate = dateEpoch(threeDaysAgo.toString()),
                        locationLat = WeatherWidgetWorker.DEFAULT_LAT,
                        locationLon = WeatherWidgetWorker.DEFAULT_LON,
                        locationName = "Mountain View, CA",
                        highTemp = 65f,
                        lowTemp = 45f,
                        condition = "Sunny",
                        source = WeatherSource.NWS.id,
                        precipProbability = 0,
                        fetchedAt = System.currentTimeMillis()
                    )
                )
            )

            // 2. Insert historical observation for 3 days ago
            db.observationDao().insertAll(
                listOf(
                    ObservationEntity(
                        stationId = "KPAO",
                        stationName = "Palo Alto",
                        timestamp = startOfDay + 12 * 3600000L, // Noon
                        temperature = 60f,
                        condition = "Sunny",
                        locationLat = WeatherWidgetWorker.DEFAULT_LAT,
                        locationLon = WeatherWidgetWorker.DEFAULT_LON,
                        api = WeatherSource.NWS.id,
                        fetchedAt = System.currentTimeMillis()
                    )
                )
            )
            
            // NOTE: We EXPLICITLY do NOT insert hourly_forecasts for this day.
            // This is the condition reported by the user.
        }
    }

    @After
    override fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        super.cleanup()
    }

    @Test
    fun clickingHistoricalDay_withOnlyObservations_navigatesToTemperatureMode() = runBlocking {
        // GIVEN: A daily widget navigated to see 3 days ago
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
        stateManager.setDateOffset(testWidgetId, -2) // Center = today - 2. Leftmost = today - 3.
        
        // Wait for state to persist (it uses apply() internally)
        val stateDeadline = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < stateDeadline && stateManager.getDateOffset(testWidgetId) != -2) {
            Thread.sleep(50)
        }
        assertEquals("Date offset should be -2", -2, stateManager.getDateOffset(testWidgetId))
        
        val now = LocalDateTime.now()
        val views = renderDailyGraph(now)
        val applied = applyToRoot(views)
        
        // Index 0 (graph_day1_zone) should be threeDaysAgo (today - 3)
        val historyZone = applied.findViewById<View>(R.id.graph_day1_zone)
        assertNotNull("History zone for 3 days ago should exist", historyZone)
        assertEquals("History zone should be visible", View.VISIBLE, historyZone.visibility)

        // WHEN: Clicking the historical day
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clicked = false
        instrumentation.runOnMainSync {
            clicked = historyZone.performClick()
        }
        assertTrue("performClick should return true", clicked)
        instrumentation.waitForIdleSync()

        // THEN: It should switch to TEMPERATURE mode
        waitForViewMode(ViewMode.TEMPERATURE)
        assertEquals("Should have navigated to TEMPERATURE mode", ViewMode.TEMPERATURE, stateManager.getViewMode(testWidgetId))
    }

    private suspend fun renderDailyGraph(now: LocalDateTime): RemoteViews {
        val today = now.toLocalDate()
        val displaySource = WeatherSource.NWS
        val dateOffset = stateManager.getDateOffset(testWidgetId)
        val centerDate = today.plusDays(dateOffset.toLong())

        val forecasts = db.forecastDao().getForecastsInRange(
            dateEpoch(today.minusDays(5).toString()),
            dateEpoch(today.plusDays(7).toString()),
            WeatherWidgetWorker.DEFAULT_LAT,
            WeatherWidgetWorker.DEFAULT_LON
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = centerDate,
            today = today,
            weatherByDate = forecasts.associateBy { f -> LocalDate.ofEpochDay(f.targetDate / (24 * 60 * 60 * 1000L)) },
            forecastSnapshots = emptyMap(),
            numColumns = 5,
            displaySource = displaySource,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            stateManager = stateManager,
            appWidgetId = testWidgetId
        )

        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        views.setViewVisibility(R.id.graph_day_zones, View.VISIBLE)

        DailyClickHandlerFactory.setupGraphDayClickHandlers(
            context, views, testWidgetId, now, days, 
            WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON, 
            displaySource, 5
        )
        
        return views
    }

    private fun applyToRoot(views: RemoteViews): View {
        val root = FrameLayout(context)
        val applied = views.apply(context, root as ViewGroup)
        applied.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), 
                        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        applied.layout(0, 0, 1000, 1000)
        return applied
    }

    private fun waitForViewMode(expected: ViewMode) {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (stateManager.getViewMode(testWidgetId) == expected) {
                return
            }
            Thread.sleep(100)
        }
    }
}
