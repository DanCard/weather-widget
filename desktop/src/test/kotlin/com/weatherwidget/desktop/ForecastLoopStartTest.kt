package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * After a resume the forecast loop plans when the catch-up refresh is done, not 5 min later
 * (performance/261019-desktop-notebook-morning-forecast.md).
 */
@Category(ShortDuration::class)
class ForecastLoopStartTest {
    private val settle = FORECAST_LOOP_STARTUP_SETTLE_MS

    @Test
    fun `which restarts follow the launch refresh`() {
        assertTrue(forecastLoopWaitsForLaunch(STARTUP_REASON))
        assertTrue(forecastLoopWaitsForLaunch("resume:logind"))
        assertTrue(forecastLoopWaitsForLaunch("network:restored"))
        assertFalse(forecastLoopWaitsForLaunch(SOURCE_CYCLE_REASON))
        assertFalse(forecastLoopWaitsForLaunch("source_or_location_change"))
    }

    @Test
    fun `after a resume the loop plans as soon as the catch-up refresh finishes`() = runTest {
        val done = CompletableDeferred<Unit>()
        val launchJob = launch { done.await() }
        val wait = async { awaitLaunch("network:restored", launchJob, settle) }
        advanceTimeBy(40_000)
        runCurrent()
        assertFalse(wait.isCompleted)
        done.complete(Unit)
        runCurrent()
        assertEquals("launch_done", wait.await())
        assertEquals(40_000L, currentTime)
    }

    @Test
    fun `a catch-up refresh still running at 5 min leaves the cap in charge`() = runTest {
        val launchJob = launch { delay(settle * 3) }
        assertEquals("settle", awaitLaunch("resume:logind", launchJob, settle))
        assertEquals(settle, currentTime)
        launchJob.cancel()
    }

    @Test
    fun `no launch refresh, or one already done - plan at once`() = runTest {
        assertEquals("launch_done", awaitLaunch(STARTUP_REASON, null, settle))
        val finished = launch { }
        runCurrent()
        assertEquals("launch_done", awaitLaunch(STARTUP_REASON, finished, settle))
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a source change keeps the whole settle even when its refresh is done`() = runTest {
        val finished = launch { }
        runCurrent()
        assertEquals("settle", awaitLaunch(SOURCE_CYCLE_REASON, finished, settle))
        assertEquals(settle, currentTime)
    }
}
