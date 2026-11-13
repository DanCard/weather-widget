package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.RefreshScheduler
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [35])
class NavigationPersistenceRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId by lazy { com.weatherwidget.test.BoundTestWidget.create() }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        RefreshScheduler.setIsRefreshDisabledForTesting(true)
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        stateManager.setViewMode(testWidgetId, ViewMode.DAILY)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        RefreshScheduler.setIsRefreshDisabledForTesting(false)
    }

    @Test
    fun transitionBetweenHourlyAndPrecip_preservesOffset() {
        runBlocking {
            WidgetIntentRouter.handleSetView(
                context,
                testWidgetId,
                ViewMode.PRECIPITATION,
                48
            )

        }

        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))
        assertEquals(48, stateManager.getHourlyOffset(testWidgetId))

        runBlocking {
            WidgetIntentRouter.handleSetView(
                context,
                testWidgetId,
                ViewMode.TEMPERATURE
            )

        }

        assertEquals("View mode should be HOURLY", ViewMode.TEMPERATURE, stateManager.getViewMode(testWidgetId))
        assertEquals("Offset should be preserved", 48, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun toggleFromHourlyToPrecip_preservesOffset() {
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
        stateManager.setHourlyOffset(testWidgetId, 24)

        runBlocking {
            WidgetIntentRouter.handleTogglePrecip(context, testWidgetId)

        }

        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))
        assertEquals("Offset should be preserved when toggling PRECIP from HOURLY", 24, stateManager.getHourlyOffset(testWidgetId))
    }

    @org.junit.After
    fun noSwallowedInteractionFailure() = com.weatherwidget.test.BoundTestWidget.assertNoInteractionFailures()
}
