package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.shared.observations.NwsGapFillRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * What [NwsGapFillRun.Host]'s storage reads, write and recompute mean, on Android's Room Host and
 * desktop's SQLite Host (plans/261025-shared-nws-gap-fill.md). The station list and per-station fetch
 * are network, not part of it. The runner's need re-check after each station relies on the last case:
 * stored NWS readings that reach a past day's afternoon must file that day's NWS daily_history row.
 */
abstract class NwsGapFillHostContract {
    /** Writes rows as given through the platform's database. */
    interface Seed {
        fun dailyHistory(row: DailyHistory)
        fun observation(row: ObservationReading)
    }

    /** A fresh, empty database; the Host sits at [LAT], [LON], wired exactly as production wires it. */
    protected abstract fun open(): Pair<NwsGapFillRun.Host, Seed>

    private lateinit var host: NwsGapFillRun.Host
    private lateinit var seed: Seed

    @Before
    fun openDatabase() {
        val (h, s) = open()
        host = h
        seed = s
    }

    @Test
    fun `dailyHistoryRows - every source in the box, both ends inclusive`() = runBlocking {
        seed.dailyHistory(dh(DAY0, "NWS"))
        seed.dailyHistory(dh(DAY0, "OPEN_METEO"))
        seed.dailyHistory(dh(DAY1, "NWS", lat = NEARBY_LAT))
        seed.dailyHistory(dh(DAY1, "NWS", lat = OUTSIDE_LAT))
        seed.dailyHistory(dh(DAY1 + DAY, "NWS"))

        assertEquals(
            setOf(DAY0 to "NWS", DAY0 to "OPEN_METEO", DAY1 to "NWS"),
            host.dailyHistoryRows(DAY0, DAY1, LAT, LON).map { it.dateMs to it.source }.toSet(),
        )
    }

    /**
     * Rows at the Host's own site only: other fragments in the box go through `ObservationSiteMerge`
     * on both platforms, whose rules are its own tests', not this port's.
     */
    @Test
    fun `observations - this site's NWS rows, start inclusive, end exclusive, outside the box excluded`() = runBlocking {
        seed.observation(obs("KNUQ", H0, "NWS"))
        seed.observation(obs("KSJC", H0 + HOUR, "NWS"))
        seed.observation(obs("KPAO", H0, "NWS", lat = OUTSIDE_LAT))
        seed.observation(obs("KNUQ", H0 + 2 * HOUR, "NWS"))

        // A Host may pre-filter to NWS; the runner filters either way, so only NWS rows are pinned.
        assertEquals(
            setOf(H0 to "KNUQ", H0 + HOUR to "KSJC"),
            host.observations(H0, H0 + 2 * HOUR, LAT, LON).filter { it.api == "NWS" }.map { it.timestamp to it.stationId }.toSet(),
        )
    }

    @Test
    fun `store - the stored rows read back with their api and station`() = runBlocking {
        host.store(listOf(obs("KNUQ", H0, "NWS"), obs("KSJC", H0 + HOUR, "NWS")), LAT, LON)

        assertEquals(
            setOf(NwsGapFillRun.ObsStamp(H0, "NWS", "KNUQ"), NwsGapFillRun.ObsStamp(H0 + HOUR, "NWS", "KSJC")),
            host.observations(H0, H0 + 2 * HOUR, LAT, LON).toSet(),
        )
    }

    @Test
    fun `recomputeExtremes - stored NWS readings file the past day's NWS daily_history row`() = runBlocking {
        val zone = ZoneId.systemDefault()
        val yesterday = LocalDate.now(zone).minusDays(1)
        val key = yesterday.toEpochDay() * DAY
        host.store(
            listOf(6, 10, 15, 18).map { hour ->
                obs("KNUQ", yesterday.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(), "NWS", temp = 50f + hour)
            },
            LAT,
            LON,
        )

        host.recomputeExtremes(LAT, LON, setOf(yesterday))

        assertTrue(host.dailyHistoryRows(key, key, LAT, LON).any { it.source == "NWS" })
    }

    companion object {
        const val LAT = 37.417
        const val LON = -122.089
        /** Inside the same-site box (0.05°), not the same quantized site. */
        const val NEARBY_LAT = 37.467
        /** Outside the box. */
        const val OUTSIDE_LAT = 37.567
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        val DAY0: Long = LocalDate.of(2026, 10, 20).toEpochDay() * DAY
        val DAY1: Long = DAY0 + DAY
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
    }
}
