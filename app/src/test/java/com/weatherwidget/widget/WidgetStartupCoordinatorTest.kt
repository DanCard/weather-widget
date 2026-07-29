package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
}
