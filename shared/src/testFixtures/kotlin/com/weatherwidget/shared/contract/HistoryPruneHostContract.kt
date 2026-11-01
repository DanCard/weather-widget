package com.weatherwidget.shared.contract

import com.weatherwidget.data.local.HistoryPruneRun
import com.weatherwidget.data.local.HourlyActualsArchiveRun
import com.weatherwidget.data.model.HourlyActual
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What [HistoryPruneRun.Host] means on Android's Room Host and desktop's SQLite Host
 * (plans/261101-reliable-hourly-prune-scheduling.md). Rows sit in the merged `hourly_forecasts`.
 * Also covers its parent port, [HourlyActualsArchiveRun.Host].
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

    protected abstract suspend fun archived(): List<HourlyActual>

    /** One observation at the seeded site. */
    protected abstract suspend fun seedObservation(timestampMs: Long, fetchedAtMs: Long)

    private lateinit var host: HistoryPruneRun.Host
    private val now = System.currentTimeMillis()

    @Before
    fun openDatabase() {
        host = open()
    }

    @Test
    fun `the age delete goes by forecast hour, however old the fetch`() = runBlocking {
        val cutoff = now - 100 * DAY
        seed(
            listOf(
                Seed(hourMs = cutoff - DAY, labelMs = cutoff + DAY, fetchedAtMs = cutoff + DAY),
                Seed(hourMs = cutoff + DAY, labelMs = cutoff - DAY, fetchedAtMs = cutoff - DAY),
                Seed(hourMs = cutoff + 2 * DAY, labelMs = cutoff + DAY, fetchedAtMs = cutoff + DAY),
            ),
        )

        host.deleteOlderThan(cutoff)

        assertEquals(2, rowCount())
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
    fun `archived hours are frozen, and their age delete goes by hour`() = runBlocking {
        val hour = (now - 20 * DAY) / HOUR * HOUR
        val first = HourlyActual(hour, "NWS", 37.42, -122.08, temperature = 60f, precipAmountMm = 0f, archivedAt = now)
        val old = HourlyActual(hour - 100 * DAY, "NWS", 37.42, -122.08, temperature = 50f, archivedAt = now)

        host.insertHourlyActuals(listOf(first, old))
        host.insertHourlyActuals(listOf(first.copy(temperature = 99f)))
        assertEquals(hour, host.latestArchivedHour())
        host.deleteHourlyActualsOlderThan(hour - DAY)

        assertEquals(listOf(first), archived())
    }

    @Test
    fun `observations age by reading time, however new the fetch`() = runBlocking {
        val cutoff = now - 13 * DAY
        assertNull(host.oldestObservation())
        seedObservation(cutoff - DAY, fetchedAtMs = now)
        seedObservation(cutoff + DAY, fetchedAtMs = now)
        assertEquals(cutoff - DAY, host.oldestObservation())

        host.deleteObservationsOlderThan(cutoff)

        assertEquals(cutoff + DAY, host.oldestObservation())
    }

    @Test
    fun `the archive reads the site's observations and hourly rows in its window`() = runBlocking {
        val hour = (now - 12 * DAY) / HOUR * HOUR
        seedObservation(hour, fetchedAtMs = hour)
        seedObservation(hour + 2 * DAY, fetchedAtMs = hour)
        seed(listOf(Seed(hour, hour - HOUR, hour - HOUR)))

        assertEquals(mapOf((37.42 to -122.08) to 1), host.observationSites(hour, hour + HOUR))
        assertEquals(listOf(hour), host.observations(hour, hour + HOUR, 37.42, -122.08).map { it.timestamp })
        assertEquals(listOf(hour), host.hourlyForecasts(hour, hour + 1, 37.42, -122.08).map { it.dateTime })
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
