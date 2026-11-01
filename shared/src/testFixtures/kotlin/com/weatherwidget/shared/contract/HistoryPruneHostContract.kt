package com.weatherwidget.shared.contract

import com.weatherwidget.data.local.HistoryPruneRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What [HistoryPruneRun.Host] means on Android's Room Host and desktop's SQLite Host
 * (plans/261101-reliable-hourly-prune-scheduling.md). Rows sit in the merged `hourly_forecasts`.
 */
abstract class HistoryPruneHostContract {
    data class Seed(val hourMs: Long, val labelMs: Long, val fetchedAtMs: Long)

    /** A fresh, empty database each call. */
    protected abstract fun open(): HistoryPruneRun.Host

    protected abstract suspend fun seed(rows: List<Seed>)

    protected abstract suspend fun rowCount(): Int

    /** What [HistoryPruneRun.Host.recordCompleted] left; 0 when nothing did. */
    protected abstract suspend fun lastCompleted(): Long

    protected abstract suspend fun logged(): List<String>

    private lateinit var host: HistoryPruneRun.Host
    private val now = System.currentTimeMillis()

    @Before
    fun openDatabase() {
        host = open()
    }

    @Test
    fun `the age delete takes rows old by hour or by fetch, and leaves the rest`() = runBlocking {
        val cutoff = now - 100 * DAY
        seed(
            listOf(
                Seed(hourMs = cutoff - DAY, labelMs = cutoff + DAY, fetchedAtMs = cutoff + DAY),
                Seed(hourMs = cutoff + DAY, labelMs = cutoff - DAY, fetchedAtMs = cutoff - DAY),
                Seed(hourMs = cutoff + DAY, labelMs = cutoff + DAY, fetchedAtMs = cutoff + DAY),
            ),
        )

        host.deleteOlderThan(cutoff)

        assertEquals(1, rowCount())
    }

    @Test
    fun `the snapshot prune reduces a deep stack, and a second pass finds nothing`() = runBlocking {
        val hour = (now - 10 * DAY) / HOUR * HOUR
        seed((1..30).map { k -> Seed(hour, hour - k * HOUR, hour - k * HOUR + 60_000L) })

        host.pruneSnapshots()
        val left = rowCount()
        host.pruneSnapshots()

        assertTrue("30 copies leave only what readers need: $left", left in 1..3)
        assertEquals(left, rowCount())
    }

    @Test
    fun `freed pages are reported, and a vacuum returns them`() = runBlocking {
        val hour = now - 10 * DAY
        seed((1..3_000).map { k -> Seed(hour - k * HOUR, hour, hour) })
        host.deleteOlderThan(hour + DAY)
        assertTrue("deleting leaves free pages", host.freelistBytes() > 0L)

        host.vacuum()

        assertEquals(0L, host.freelistBytes())
    }

    @Test
    fun `the completed marker reads back`() = runBlocking {
        assertEquals(0L, lastCompleted())

        host.recordCompleted(now)
        host.recordCompleted(now + 1)

        assertEquals(now + 1, lastCompleted())
    }

    @Test
    fun `log lines land under the prune tag`() = runBlocking {
        host.log("vacuum=done")

        assertTrue(logged().any { it == "vacuum=done" })
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
