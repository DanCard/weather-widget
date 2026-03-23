package com.weatherwidget.widget.handlers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.testutil.dateEpoch
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Integration test for API source toggling in the Daily Forecast view.
 */
@RunWith(AndroidJUnit4::class)
class DailyViewApiToggleIntegrationTest : IsolatedIntegrationTest("daily_api_toggle") {

    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 42

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        
        // Ensure we're in DAILY mode
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
        
        // Setup a visible source order: NWS -> OPEN_METEO -> WEATHER_API
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))
        
        // Add a latest weather record so handleToggleApi has coordinates
        runBlocking {
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            db.forecastDao().insertForecast(
                ForecastEntity(
                    targetDate = dateEpoch(today),
                    forecastDate = dateEpoch(today),
                    highTemp = 75f,
                    lowTemp = 55f,
                    condition = "Sunny",
                    source = WeatherSource.NWS.id,
                    locationLat = 40.7128,
                    locationLon = -74.0060,
                    fetchedAt = System.currentTimeMillis()
                )
            )
        }
    }

    @Test
    fun dailyForecast_apiToggle_cyclesThroughSources_preservesDailyMode() = runBlocking {
        // Verify we have 3 sources in the cycle
        val sources = stateManager.getEffectiveVisibleSourcesOrder(testWidgetId)
        assertEquals("Should have 3 visible sources for this test", 3, sources.size)

        // Initial state
        assertEquals("Should start with NWS", WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("Should start in DAILY mode", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))

        // 1st click: NWS -> OPEN_METEO
        WidgetIntentRouter.handleToggleApi(context, testWidgetId)
        assertEquals("After 1st toggle, should be OPEN_METEO", WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("View mode should still be DAILY after 1st toggle", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))

        // 2nd click: OPEN_METEO -> WEATHER_API
        WidgetIntentRouter.handleToggleApi(context, testWidgetId)
        assertEquals("After 2nd toggle, should be WEATHER_API", WeatherSource.WEATHER_API, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("View mode should still be DAILY after 2nd toggle", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))

        // 3rd click: WEATHER_API -> NWS (return to original)
        WidgetIntentRouter.handleToggleApi(context, testWidgetId)
        assertEquals("After 3rd toggle, should return to NWS", WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("View mode should still be DAILY after 3rd toggle", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
    }
}
