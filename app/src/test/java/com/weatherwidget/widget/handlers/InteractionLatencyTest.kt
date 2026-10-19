package com.weatherwidget.widget.handlers

import android.os.SystemClock
import com.weatherwidget.test.category.ShortDuration
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The persisted INTERACTION_E2E row (performance/261019-fold-first-source-cycle-slow-after-process-
 * start.md): which taps keep one, what it says, and that the widget lock reports its wait into the
 * tap's trace.
 */
@Category(ShortDuration::class)
class InteractionLatencyTest {

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
        WidgetInteractionCoordinator.clearForTesting()
    }

    @Test
    fun `the first cycle of a process is kept whatever its speed`() {
        for (ordinal in 1..InteractionLatency.EARLY_TAPS) {
            assertTrue(InteractionLatency.shouldPersist(ordinal, e2eMs = 120L))
        }
    }

    @Test
    fun `a warm fast tap is not kept, a warm slow one is`() {
        val warm = InteractionLatency.EARLY_TAPS + 1
        assertFalse(InteractionLatency.shouldPersist(warm, e2eMs = InteractionLatency.SLOW_E2E_MS - 1))
        assertTrue(InteractionLatency.shouldPersist(warm, e2eMs = InteractionLatency.SLOW_E2E_MS))
    }

    @Test
    fun `the row splits the tap and marks a process it probably started`() {
        val row = InteractionLatency.format(
            action = "ACTION_TOGGLE_API",
            appWidgetId = 345,
            ordinal = 1,
            processAgeAtReceiptMs = 1_200L,
            queueMs = 3L,
            lockWaitMs = 0L,
            workMs = 940L,
            e2eMs = 943L,
        )
        assertEquals(
            "action=ACTION_TOGGLE_API widget=345 e2e=943ms queue=3ms lockWait=0ms work=940ms " +
                "tapInProcess=1 procAge=1200ms cold=true",
            row,
        )
    }

    @Test
    fun `an unknown process start reads na and not cold`() {
        val row = InteractionLatency.format("ACTION_NAV_LEFT", 1, 9, Long.MAX_VALUE, 0L, 0L, 1L, 1L)
        assertTrue(row, row.endsWith("procAge=na cold=false"))
    }

    @Test
    fun `the widget lock adds its wait to the tap's trace`() = runTest {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returnsMany listOf(1_000L, 1_250L, 2_000L, 2_040L)
        val trace = InteractionTrace()

        withContext(trace) {
            WidgetInteractionCoordinator.withWidgetLock(345) {}
            WidgetInteractionCoordinator.withWidgetLock(345) {}
        }

        assertEquals(290L, trace.lockWaitMs)
    }

    @Test
    fun `a lock taken outside any tap has no trace to fill`() = runTest {
        // Worker paints share the lock; they must run unchanged with no trace in context.
        assertEquals(7, WidgetInteractionCoordinator.withWidgetLock(345) { 7 })
    }
}
