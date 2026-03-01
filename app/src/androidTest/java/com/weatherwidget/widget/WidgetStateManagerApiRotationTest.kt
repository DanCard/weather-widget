package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetStateManagerApiRotationTest : IsolatedIntegrationTest("widget_state_api_rotation") {
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 777

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
    }

    @After
    override fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        super.cleanup()
    }

    @Test
    fun toggleDisplaySource_cyclesThroughVisibleSources() {
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API)
        stateManager.setVisibleSourcesOrder(visibleSources)
        
        // Initial state should be first in list
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        
        // First toggle: NWS -> OPEN_METEO
        assertEquals(WeatherSource.OPEN_METEO, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))
        
        // Second toggle: OPEN_METEO -> WEATHER_API
        assertEquals(WeatherSource.WEATHER_API, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.WEATHER_API, stateManager.getCurrentDisplaySource(testWidgetId))
        
        // Third toggle: WEATHER_API -> NWS
        assertEquals(WeatherSource.NWS, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
    }

    @Test
    fun toggleDisplaySource_withTwoSources() {
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        stateManager.setVisibleSourcesOrder(visibleSources)
        
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        
        // Toggle: NWS -> OPEN_METEO
        assertEquals(WeatherSource.OPEN_METEO, stateManager.toggleDisplaySource(testWidgetId))
        
        // Toggle: OPEN_METEO -> NWS
        assertEquals(WeatherSource.NWS, stateManager.toggleDisplaySource(testWidgetId))
    }
}
