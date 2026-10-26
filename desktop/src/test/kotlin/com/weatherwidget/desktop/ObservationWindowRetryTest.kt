package com.weatherwidget.desktop

import com.weatherwidget.desktop.DesktopWeatherRepository.Companion.OBS_WINDOW_FAILED
import com.weatherwidget.desktop.DesktopWeatherRepository.Companion.OBS_WINDOW_UNREACHABLE
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** [ObservationWindowRetry] on the shared 10 s → 1 m → 4 m schedule, with the waits recorded, not slept. */
@Category(ShortDuration::class)
class ObservationWindowRetryTest {
    private val waits = mutableListOf<Long>()
    private val logs = mutableListOf<String>()
    private var stored = 0

    private fun retry(first: Int, outcomes: List<Int>, stillCurrent: () -> Boolean = { true }): Pair<Int, Int> {
        val queue = ArrayDeque(outcomes)
        var calls = 0
        val last = runBlocking {
            ObservationWindowRetry.run(
                firstOutcome = first,
                refresh = { calls++; queue.removeFirst() },
                stillCurrent = stillCurrent,
                onStored = { stored++ },
                log = { tag, message, _ -> logs += "$tag $message" },
                wait = { waits += it },
            )
        }
        return last to calls
    }

    @Test
    fun `an answered fill is never retried`() {
        assertEquals(0 to 0, retry(first = 0, outcomes = emptyList()))
        assertEquals(12 to 0, retry(first = 12, outcomes = emptyList()))
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `a failure that is not unreachable is not retried`() {
        assertEquals(OBS_WINDOW_FAILED to 0, retry(first = OBS_WINDOW_FAILED, outcomes = emptyList()))
    }

    @Test
    fun `unreachable retries on the shared schedule, then gives up`() {
        val (last, calls) = retry(OBS_WINDOW_UNREACHABLE, List(3) { OBS_WINDOW_UNREACHABLE })

        assertEquals(OBS_WINDOW_UNREACHABLE, last)
        assertEquals(3, calls)
        assertEquals(listOf(10_000L, 60_000L, 240_000L), waits)
        assertEquals(3, logs.count { it.startsWith("OBS_HOURLY_BACKFILL_RETRY reason=obs_window attempt=") && "delayMs" in it })
    }

    @Test
    fun `a retry that stores rows stops and publishes`() {
        val (last, calls) = retry(OBS_WINDOW_UNREACHABLE, listOf(OBS_WINDOW_UNREACHABLE, 40))

        assertEquals(40, last)
        assertEquals(2, calls)
        assertEquals(listOf(10_000L, 60_000L), waits)
        assertEquals(1, stored)
    }

    @Test
    fun `a superseded repository or source stops the retry before fetching`() {
        val (last, calls) = retry(OBS_WINDOW_UNREACHABLE, listOf(40), stillCurrent = { false })

        assertEquals(OBS_WINDOW_UNREACHABLE, last)
        assertEquals(0, calls)
        assertEquals(1, logs.count { "outcome=superseded" in it })
    }
}
