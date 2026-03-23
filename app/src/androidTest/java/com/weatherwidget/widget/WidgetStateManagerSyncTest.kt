package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.model.WeatherSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetStateManagerSyncTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 555

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
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
