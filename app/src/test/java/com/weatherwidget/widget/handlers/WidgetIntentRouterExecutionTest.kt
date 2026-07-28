package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@OptIn(ExperimentalCoroutinesApi::class)
@Category(ShortDuration::class)
class WidgetIntentRouterExecutionTest {

    @After
    fun tearDown() {
        WidgetIntentRouter.clearInteractionMutexesForTesting()
    }

    @Test
    fun `same widget interactions serialize while different widgets remain independent`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        launch {
            WidgetIntentRouter.withWidgetInteractionLock(appWidgetId = 7) {
                events += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                events += "first-end"
            }
        }
        firstEntered.await()

        launch {
            WidgetIntentRouter.withWidgetInteractionLock(appWidgetId = 7) {
                events += "second"
            }
        }
        launch {
            WidgetIntentRouter.withWidgetInteractionLock(appWidgetId = 8) {
                events += "other-widget"
            }
        }
        runCurrent()

        assertEquals(listOf("first-start", "other-widget"), events)

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first-start", "other-widget", "first-end", "second"), events)
    }

    @Test
    fun `batch render continues after one widget fails`() = runTest {
        val rendered = mutableListOf<Int>()
        val failures = mutableListOf<Int>()

        WidgetIntentRouter.forEachWidgetIsolated(
            appWidgetIds = intArrayOf(1, 2, 3),
            onFailure = { widgetId, _ -> failures += widgetId },
        ) { widgetId ->
            if (widgetId == 2) error("render failed")
            rendered += widgetId
        }

        assertEquals(listOf(1, 3), rendered)
        assertEquals(listOf(2), failures)
    }

    @Test
    fun `batch render continues when failure reporting also fails`() = runTest {
        val rendered = mutableListOf<Int>()

        WidgetIntentRouter.forEachWidgetIsolated(
            appWidgetIds = intArrayOf(1, 2, 3),
            onFailure = { _, _ -> error("reporting failed") },
        ) { widgetId ->
            if (widgetId == 2) error("render failed")
            rendered += widgetId
        }

        assertEquals(listOf(1, 3), rendered)
    }

    @Test
    fun `resize debounce keeps only the newest request per widget`() = runTest {
        val survived = mutableListOf<Int>()

        // Three rapid resize events for widget 7, as a drag emits. Only the last may render.
        repeat(3) { index ->
            launch {
                if (WidgetIntentRouter.awaitLatestResizeRequest(appWidgetId = 7)) survived += index
            }
            runCurrent()
        }
        advanceUntilIdle()

        assertEquals(listOf(2), survived)
    }

    @Test
    fun `resize debounce treats widgets independently`() = runTest {
        val survived = mutableListOf<Int>()

        launch { if (WidgetIntentRouter.awaitLatestResizeRequest(appWidgetId = 7)) survived += 7 }
        launch { if (WidgetIntentRouter.awaitLatestResizeRequest(appWidgetId = 8)) survived += 8 }
        advanceUntilIdle()

        assertEquals(listOf(7, 8), survived)
    }

    @Test
    fun `resize debounce does not hold the interaction lock while waiting`() = runTest {
        val events = mutableListOf<String>()

        launch {
            if (WidgetIntentRouter.awaitLatestResizeRequest(appWidgetId = 7)) events += "resize-render"
        }
        runCurrent()

        // The debounce is sleeping. A tap on the same widget must still acquire the lock immediately —
        // this is the regression the additive-sleep-under-lock bug caused.
        launch {
            WidgetIntentRouter.withWidgetInteractionLock(appWidgetId = 7) { events += "tap" }
        }
        runCurrent()

        assertEquals(listOf("tap"), events)

        advanceUntilIdle()
        assertEquals(listOf("tap", "resize-render"), events)
    }

    @Test
    fun `batch render propagates cancellation and stops later widgets`() = runTest {
        val rendered = mutableListOf<Int>()
        var cancelled = false

        try {
            WidgetIntentRouter.forEachWidgetIsolated(intArrayOf(1, 2, 3)) { widgetId ->
                rendered += widgetId
                if (widgetId == 2) throw CancellationException("cancel")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(listOf(1, 2), rendered)
    }
}
