package com.weatherwidget.data.local

import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HistoryPruneRunTest {
    private val now = 1_800_000_000_000L

    private class FakeHost(
        val freeBytes: Long = 0L,
        val failPrune: Exception? = null,
        val failVacuum: Exception? = null,
    ) : HistoryPruneRun.Host {
        val calls = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var ageCutoff = 0L
        var completedAt = 0L

        override suspend fun deleteOlderThan(cutoffMs: Long) {
            calls += "age"
            ageCutoff = cutoffMs
        }

        override suspend fun pruneSnapshots() {
            calls += "prune"
            failPrune?.let { throw it }
        }

        override suspend fun freelistBytes(): Long = freeBytes

        override suspend fun vacuum() {
            calls += "vacuum"
            failVacuum?.let { throw it }
        }

        override suspend fun log(message: String) {
            logs += message
        }

        override suspend fun recordCompleted(startedAtMs: Long) {
            calls += "recorded"
            completedAt = startedAtMs
        }
    }

    @Test
    fun `age delete, prune, vacuum, then the completed marker`() = runBlocking {
        val host = FakeHost(freeBytes = HistoryPruneSchedule.VACUUM_MIN_FREELIST_BYTES)
        HistoryPruneRun.run(host, now)

        assertEquals(listOf("age", "prune", "vacuum", "recorded"), host.calls)
        assertEquals(RetentionPolicy.daysAgo(now, RetentionPolicy.HOURLY_HISTORY_SETTLED_DAYS), host.ageCutoff)
        assertEquals(now, host.completedAt)
        assertTrue(host.logs.single().startsWith("vacuum=done freedBytes=${host.freeBytes} "))
    }

    @Test
    fun `no vacuum below the threshold`() = runBlocking {
        val host = FakeHost(freeBytes = HistoryPruneSchedule.VACUUM_MIN_FREELIST_BYTES - 1)
        HistoryPruneRun.run(host, now)

        assertEquals(listOf("age", "prune", "recorded"), host.calls)
    }

    @Test
    fun `a failed prune leaves the marker unset, so the prune is still due`() {
        val host = FakeHost(failPrune = IllegalStateException("disk"))

        assertThrows(IllegalStateException::class.java) { runBlocking { HistoryPruneRun.run(host, now) } }
        assertFalse("recorded" in host.calls)
    }

    @Test
    fun `a failed vacuum is logged and the completed prune still counts`() = runBlocking {
        val host = FakeHost(
            freeBytes = HistoryPruneSchedule.VACUUM_MIN_FREELIST_BYTES,
            failVacuum = IllegalStateException("busy"),
        )
        HistoryPruneRun.run(host, now)

        assertEquals(now, host.completedAt)
        assertEquals(
            "vacuum=skipped reason=IllegalStateException freeBytes=${host.freeBytes}",
            host.logs.single(),
        )
    }

    @Test
    fun `cancellation during vacuum is never swallowed`() {
        val host = FakeHost(
            freeBytes = HistoryPruneSchedule.VACUUM_MIN_FREELIST_BYTES,
            failVacuum = CancellationException("stopped"),
        )

        assertThrows(CancellationException::class.java) { runBlocking { HistoryPruneRun.run(host, now) } }
        assertFalse("recorded" in host.calls)
    }
}
