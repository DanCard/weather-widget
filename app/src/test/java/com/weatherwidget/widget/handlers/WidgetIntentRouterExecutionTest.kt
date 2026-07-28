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
