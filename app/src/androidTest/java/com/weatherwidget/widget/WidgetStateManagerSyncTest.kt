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
class WidgetStateManagerSyncTest {
    companion object {
        private const val TEST_PREFS_SUFFIX = "widget_state_sync"
    }

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 555

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AndroidTestWidgetState.useIsolatedPrefs(TEST_PREFS_SUFFIX, context)
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        AndroidTestWidgetState.cleanup(TEST_PREFS_SUFFIX, context)
    }

    @Test
    fun setCurrentDisplaySource_updatesCorrectly() {
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API)
        stateManager.setVisibleSourcesOrder(visibleSources)
        
        // Initial state
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        
        // Update to OPEN_METEO
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)
        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))
        
        // Update to WEATHER_API
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.WEATHER_API)
        assertEquals(WeatherSource.WEATHER_API, stateManager.getCurrentDisplaySource(testWidgetId))
        
        // Update back to NWS
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.NWS)
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
    }

    @Test
    fun setCurrentDisplaySource_ignoresUnknownSource() {
        val visibleSources = listOf(WeatherSource.NWS)
        stateManager.setVisibleSourcesOrder(visibleSources)
        
        // Initial state
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
        
        // Try to set a source that is not in the visible list (if possible, though WeatherSource is enum)
        // Actually, setCurrentDisplaySource only sets it if it's in the visible list.
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)
        
        // Should remain NWS because OPEN_METEO is not visible
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
    }
}
