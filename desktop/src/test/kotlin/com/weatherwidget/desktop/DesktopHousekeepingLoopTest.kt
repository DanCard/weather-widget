package com.weatherwidget.desktop

import com.weatherwidget.data.local.HistoryPruneSchedule
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@Category(ShortDuration::class)
class DesktopHousekeepingLoopTest {
    @Test
    fun `checks shortly after start, then on every interval`() = runTest {
        var checks = 0
        val job = launch { DesktopHousekeepingLoop.run(prune = { checks++ }, clock = { 0L }) }

        advanceTimeBy(HistoryPruneSchedule.FIRST_CHECK_DELAY_MS - 1)
        runCurrent()
        assertEquals(0, checks)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, checks)

        advanceTimeBy(HistoryPruneSchedule.CHECK_INTERVAL_MS * 2)
        runCurrent()
        assertEquals(3, checks)
        job.cancel()
    }

    @Test
    fun `a failed check does not end the loop`() = runTest {
        var checks = 0
        val job = launch {
            DesktopHousekeepingLoop.run(prune = { checks++; error("db busy") }, clock = { 0L })
        }

        advanceTimeBy(HistoryPruneSchedule.FIRST_CHECK_DELAY_MS + HistoryPruneSchedule.CHECK_INTERVAL_MS)
        runCurrent()
        assertEquals(2, checks)
        job.cancel()
    }

    /** Guards the stall: the prune must not go back behind a refresh or the away rule. */
    @Test
    fun `the prune is asked for by the loop, and never from a refresh or behind presence`() {
        val main = listOf("src/main/kotlin", "desktop/src/main/kotlin").map(::File).first { it.isDirectory }
        val dir = File(main, "com/weatherwidget/desktop")
        val repository = File(dir, "DesktopWeatherRepository.kt").readText()
        assertEquals(
            "only the declaration",
            1,
            Regex("""pruneHistorySnapshotsIfDue\(""").findAll(repository).count(),
        )
        val loop = File(dir, "DesktopHousekeepingLoop.kt").readText()
        assertFalse(loop.contains("isAway"))
    }
}
