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
        val failArchive: Exception? = null,
    ) : HistoryPruneRun.Host {
        val calls = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var ageCutoff = 0L
        var observationCutoff = 0L
        var actualsCutoff = 0L
        var completedAt = 0L

        override suspend fun deleteOlderThan(cutoffMs: Long) {
            calls += "age"
            ageCutoff = cutoffMs
        }

        override suspend fun pruneSnapshots() {
            calls += "prune"
            failPrune?.let { throw it }
        }

        override suspend fun deleteObservationsOlderThan(cutoffMs: Long) {
            calls += "observations"
            observationCutoff = cutoffMs
        }

        override suspend fun deleteHourlyActualsOlderThan(cutoffMs: Long) {
            calls += "actuals"
            actualsCutoff = cutoffMs
        }

        override suspend fun latestArchivedHour(): Long? = null

        override suspend fun oldestObservation(): Long? {
            calls += "archive"
            failArchive?.let { throw it }
            return null
        }

        override suspend fun observationSites(startMs: Long, endMs: Long) = emptyMap<Pair<Double, Double>, Int>()

        override suspend fun observations(startMs: Long, endMs: Long, lat: Double, lon: Double) =
            emptyList<com.weatherwidget.data.model.ObservationReading>()

        override suspend fun hourlyForecasts(startMs: Long, endMs: Long, lat: Double, lon: Double) =
            emptyList<com.weatherwidget.data.model.HourlyForecast>()

        override fun personalStationWeight() = 1.0

        override suspend fun insertHourlyActuals(rows: List<com.weatherwidget.data.model.HourlyActual>) = Unit

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
    fun `age delete, prune, archive, raw deletes, vacuum, then the completed marker`() = runBlocking {
        val host = FakeHost(freeBytes = HistoryPruneSchedule.VACUUM_MIN_FREELIST_BYTES)
        HistoryPruneRun.run(host, now)

        assertEquals(listOf("age", "prune", "archive", "observations", "actuals", "vacuum", "recorded"), host.calls)
        assertEquals(RetentionPolicy.daysAgo(now, RetentionPolicy.HOURLY_FORECAST_DAYS), host.ageCutoff)
        assertEquals(RetentionPolicy.daysAgo(now, RetentionPolicy.HOURLY_ACTUALS_DAYS), host.actualsCutoff)
        val rawDays = (now - host.observationCutoff).toDouble() / (24 * 3_600_000L)
        assertTrue("raw kept $rawDays days", rawDays >= RetentionPolicy.HOURLY_ACTUALS_ARCHIVE_DAYS + RetentionPolicy.OBSERVATION_ARCHIVE_MARGIN_DAYS)
        assertEquals(now, host.completedAt)
        assertTrue(host.logs.last().startsWith("vacuum=done freedBytes=${host.freeBytes} "))
    }

    @Test
    fun `no vacuum below the threshold`() = runBlocking {
        val host = FakeHost(freeBytes = HistoryPruneSchedule.VACUUM_MIN_FREELIST_BYTES - 1)
        HistoryPruneRun.run(host, now)

        assertEquals(listOf("age", "prune", "archive", "observations", "actuals", "recorded"), host.calls)
    }

    @Test
    fun `a failed prune leaves the marker unset, so the prune is still due`() {
        val host = FakeHost(failPrune = IllegalStateException("disk"))

        assertThrows(IllegalStateException::class.java) { runBlocking { HistoryPruneRun.run(host, now) } }
        assertFalse("recorded" in host.calls)
    }

    @Test
    fun `a failed archive deletes no observations and stays due`() {
        val host = FakeHost(failArchive = IllegalStateException("disk"))

        assertThrows(IllegalStateException::class.java) { runBlocking { HistoryPruneRun.run(host, now) } }
        assertFalse("observations" in host.calls)
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
            host.logs.last(),
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
