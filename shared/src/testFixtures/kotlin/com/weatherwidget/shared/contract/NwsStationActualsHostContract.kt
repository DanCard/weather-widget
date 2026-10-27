package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.actuals.NwsStationActualsRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What [NwsStationActualsRun.Host]'s database reads and write mean, on Android's Room Host and
 * desktop's SQLite Host (plans/261024-host-contract-tests-room-and-desktop-sqlite.md). Stations and
 * the station-day pull are network, not part of it. Results compare as sets: row order is not
 * part of the contract.
 */
abstract class NwsStationActualsHostContract {
    /** Writes rows as given (no rounding, quantizing or clock) through the platform's database. */
    interface Seed {
        fun hourly(row: HourlyForecast)
        fun hourlyHistory(row: HourlyForecast, snapshotBucketMs: Long)
        fun observation(row: ObservationReading)
    }

    /** A fresh, empty database; the Host sits at [LAT], [LON]. */
    protected abstract fun open(): Pair<NwsStationActualsRun.Host, Seed>

    private lateinit var host: NwsStationActualsRun.Host
    private lateinit var seed: Seed

    @Before
    fun openDatabase() {
        val (h, s) = open()
        host = h
        seed = s
    }

    @Test
    fun `dailyHistory - every source in the box, both ends inclusive`() = runBlocking {
        host.upsert(
            listOf(
                dh(DAY0, "NWS", 70f), dh(DAY0, "OPEN_METEO", 71f), dh(DAY1, "NWS", 72f, lat = NEARBY_LAT),
                dh(DAY1, "NWS", 73f, lat = OUTSIDE_LAT), dh(DAY1 + DAY, "NWS", 74f),
            ),
        )

        assertEquals(
            setOf(Triple(DAY0, "NWS", 70f), Triple(DAY0, "OPEN_METEO", 71f), Triple(DAY1, "NWS", 72f)),
            host.dailyHistory(DAY0, DAY1).map { Triple(it.date, it.source, it.computedHighTemp) }.toSet(),
        )
    }

    @Test
    fun `upsert replaces the row on its key`() = runBlocking {
        host.upsert(listOf(dh(DAY0, "NWS", 70f)))
        host.upsert(listOf(dh(DAY0, "NWS", 75f).copy(apiHighTemp = 74f, actualsSource = "nws_station_pull")))

        val row = host.dailyHistory(DAY0, DAY0).single()
        assertEquals(listOf(75f, 74f), listOf(row.computedHighTemp, row.apiHighTemp))
        assertEquals("nws_station_pull", row.actualsSource)
    }

    @Test
    fun `nwsLiveHourly - NWS rows in the box, start inclusive, end exclusive, uncollapsed`() = runBlocking {
        seed.hourly(hr(H0, 60f, "NWS"))
        seed.hourly(hr(H0, 61f, "NWS", lat = NEARBY_LAT))
        seed.hourly(hr(H0, 62f, "OPEN_METEO"))
        seed.hourly(hr(H0, 63f, "NWS", lat = OUTSIDE_LAT))
        seed.hourly(hr(H0 + 2 * HOUR, 64f, "NWS"))

        assertEquals(setOf(60f, 61f), host.nwsLiveHourly(H0, H0 + 2 * HOUR).map { it.temperature }.toSet())
    }

    @Test
    fun `nwsHourlyHistory - every NWS snapshot in the box, end exclusive`() = runBlocking {
        seed.hourlyHistory(hr(H0, 50f, "NWS", fetchedAt = T1), snapshotBucketMs = T1)
        seed.hourlyHistory(hr(H0, 51f, "NWS", fetchedAt = T2), snapshotBucketMs = T2)
        seed.hourlyHistory(hr(H0, 52f, "OPEN_METEO", fetchedAt = T2), snapshotBucketMs = T2)
        seed.hourlyHistory(hr(H0 + 2 * HOUR, 53f, "NWS", fetchedAt = T2), snapshotBucketMs = T2)

        assertEquals(setOf(50f, 51f), host.nwsHourlyHistory(H0, H0 + 2 * HOUR).map { it.temperature }.toSet())
    }

    /**
     * User, 2026-10-24: live wins every hour it has; history fills the rest (desktop's rule, now both).
     * Every fetch overwrites the live row as it writes its snapshot, so the live row is never older
     * than history; rows compete on `fetchedAt` (SettledForecastPick).
     */
    @Test
    fun `the day's NWS hourly - live wins, history fills hours without a live row`() = runBlocking {
        seed.hourly(hr(H0, 60f, "NWS", fetchedAt = T2))
        seed.hourlyHistory(hr(H0, 50f, "NWS", fetchedAt = T1), snapshotBucketMs = T1)
        seed.hourlyHistory(hr(H0 + HOUR, 51f, "NWS", fetchedAt = T1), snapshotBucketMs = T1)

        val hourly = NwsStationActualsRun.nwsHourlyForDay(host, H0, H0 + 2 * HOUR, nowMs = H0 + 3 * HOUR)

        assertEquals(listOf(H0 to 60f, H0 + HOUR to 51f), hourly.map { it.dateTime to it.temperature })
    }

    /** Both platforms' reads end in `ObservationSiteMerge`: nearby device fragments merge, one copy per report. */
    @Test
    fun `storedObservations - start inclusive, end exclusive, nearby fragments merged, every api`() = runBlocking {
        seed.observation(obs("KNUQ", H0, 60f, "NWS"))
        seed.observation(obs("KNUQ", H0, 61f, "NWS", lat = FRAGMENT_LAT)) // same report, a fragment
        seed.observation(obs("KSJC", H0, 62f, "NWS", lat = FRAGMENT_LAT))
        seed.observation(obs("KNUQ", H0, 63f, "SYNOPTIC"))
        seed.observation(obs("KPAO", H0, 64f, "NWS", lat = NEARBY_LAT)) // another town in the box
        seed.observation(obs("KNUQ", H0 + 2 * HOUR, 65f, "NWS"))

        assertEquals(
            setOf("KNUQ/NWS" to 60f, "KSJC/NWS" to 62f, "KNUQ/SYNOPTIC" to 63f),
            host.storedObservations(H0, H0 + 2 * HOUR).map { "${it.stationId}/${it.api}" to it.temperature }.toSet(),
        )
    }

    companion object {
        const val LAT = 37.417
        const val LON = -122.089
        /** Inside `ObservationSiteMerge.MERGE_TOLERANCE_DEG` (0.01°). */
        const val FRAGMENT_LAT = 37.422
        /** Inside the ±0.1° read box, another site. */
        const val NEARBY_LAT = 37.467
        const val OUTSIDE_LAT = 37.567
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        val DAY0: Long = LocalDate.of(2026, 10, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val DAY1: Long = DAY0 + DAY
        val H0: Long = DAY0 + 18 * HOUR
        val T1: Long = DAY0 - 2 * DAY
        val T2: Long = DAY0 - DAY

        fun dh(date: Long, source: String, high: Float, lat: Double = LAT) = DailyHistory(
            date = date, source = source, locationLat = lat, locationLon = LON,
            computedHighTemp = high, computedLowTemp = 40f, condition = "Sunny", updatedAt = T1,
        )

        fun hr(dateTime: Long, temp: Float, source: String, lat: Double = LAT, fetchedAt: Long = T1) = HourlyForecast(
            dateTime = dateTime, temperature = temp, condition = "Cloudy", source = source,
            fetchedAt = fetchedAt, locationLat = lat, locationLon = LON,
        )

        fun obs(station: String, timestamp: Long, temp: Float, api: String, lat: Double = LAT) = ObservationReading(
            stationId = station, stationName = station, timestamp = timestamp, temperature = temp,
            condition = "Clear", locationLat = lat, locationLon = LON, distanceKm = 4f, api = api, fetchedAt = T2,
        )
    }
}
