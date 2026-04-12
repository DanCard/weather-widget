package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyViewApiToggleIntegrationRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 42

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))
        WidgetIntentRouter.setIsRefreshDisabledForTesting(true)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        WidgetIntentRouter.setIsRefreshDisabledForTesting(false)
    }

    @Test
    fun dailyForecast_apiToggle_cyclesThroughSources_preservesDailyMode() = runBlocking {
        val sources = stateManager.getEffectiveVisibleSourcesOrder(testWidgetId)

        assertEquals("Should start with NWS", WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("Should start in DAILY mode", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))

        try { WidgetIntentRouter.handleToggleApi(context, testWidgetId) } catch (_: Exception) {}
        assertEquals("After 1st toggle, should be OPEN_METEO", WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("View mode should still be DAILY after 1st toggle", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))

        try { WidgetIntentRouter.handleToggleApi(context, testWidgetId) } catch (_: Exception) {}
        assertEquals("After 2nd toggle, should be WEATHER_API", WeatherSource.WEATHER_API, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("View mode should still be DAILY after 2nd toggle", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))

        try { WidgetIntentRouter.handleToggleApi(context, testWidgetId) } catch (_: Exception) {}
        assertEquals("After 3rd toggle, should return to NWS", WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals("View mode should still be DAILY after 3rd toggle", ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
    }
}