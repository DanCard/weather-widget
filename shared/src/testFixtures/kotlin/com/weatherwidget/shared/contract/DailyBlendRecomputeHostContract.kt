package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.shared.actuals.DailyBlendRecomputeRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * What [DailyBlendRecomputeRun.Host]'s reads and writes mean, on Android's Room Host and desktop's
 * SQLite Host (plans/261025-shared-daily-blend-recompute.md). The signature is pinned to its exact
 * string: the unchanged-day skip compares it across recomputes, so both SQL copies must agree.
 */
abstract class DailyBlendRecomputeHostContract {
    /** Writes rows as given through the platform's database. */
    interface Seed {
        fun dailyHistory(row: DailyHistory)
        fun observation(row: ObservationReading)
        fun hourly(row: HourlyForecast)
    }

    /** A fresh, empty database; the Host sits at [LAT], [LON], wired exactly as production wires it. */
    protected abstract fun open(): Pair<DailyBlendRecomputeRun.Host, Seed>

    private lateinit var host: DailyBlendRecomputeRun.Host
    private lateinit var seed: Seed

    @Before
    fun openDatabase() {
        val (h, s) = open()
        host = h
        seed = s
    }

    /**
     * This site's rows only: other fragments in the box go through `ObservationSiteMerge` on both
     * platforms, whose rules are its own tests', not this port's.
     */
    @Test
    fun `observations - every api at the site, start inclusive, end exclusive, outside the box excluded`() = runBlocking {
        seed.observation(obs("KNUQ", H0, "NWS"))
        seed.observation(obs("OM", H0 + HOUR, "OPEN_METEO"))
        seed.observation(obs("KSJC", H0, "NWS", lat = OUTSIDE_LAT))
        seed.observation(obs("KNUQ", H0 + 2 * HOUR, "NWS"))

        assertEquals(
            setOf(Triple(H0, "KNUQ", "NWS"), Triple(H0 + HOUR, "OM", "OPEN_METEO")),
            host.observations(H0, H0 + 2 * HOUR).map { Triple(it.timestamp, it.stationId, it.api) }.toSet(),
        )
    }

    @Test
    fun `hourlyAllSources - every source at the site, both ends inclusive`() = runBlocking {
        seed.hourly(hourly(H0, "NWS"))
        seed.hourly(hourly(H0 + HOUR, "OPEN_METEO"))
        seed.hourly(hourly(H0 + 2 * HOUR, "GOOGLE_WEATHER"))
        seed.hourly(hourly(H0 + 3 * HOUR, "NWS"))

        assertEquals(
            setOf(H0 to "NWS", H0 + HOUR to "OPEN_METEO", H0 + 2 * HOUR to "GOOGLE_WEATHER"),
            host.hourlyAllSources(H0, H0 + 2 * HOUR).map { it.dateTime to it.source }.toSet(),
        )
    }

    @Test
    fun `observationSignature - the same string on both, end exclusive, a revised value changes it`() = runBlocking {
        seed.observation(obs("KNUQ", H0, "NWS", temp = 60f))
        seed.observation(obs("KNUQ", H0 + HOUR, "NWS", temp = 61.5f))
        seed.observation(obs("KNUQ", H0 + 2 * HOUR, "NWS", temp = 99f))

        val signature = host.observationSignature(H0, H0 + 2 * HOUR)
        assertEquals("2|${H0 + HOUR}|12150|0|0|0|0", signature)

        seed.observation(obs("KNUQ", H0 + HOUR, "NWS", temp = 62f))
        assertNotEquals(signature, host.observationSignature(H0, H0 + 2 * HOUR))
    }

    @Test
    fun `dailyHistoryAt - every source in the box at exactly that key`() = runBlocking {
        seed.dailyHistory(dh(DAY0, "NWS"))
        seed.dailyHistory(dh(DAY0, "OPEN_METEO"))
        seed.dailyHistory(dh(DAY0, "NWS", lat = OUTSIDE_LAT))
        seed.dailyHistory(dh(DAY0 + DAY, "NWS"))

        assertEquals(setOf("NWS", "OPEN_METEO"), host.dailyHistoryAt(DAY0).map { it.source }.toSet())
    }

    /** Phase 0's write boundary: a raw fix lands on the rounded site on both platforms. */
    @Test
    fun `insert - files the row under the rounded site`() = runBlocking {
        host.insert(listOf(dh(DAY0, "NWS", lat = RAW_LAT)))

        assertEquals(listOf(LAT), host.dailyHistoryAt(DAY0).map { it.locationLat })
    }

    @Test
    fun `updateBlendIfUnchanged - blend columns only, and only while updatedAt matches`() = runBlocking {
        seed.dailyHistory(dh(DAY0, "NWS").copy(apiHighTemp = 84f, apiStationId = "KNUQ", forecastHighTemp = 86f))
        val stored = host.dailyHistoryAt(DAY0).single()
        val merged = stored.copy(computedHighTemp = 75f, computedLowTemp = 48f, lastWriter = "blend_recompute")

        assertEquals(0, host.updateBlendIfUnchanged(merged, newUpdatedAt = DAY0 + 5, expectedUpdatedAt = stored.updatedAt + 1))
        assertEquals(1, host.updateBlendIfUnchanged(merged.copy(apiHighTemp = null), newUpdatedAt = DAY0 + 5, expectedUpdatedAt = stored.updatedAt))

        val after = host.dailyHistoryAt(DAY0).single()
        assertEquals(75f, after.computedHighTemp)
        assertEquals(DAY0 + 5, after.updatedAt)
        assertEquals("the api actual is not the blend's to write", 84f, after.apiHighTemp)
        assertEquals(86f, after.forecastHighTemp)
        assertEquals("KNUQ", after.apiStationId)
        assertNull(after.priorForecastHighTemp)
    }

    companion object {
        const val LAT = 37.417
        const val LON = -122.089
        const val RAW_LAT = 37.4166014
        /** Outside the box. */
        const val OUTSIDE_LAT = 37.567
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        val DAY0: Long = LocalDate.of(2026, 10, 20).toEpochDay() * DAY
        val H0: Long = DAY0 + 18 * HOUR

        fun dh(date: Long, source: String, lat: Double = LAT) = DailyHistory(
            date = date, source = source, locationLat = lat, locationLon = LON,
            computedHighTemp = 70f, computedLowTemp = 50f, condition = "Sunny", updatedAt = DAY0,
        )

        fun obs(station: String, timestamp: Long, api: String, lat: Double = LAT, temp: Float = 60f) = ObservationReading(
            stationId = station, stationName = station, timestamp = timestamp, temperature = temp,
            condition = "Clear", locationLat = lat, locationLon = LON, distanceKm = 3f,
            stationType = StationType.OFFICIAL, fetchedAt = timestamp, api = api,
        )

        fun hourly(dateTime: Long, source: String) = HourlyForecast(
            dateTime = dateTime, temperature = 60f, condition = "Clear", source = source,
            fetchedAt = DAY0, locationLat = LAT, locationLon = LON,
        )
    }
}
