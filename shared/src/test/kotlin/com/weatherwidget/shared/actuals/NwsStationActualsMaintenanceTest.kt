package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pins the policy [NwsStationActualsMaintenance] owns for both platforms: `Insufficient` dates fall
 * back to stored observations, `Unavailable` dates stay retryable, and the counters match what the
 * outcome log reports.
 */
@Category(ShortDuration::class)
class NwsStationActualsMaintenanceTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowDate: LocalDate = LocalDate.of(2026, 9, 9)
    private val nowMs: Long = nowDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val yesterday: LocalDate = nowDate.minusDays(1)
    private val yesterdayMs: Long = yesterday.toEpochDay() * 86_400_000L
    private val twoDaysAgo: LocalDate = nowDate.minusDays(2)
    private val twoDaysAgoMs: Long = twoDaysAgo.toEpochDay() * 86_400_000L

    private val extreme = StationDailyExtremes.StationDailyExtreme(
        stationId = "KSJC",
        stationName = "San Jose",
        distanceKm = 4f,
        high = 80f,
        low = 55f,
        readingCount = 24,
    )

    private fun resolve(
        dates: List<Long>,
        fetch: suspend (startIso: String) -> List<ObservationReading>?,
        stored: suspend (LocalDate) -> StationDailyExtremes.StationDailyExtreme? = { null },
    ): NwsStationActualsMaintenance.Outcome = runBlocking {
        NwsStationActualsMaintenance.resolve(
            missingDates = dates,
            stationIdsNearestFirst = listOf("KSJC"),
            userLat = 37.33,
            userLon = -121.88,
            personalStationWeight = 0.5,
            zone = zone,
            nowMs = nowMs,
            fetchStationDay = { _, startIso, _ -> fetch(startIso) },
            stationExtremeFromStoredObservations = stored,
        )
    }

    @Test
    fun `insufficient date falls back to stored observations`() {
        var storedCalls = 0
        val outcome = resolve(
            dates = listOf(yesterdayMs),
            fetch = { emptyList() },
            stored = { storedCalls++; extreme },
        )
        assertEquals(1, storedCalls)
        assertEquals(mapOf(yesterdayMs to extreme), outcome.cached)
        assertTrue(outcome.pulled.isEmpty())
        assertEquals(0, outcome.insufficientUnresolved)
        assertEquals(0, outcome.unavailable)
    }

    @Test
    fun `unavailable date stays retryable and never caches`() {
        var storedCalls = 0
        val outcome = resolve(
            dates = listOf(yesterdayMs),
            fetch = { null },
            stored = { storedCalls++; extreme },
        )
        assertEquals(0, storedCalls)
        assertTrue(outcome.cached.isEmpty())
        assertTrue(outcome.pulled.isEmpty())
        assertEquals(1, outcome.unavailable)
    }

    @Test
    fun `insufficient date without a stored extreme counts as unresolved`() {
        val outcome = resolve(
            dates = listOf(yesterdayMs),
            fetch = { emptyList() },
            stored = { null },
        )
        assertTrue(outcome.cached.isEmpty())
        assertEquals(1, outcome.insufficientUnresolved)
    }

    @Test
    fun `insufficient date caches while unavailable date is retried`() {
        val outcome = resolve(
            dates = listOf(twoDaysAgoMs, yesterdayMs),
            // Two days ago answered with nothing (Insufficient -> cached); yesterday failed
            // (Unavailable -> retried, never cached).
            fetch = { startIso -> if (startIso.startsWith(twoDaysAgo.toString())) emptyList() else null },
            stored = { date -> if (date == twoDaysAgo) extreme else null },
        )
        assertEquals(1, outcome.unavailable)
        assertEquals(mapOf(twoDaysAgoMs to extreme), outcome.cached)
        assertEquals(0, outcome.insufficientUnresolved)
    }
}
