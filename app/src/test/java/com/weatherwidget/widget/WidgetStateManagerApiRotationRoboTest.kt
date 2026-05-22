package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetStateManagerApiRotationRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 777

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
    fun toggleDisplaySource_cyclesThroughVisibleSources() {
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API)
        stateManager.setVisibleSourcesOrder(visibleSources)

        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))

        assertEquals(WeatherSource.OPEN_METEO, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))

        assertEquals(WeatherSource.WEATHER_API, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.WEATHER_API, stateManager.getCurrentDisplaySource(testWidgetId))

        assertEquals(WeatherSource.NWS, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
    }

    @Test
    fun toggleDisplaySource_withTwoSources() {
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        stateManager.setVisibleSourcesOrder(visibleSources)

        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))

        assertEquals(WeatherSource.OPEN_METEO, stateManager.toggleDisplaySource(testWidgetId))

        assertEquals(WeatherSource.NWS, stateManager.toggleDisplaySource(testWidgetId))
    }
}