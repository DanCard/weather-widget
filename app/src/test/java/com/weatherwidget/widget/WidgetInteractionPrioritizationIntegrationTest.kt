package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.widget.handlers.WidgetIntentActionHandler
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@OptIn(ExperimentalCoroutinesApi::class)
@Category(MediumDuration::class)
class WidgetInteractionPrioritizationIntegrationTest : RobolectricTest() {

    private lateinit var context: Context
    private val repository: WeatherRepository = mockk(relaxed = true)
    private val appWidgetManager: AppWidgetManager = mockk()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetUpdateTracker.clearForTesting()
        WidgetPreemptionSettler.clearForTesting()

        mockkStatic(AppWidgetManager::class)
        every { AppWidgetManager.getInstance(any()) } returns appWidgetManager
        every { appWidgetManager.getAppWidgetOptions(any()) } returns android.os.Bundle()

        mockkObject(WidgetIntentRouter)
        coEvery { WidgetIntentRouter.handleToggleApi(any(), any(), any()) } returns Unit
        coEvery { WidgetIntentRouter.renderWidgetFromCache(any(), any(), any()) } returns null
    }

    @After
    fun tearDown() {
        WidgetUpdateTracker.clearForTesting()
        WidgetPreemptionSettler.clearForTesting()
        unmockkAll()
    }

    @Test
    fun `user interaction records active widget and prior batch refresh renders it first`() = runTest {
        val receiver = WidgetInteractionReceiver().apply {
            scope = this@runTest
            this.repository = this@WidgetInteractionPrioritizationIntegrationTest.repository
        }

        // Three installed widgets: 201, 202, 203
        every {
            appWidgetManager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
        } returns intArrayOf(201, 202, 203)

        // User interacts with widget 202
        val toggleIntent = Intent(context, WidgetInteractionReceiver::class.java).apply {
            action = WidgetActions.ACTION_TOGGLE_API
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 202)
        }
        receiver.onReceive(context, toggleIntent)
        testScheduler.advanceUntilIdle()

        // Verify active widget recorded in WidgetStateManager
        val stateManager = WidgetStateManager(context)
        assertEquals(202, stateManager.getLastInteractedWidgetId())

        // Now verify prioritization ordering in batch update
        val prioritized = WidgetPaintPrioritizer.prioritize(
            context = context,
            appWidgetIds = intArrayOf(201, 202, 203),
            lastInteractedId = stateManager.getLastInteractedWidgetId(),
            appWidgetManager = appWidgetManager,
        )

        // Widget 202 must be prioritized at index 0!
        assertEquals(202, prioritized[0])
        assertEquals(3, prioritized.size)
        assertEquals(setOf(201, 202, 203), prioritized.toSet())
    }

    @Test
    fun `user interaction cancels active background jobs on other widgets and settles them later`() = runTest {
        WidgetPreemptionSettler.scope = this
        WidgetPreemptionSettler.settlementDelayMs = 2000L
        val renderedList = mutableListOf<Int>()
        WidgetPreemptionSettler.renderWidget = { _, id, _ -> renderedList.add(id) }

        val receiver = WidgetInteractionReceiver().apply {
            scope = this@runTest
            this.repository = this@WidgetInteractionPrioritizationIntegrationTest.repository
        }

        // Background sync running on widget 201 and UI paint on widget 203
        val job201 = Job()
        val job203 = Job()
        WidgetUpdateTracker.trackJob(201, job201, WidgetUpdateTracker.JobType.BACKGROUND_SYNC)
        WidgetUpdateTracker.trackJob(203, job203, WidgetUpdateTracker.JobType.UI_PAINT)

        // Tap arrives on widget 202
        val tapIntent = Intent(context, WidgetInteractionReceiver::class.java).apply {
            action = WidgetActions.ACTION_TOGGLE_API
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 202)
        }
        receiver.onReceive(context, tapIntent)
        testScheduler.advanceTimeBy(100)

        // Jobs for other widgets should be cancelled immediately
        assertTrue("Job on 201 must be cancelled", job201.isCancelled)
        assertTrue("Job on 203 must be cancelled", job203.isCancelled)

        // Cancelled widgets are scheduled for settlement
        assertEquals(setOf(201, 203), WidgetPreemptionSettler.pendingWidgets())

        // Before settlement debounce, no cache render for 201 and 203
        assertTrue("No settlement renders should have occurred before debounce expires", renderedList.isEmpty())

        // Advance past debounce delay (2000ms)
        testScheduler.advanceTimeBy(2000)
        testScheduler.advanceUntilIdle()

        // Preempted widgets settle from cache cleanly
        assertEquals(listOf(201, 203).sorted(), renderedList.sorted())
        assertTrue("Pending widgets should be cleared after settlement", WidgetPreemptionSettler.pendingWidgets().isEmpty())
    }
}
