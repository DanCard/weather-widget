package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.testutil.AndroidTestWidgetState
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test to verify that the navigation offset is preserved
 * when switching between hourly and precipitation views.
 */
@RunWith(AndroidJUnit4::class)
class NavigationPersistenceTest {
    companion object {
        private const val TEST_PREFS_SUFFIX = "navigation_persistence"
    }

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 99991

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AndroidTestWidgetState.useIsolatedPrefs(TEST_PREFS_SUFFIX, context)
        WidgetIntentRouter.setDisableRefreshForTesting(true)
        stateManager = WidgetStateManager(context)
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        AndroidTestWidgetState.cleanup(TEST_PREFS_SUFFIX, context)
        WidgetIntentRouter.setDisableRefreshForTesting(false)
    }

    @Test
    fun transitionBetweenHourlyAndPrecip_preservesOffset() {
        // 1. Start in DAILY mode (set in setup)
        
        // 2. Simulate clicking on "Friday" (offset 48) to go to PRECIPITATION
        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(
                    context, 
                    testWidgetId, 
                    ViewMode.PRECIPITATION, 
                    48
                )
            } catch (_: Exception) {}
        }
        
        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))
        assertEquals(48, stateManager.getHourlyOffset(testWidgetId))
        
        // 3. Simulate clicking "Temperature" to go to HOURLY without specifying offset
        // This is what PrecipViewHandler does.
        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(
                    context, 
                    testWidgetId, 
                    ViewMode.TEMPERATURE
                )
            } catch (_: Exception) {}
        }
        
        // 4. Verify offset is preserved (fixed)
        assertEquals("View mode should be HOURLY", ViewMode.TEMPERATURE, stateManager.getViewMode(testWidgetId))
        assertEquals("Offset should be preserved", 48, stateManager.getHourlyOffset(testWidgetId))
    }
    
    @Test
    fun toggleFromHourlyToPrecip_preservesOffset() {
        // 1. Start in HOURLY mode with offset 24
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
        stateManager.setHourlyOffset(testWidgetId, 24)
        
        // 2. Toggle to PRECIPITATION
        runBlocking {
            try {
                WidgetIntentRouter.handleTogglePrecip(context, testWidgetId)
            } catch (_: Exception) {}
        }
        
        // 3. Verify offset is preserved (fixed)
        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))
        assertEquals("Offset should be preserved when toggling PRECIP from HOURLY", 24, stateManager.getHourlyOffset(testWidgetId))
    }
}
