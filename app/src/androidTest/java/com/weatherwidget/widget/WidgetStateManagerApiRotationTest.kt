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
class WidgetStateManagerApiRotationTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 99992

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        stateManager.setApiPreference(ApiPreference.PREFER_NWS)
    }

    @Test
    fun toggleDisplaySource_rotatesNwsToOpenMeteoToWeatherApi() {
        stateManager.setApiPreference(ApiPreference.PREFER_NWS)
        stateManager.resetToggleState(testWidgetId)

        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals(WeatherSource.OPEN_METEO, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.WEATHER_API, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.NWS, stateManager.toggleDisplaySource(testWidgetId))
    }
}
