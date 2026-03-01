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
class WidgetStateManagerSyncTest : IsolatedIntegrationTest("widget_state_sync") {
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 555

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
    fun setCurrentDisplaySource_updatesCorrectly() {
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API)
        stateManager.setVisibleSourcesOrder(visibleSources)
        
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)
        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))
        
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.WEATHER_API)
        assertEquals(WeatherSource.WEATHER_API, stateManager.getCurrentDisplaySource(testWidgetId))
        
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.NWS)
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
    }

    @Test
    fun setCurrentDisplaySource_ignoresUnknownSource() {
        val visibleSources = listOf(WeatherSource.NWS)
        stateManager.setVisibleSourcesOrder(visibleSources)
        
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
    }
}
