package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopSourceRetryState
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.util.SourceRetry
import com.weatherwidget.test.category.ShortDuration
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `a retry armed before source change fires at its due time while settle still finishes at 5 min`() = runTest {
        val grace = 5_000L
        var retryAt: Long? = 60_000L
        val firedAt = mutableListOf<Long>()
        val wake = Channel<String>(Channel.CONFLATED)

        val wait = async {
            awaitLaunch(
                SOURCE_CYCLE_REASON,
                null,
                settle,
                wake = wake,
                nextRetryAtMs = { retryAt },
                onRetryDue = {
                    firedAt += currentTime
                    retryAt = null
                },
                graceMs = grace,
                nowMs = { currentTime },
            )
        }

        advanceTimeBy(60_000L - grace)
        runCurrent()
        assertEquals(listOf(60_000L - grace), firedAt)
        assertFalse(wait.isCompleted)

        assertEquals("settle", wait.await())
        assertEquals(settle, currentTime)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a retry armed during settle via wake channel fires on time and consumes the wake`() = runTest {
        val grace = 5_000L
        var retryAt: Long? = null
        val firedAt = mutableListOf<Long>()
        val wake = Channel<String>(Channel.CONFLATED)

        val wait = async {
            awaitLaunch(
                SOURCE_CYCLE_REASON,
                null,
                settle,
                wake = wake,
                nextRetryAtMs = { retryAt },
                onRetryDue = {
                    firedAt += currentTime
                    retryAt = null
                },
                graceMs = grace,
                nowMs = { currentTime },
            )
        }

        advanceTimeBy(90_000L)
        runCurrent()
        retryAt = 210_000L
        wake.trySend("source_retry_armed")
        runCurrent()
        assertTrue(wake.isEmpty)
        assertTrue(firedAt.isEmpty())

        advanceTimeBy(120_000L - grace)
        runCurrent()
        assertEquals(listOf(210_000L - grace), firedAt)
        assertFalse(wait.isCompleted)

        assertEquals("settle", wait.await())
        assertEquals(settle, currentTime)
    }

    @Test
    fun `non-retry wake during settle neither ends settle nor triggers onRetryDue`() = runTest {
        var called = 0
        val wake = Channel<String>(Channel.CONFLATED)
        val wait = async {
            awaitLaunch(
                SOURCE_CYCLE_REASON,
                null,
                settle,
                wake = wake,
                nextRetryAtMs = { null },
                onRetryDue = { called++ },
                nowMs = { currentTime },
            )
        }

        advanceTimeBy(30_000L)
        runCurrent()
        wake.trySend("screen")
        runCurrent()
        assertEquals(0, called)
        assertFalse(wait.isCompleted)

        assertEquals("settle", wait.await())
        assertEquals(settle, currentTime)
        assertEquals(0, called)
    }

    @Test
    fun `launchRetry survives source_change restart and is cancelled by location_change or cancel`() = runTest {
        val jobs = LaunchRefreshJobs(this)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val retryJob = jobs.launchRetry {
            started.complete(Unit)
            gate.await()
        }
        started.await()

        jobs.start(SOURCE_CYCLE_REASON) { }
        runCurrent()
        assertTrue(retryJob.isActive)

        jobs.start("location_change") { }
        runCurrent()
        assertTrue(retryJob.isCancelled)

        val secondRetry = jobs.launchRetry { gate.await() }
        runCurrent()
        assertTrue(secondRetry.isActive)
        jobs.cancel()
        runCurrent()
        assertTrue(secondRetry.isCancelled)
    }

    @Test
    fun `fetchOnce skips retry when launch refresh is already fetching the same key`() = runTest {
        val jobs = LaunchRefreshJobs(this)
        val inFlight = CompletableDeferred<Unit>()
        val key = LaunchRefreshJobs.key("OPEN_METEO", 37.417, -122.089)

        jobs.start(SOURCE_CYCLE_REASON) {
            jobs.fetchOnce(key) {
                inFlight.await()
                "launch"
            }
        }
        runCurrent()

        val retryResult = jobs.fetchOnce(key) { "retry" }
        assertNull(retryResult)

        inFlight.complete(Unit)
        runCurrent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `settle-time retry with SQLite retry state consumes row and dedups against in-flight launch`() = runTest {
        val dir = Files.createTempDirectory("forecast-loop-retry-integration")
        val db = DesktopWeatherDatabase(dir.resolve("weather.db")).apply { initialize() }
        try {
            val dao = DesktopWeatherDao(db)
            val retryState = DesktopSourceRetryState(dao)
            val sourceId = "OPEN_METEO"
            val key = LaunchRefreshJobs.key(sourceId, 37.417, -122.089)
            val grace = 5_000L

            // 1. Arm a retry in real SQLite due at +60 s.
            retryState.arm(sourceId, SourceRetry.Pending(atMs = 60_000L, delayMs = 60_000L, attempt = 1))
            assertEquals(60_000L, retryState.retryAtMs(sourceId, nowMs = 0L))

            val jobs = LaunchRefreshJobs(this)
            val launchInFlight = CompletableDeferred<Unit>()
            var fetchCount = 0

            // Simulate an in-flight launch refresh already holding fetchOnce(key).
            jobs.start(SOURCE_CYCLE_REASON) {
                jobs.fetchOnce(key) {
                    fetchCount++
                    launchInFlight.await()
                }
            }
            runCurrent()
            assertEquals(1, fetchCount)

            // 2. Run awaitLaunch settle; when the retry comes due at 55 s, it consumes the SQLite row
            //    synchronously and skips the duplicate fetch via fetchOnce.
            var onRetryDueCalls = 0
            val wait = async {
                awaitLaunch(
                    SOURCE_CYCLE_REASON,
                    jobs.current(),
                    settle,
                    nextRetryAtMs = { retryState.retryAtMs(sourceId, currentTime) },
                    onRetryDue = {
                        onRetryDueCalls++
                        if (retryState.isDue(sourceId, currentTime, grace)) {
                            retryState.consume(sourceId)
                            jobs.launchRetry {
                                jobs.fetchOnce(key) { fetchCount++ }
                            }
                        }
                    },
                    graceMs = grace,
                    nowMs = { currentTime },
                )
            }

            advanceTimeBy(60_000L - grace)
            runCurrent()

            assertEquals(1, onRetryDueCalls)
            assertFalse("consumed row must not be due again", retryState.isDue(sourceId, currentTime, grace))
            assertEquals("in-flight launch dedups retry fetch", 1, fetchCount)

            launchInFlight.complete(Unit)
            assertEquals("settle", wait.await())
            assertEquals(settle, currentTime)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * plans/261117-source-retry-due-grace.md: with production defaults a 1-min retry armed during a
     * source-change settle fires ~1 min later, not at once (2026-11-17 00:43:57 fired 59 ms after arming).
     */
    @Test
    fun `a 1-min retry fires near its time with the default grace`() = runTest {
        var retryAt: Long? = 60_000L
        val firedAt = mutableListOf<Long>()
        val wait = async {
            awaitLaunch(
                SOURCE_CYCLE_REASON,
                null,
                settle,
                wake = Channel(Channel.CONFLATED),
                nextRetryAtMs = { retryAt },
                onRetryDue = {
                    firedAt += currentTime
                    retryAt = null
                },
                nowMs = { currentTime },
            )
        }
        runCurrent()
        println("firedAt after arming=$firedAt")
        assertTrue("must not fire at arming", firedAt.isEmpty())

        advanceTimeBy(60_000L)
        runCurrent()
        println("firedAt=$firedAt")
        assertEquals(listOf(60_000L - SourceRetry.DUE_GRACE_MS), firedAt)
        assertEquals("settle", wait.await())
    }

    /**
     * The real chain on SQLite: a TIMEOUT through [SourceRetry.onFailure] arms rows via
     * [DesktopSourceRetryHost], the settle reads them through [DesktopSourceRetryState] with its default
     * grace, and each retry fails again. Attempts 1, 2 and 3 must fire ~20 s, ~1 min and ~2 min after arming.
     */
    @Test
    fun `retry chain on SQLite keeps its 20 s, 1 min and 2 min spacing during settle`() = runTest {
        val dir = Files.createTempDirectory("forecast-loop-retry-spacing")
        val db = DesktopWeatherDatabase(dir.resolve("weather.db")).apply { initialize() }
        try {
            val dao = DesktopWeatherDao(db)
            val retryState = DesktopSourceRetryState(dao)
            val wake = Channel<String>(Channel.CONFLATED)
            val host = DesktopSourceRetryHost(dao, { true }, { true }) { wake.trySend("source_retry_armed") }
            val sourceId = "OPEN_METEO"
            val firedAt = mutableListOf<Long>()

            SourceRetry.onFailure(host, sourceId, "TIMEOUT", "launch:source_change", currentTime)
            val wait = async {
                awaitLaunch(
                    SOURCE_CYCLE_REASON,
                    null,
                    settle,
                    wake = wake,
                    nextRetryAtMs = { retryState.retryAtMs(sourceId, currentTime) },
                    onRetryDue = {
                        if (retryState.isDue(sourceId, currentTime)) {
                            retryState.consume(sourceId)
                            firedAt += currentTime
                            // The retry fetch times out again and arms the next attempt.
                            SourceRetry.onFailure(host, sourceId, "TIMEOUT", SourceRetry.REASON, currentTime)
                        }
                    },
                    nowMs = { currentTime },
                )
            }
            advanceTimeBy(settle - 1)
            runCurrent()
            println("firedAt=$firedAt pending=${retryState.pending(sourceId)}")
            assertEquals(3, firedAt.size)
            assertTrue("attempt 1 at ${firedAt[0]}", firedAt[0] >= 18_000L)
            assertTrue("attempt 2 at ${firedAt[1]}", firedAt[1] - firedAt[0] >= 58_000L)
            assertTrue("attempt 3 at ${firedAt[2]}", firedAt[2] - firedAt[1] >= 118_000L)
            assertEquals(4, retryState.pending(sourceId)?.attempt)
            assertEquals("settle", wait.await())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
