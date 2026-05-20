package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
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
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

/**
 * Regression test to ensure that historical navigation offsets (e.g., -72h for 3 days ago)
 * are not arbitrarily clamped by the state manager or router.
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
class HistoryClampingRegressionRoboTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testWidgetId = 9991
    private lateinit var stateManager: WidgetStateManager

    @Before
    fun setup() {
        WeatherDatabase.setIsTesting(true)
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
    }

    @After
    fun resetGlobalTestingFlag() {
        // WeatherDatabase.isTesting is a JVM-wide static. Restore to false so it
        // does not leak into later tests that rely on the default (e.g. those
        // whose prefs paths branch on SharedPreferencesUtil's _test_default suffix).
        WeatherDatabase.setIsTesting(false)
    }

    @Test
    fun `handleSetView preserves 7 day lookback offset without clamping`() = runBlocking {
        // GIVEN: A target offset of 7 days ago (-168 hours)
        val sevenDaysAgoOffset = -168
        
        // WHEN: Setting the view via the router (simulating a widget click)
        WidgetIntentRouter.handleSetView(
            context = context,
            appWidgetId = testWidgetId,
            targetMode = ViewMode.TEMPERATURE,
            targetOffset = sevenDaysAgoOffset
        )

        // THEN: The stored offset should be EXACTLY -168, not clamped to -24
        val storedOffset = stateManager.getHourlyOffset(testWidgetId)
        assertEquals("Offset should not be clamped to -24; it should preserve the requested -168", 
            sevenDaysAgoOffset, storedOffset)
    }

    @Test
    fun `handleSetView preserves 14 day lookback offset`() = runBlocking {
        val fourteenDaysAgoOffset = -336
        
        WidgetIntentRouter.handleSetView(
            context = context,
            appWidgetId = testWidgetId,
            targetMode = ViewMode.TEMPERATURE,
            targetOffset = fourteenDaysAgoOffset
        )

        val storedOffset = stateManager.getHourlyOffset(testWidgetId)
        assertEquals("Offset should preserve -336 hours", fourteenDaysAgoOffset, storedOffset)
    }
}
