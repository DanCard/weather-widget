package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

@Category(ShortDuration::class)
class DailyBlendRecomputeRunTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 25)
    private val nowMs = today.atTime(19, 56).atZone(zone).toInstant().toEpochMilli()

    private class FakeHost : DailyBlendRecomputeRun.Host {
        override val lat = 37.417
        override val lon = -122.089
        val obs = mutableListOf<ObservationReading>()
        val rows = mutableListOf<DailyHistory>()
        val logs = mutableListOf<String>()
        var signatureReads = 0
        var observationReads = 0
        override val signatures = InMemoryReducedSignatureStore()

        override fun personalStationWeight() = 1.0
        override suspend fun observations(startMs: Long, endMs: Long): List<ObservationReading> {
            observationReads++
            return obs.filter { it.timestamp in startMs until endMs }
        }
        override suspend fun hourlyAllSources(startMs: Long, endMs: Long) = emptyList<HourlyForecast>()
        override suspend fun observationSignature(startMs: Long, endMs: Long): String {
            signatureReads++
            val day = obs.filter { it.timestamp in startMs until endMs }
            return "${day.size}|${day.sumOf { it.temperature.toDouble() }}"
        }
        override suspend fun dailyHistoryAt(dateKey: Long) = rows.filter { it.date == dateKey }
        override suspend fun insert(rows: List<DailyHistory>) {
            rows.forEach { new -> this.rows.removeAll { it.date == new.date && it.source == new.source }; this.rows += new }
        }
        override suspend fun updateBlendIfUnchanged(merged: DailyHistory, newUpdatedAt: Long, expectedUpdatedAt: Long): Int {
            val i = rows.indexOfFirst { it.date == merged.date && it.source == merged.source && it.updatedAt == expectedUpdatedAt }
            if (i < 0) return 0
            rows[i] = rows[i].copy(
                computedHighTemp = merged.computedHighTemp, computedLowTemp = merged.computedLowTemp,
                computedHighAt = merged.computedHighAt, computedLowAt = merged.computedLowAt,
                condition = merged.condition, lastWriter = merged.lastWriter, updatedAt = newUpdatedAt,
            )
            return 1
        }
        override suspend fun log(tag: String, message: String, level: String) { logs += "$tag $message" }
        override fun elapsedMs() = 0L
    }

    private lateinit var host: FakeHost

    @Before
    fun setup() {
        ActualsProviderResolver.resetPreferenceSource()
        ActualsProviderResolver.resetLocationSource()
        host = FakeHost()
    }

    private fun key(date: LocalDate) = date.toEpochDay() * 86_400_000L

    private fun hourlyReadings(date: LocalDate, temp: (Int) -> Float) = (0..23).map { h ->
        ObservationReading(
            stationId = "KNUQ", stationName = "Moffett", timestamp = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli(),
            temperature = temp(h), condition = "Clear", locationLat = host.lat, locationLon = host.lon, distanceKm = 3f,
            stationType = StationType.OFFICIAL, api = "NWS",
        )
    }

    private fun row(date: LocalDate) = DailyHistory(
        date = key(date), source = "NWS", locationLat = host.lat, locationLon = host.lon,
        computedHighTemp = 70f, computedLowTemp = 50f, condition = "Clear", updatedAt = 1L,
        forecastHighTemp = 86f, priorForecastHighTemp = 85f, noonCloudPercent = 20,
        apiHighTemp = 84f, apiLowTemp = 55.4f, apiStationId = "KNUQ",
    )

    /** The desktop bug's shape: the existing row is found by its key and only its blend columns change. */
    @Test
    fun `an existing day keeps its api actual and frozen forecast`() = runBlocking {
        val day = today.minusDays(8)
        host.obs += hourlyReadings(day) { h -> if (h == 6) 48f else if (h == 15) 80f else 60f }
        host.rows += row(day)

        DailyBlendRecomputeRun.day(host, day, nowMs, zone)

        val stored = host.rows.single { it.date == key(day) && it.source == "NWS" }
        assertEquals(84f, stored.apiHighTemp)
        assertEquals("KNUQ", stored.apiStationId)
        assertEquals(86f, stored.forecastHighTemp)
        assertEquals(85f, stored.priorForecastHighTemp)
        assertEquals(20, stored.noonCloudPercent)
    }

    @Test
    fun `only the target day is written, though its context reaches the neighbours`() = runBlocking {
        val day = today.minusDays(3)
        host.obs += hourlyReadings(day.minusDays(1)) { 60f } + hourlyReadings(day) { 61f } + hourlyReadings(day.plusDays(1)) { 62f }

        DailyBlendRecomputeRun.day(host, day, nowMs, zone)

        assertEquals(setOf(key(day)), host.rows.map { it.date }.toSet())
    }

    @Test
    fun `an unchanged day is skipped, a forced one is not`() = runBlocking {
        val day = today.minusDays(2)
        host.obs += hourlyReadings(day) { 60f }
        DailyBlendRecomputeRun.day(host, day, nowMs, zone)
        val readsAfterFirst = host.observationReads

        DailyBlendRecomputeRun.day(host, day, nowMs, zone)
        assertEquals("skipped before reading observations", readsAfterFirst, host.observationReads)
        assertTrue(host.logs.any { it.startsWith("DAILY_RECOMPUTE_SKIP date=$day") })

        DailyBlendRecomputeRun.day(host, day, nowMs, zone, force = true)
        assertEquals(readsAfterFirst + 1, host.observationReads)
    }

    @Test
    fun `range starts at the cutoff`() = runBlocking {
        val tooOld = today.minusDays(DailyBlendRecomputeRun.MAX_DAYS_BACK + 1)
        host.obs += hourlyReadings(tooOld) { 60f } + hourlyReadings(today.minusDays(1)) { 61f }

        DailyBlendRecomputeRun.range(host, tooOld, today, nowMs, zone)

        assertEquals(setOf(key(today.minusDays(1))), host.rows.map { it.date }.toSet())
    }

    @Test
    fun `a day with no observations writes nothing`() = runBlocking {
        assertEquals(0, DailyBlendRecomputeRun.day(host, today.minusDays(1), nowMs, zone))
        assertTrue(host.rows.isEmpty())
    }
}
