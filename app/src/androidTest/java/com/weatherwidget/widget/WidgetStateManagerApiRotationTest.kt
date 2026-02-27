package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.AndroidTestWidgetState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetStateManagerApiRotationTest {
    companion object {
        private const val TEST_PREFS_SUFFIX = "widget_state_api_rotation"
    }

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 99992

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AndroidTestWidgetState.useIsolatedPrefs(TEST_PREFS_SUFFIX, context)
        stateManager = WidgetStateManager(context)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        stateManager.setVisibleSourcesOrder(
            listOf(WeatherSource.NWS, WeatherSource.WEATHER_API, WeatherSource.OPEN_METEO),
        )
        AndroidTestWidgetState.cleanup(TEST_PREFS_SUFFIX, context)
    }

    @Test
    fun toggleDisplaySource_rotatesNwsToWapiToOpenMeteo() {
        stateManager.setVisibleSourcesOrder(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API),
        )
        stateManager.resetToggleState(testWidgetId)

        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals(WeatherSource.OPEN_METEO, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.WEATHER_API, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.NWS, stateManager.toggleDisplaySource(testWidgetId))
    }

    @Test
    fun toggleDisplaySource_skipsHiddenSources() {
        stateManager.setVisibleSourcesOrder(
            listOf(WeatherSource.NWS, WeatherSource.WEATHER_API),
        )
        stateManager.resetToggleState(testWidgetId)

        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals(WeatherSource.WEATHER_API, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.NWS, stateManager.toggleDisplaySource(testWidgetId))
    }
}
