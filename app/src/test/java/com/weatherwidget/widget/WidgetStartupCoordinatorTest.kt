package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class WidgetStartupCoordinatorTest {
    @Test
    fun `one widget failure does not cancel a healthy sibling`() = runTest {
        val rendered = mutableListOf<Int>()

        val outcomes =
            runStartupTasksIsolated(intArrayOf(1, 2)) { appWidgetId ->
                if (appWidgetId == 1) error("broken widget")
                rendered += appWidgetId
            }

        assertEquals(listOf(2), rendered)
        assertEquals("broken widget", outcomes.single { it.appWidgetId == 1 }.failure?.message)
        assertNull(outcomes.single { it.appWidgetId == 2 }.failure)
    }

    @Test
    fun `structured cancellation still propagates`() = runTest {
        val cancellation = CancellationException("stop startup")

        val thrown =
            try {
                runStartupTasksIsolated(intArrayOf(1, 2)) { appWidgetId ->
                    if (appWidgetId == 1) throw cancellation
                    delay(Long.MAX_VALUE)
                }
                fail("expected cancellation")
                error("unreachable")
            } catch (e: CancellationException) {
                e
            }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun `operation timings measure execution rather than await order`() = runTest {
        val timer = StartupOperationTimer { testScheduler.currentTime }
        val slow = timer.async(this) {
            delay(300)
            "slow"
        }
        val fast = timer.async(this) {
            delay(100)
            "fast"
        }

        val slowResult = slow.await()
        val fastResult = fast.await()

        assertEquals(300L, slowResult.durationMs)
        assertEquals(100L, fastResult.durationMs)
    }

    /**
     * A widget's startup paint cancelled from outside — WidgetUpdateTracker does this when a newer
     * paint of the widget starts — must not abort the batch: the live actuals pass still runs
     * (Pixel 7 Pro 2026-11-18 07:40:03, `onUpdate_CANCELLED … DeferredCoroutine was cancelled`).
     * Uses runStartupTasksIsolated's real onStarted hook, as the coordinator does.
     * performance/261118-cold-start-daily-actuals-cost.md
     */
    @Test
    fun `a superseded widget paint lets the batch continue`() = runTest {
        val jobs = mutableMapOf<Int, Job>()
        var superseded: CancellationException? = null
        var continued = false

        continueIfSuperseded(onSuperseded = { superseded = it }) {
            runStartupTasksIsolated(intArrayOf(1, 2), onStarted = { id, job -> jobs[id] = job }) { appWidgetId ->
                if (appWidgetId == 2) {
                    jobs.getValue(1).cancel() // the tracker superseding widget 1's paint
                } else {
                    delay(Long.MAX_VALUE)
                }
            }
        }
        continued = true

        println("WidgetStartupCoordinatorTest: superseded=${superseded?.message}")
        assertTrue(continued)
        assertTrue("the superseded paint was reported", superseded != null)
    }

    @Test
    fun `the caller's own cancellation is not mistaken for a superseded paint`() = runTest {
        val started = CompletableDeferred<Unit>()
        var reported = false
        var after = false
        val caller = launch {
            continueIfSuperseded(onSuperseded = { reported = true }) {
                runStartupTasksIsolated(intArrayOf(1)) {
                    started.complete(Unit)
                    delay(Long.MAX_VALUE)
                }
            }
            after = true
        }
        started.await()

        caller.cancel()
        caller.join()

        assertFalse("the live pass must not run in a cancelled caller", after)
        assertFalse(reported)
    }
}
