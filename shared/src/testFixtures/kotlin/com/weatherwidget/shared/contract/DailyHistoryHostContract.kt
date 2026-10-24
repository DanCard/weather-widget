package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.shared.actuals.DailyHistoryMaintenance
import com.weatherwidget.shared.actuals.DailyHistoryRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What [DailyHistoryRun.Host]'s reads and writes mean, run against each platform's real database:
 * Android's Room Host (`:app` Robolectric) and desktop's JDBC Host (`:desktop`, temp-file SQLite).
 * The runner's own tests use a fake Host, so without this a Host whose SQL means something else on
 * one platform passes everything (plans/261024-host-contract-tests-room-and-desktop-sqlite.md).
 *
 * Each case seeds rows that tell the plausible readings apart. Row order is not part of the contract
 * (the runner reduces by key), so results compare as sets.
 */
abstract class DailyHistoryHostContract {
    /** Writes rows exactly as given — no rounding, quantizing or clock — through the platform's DB. */
    interface Seed {
        fun forecast(row: DailyHistoryMaintenance.ForecastHistoryRow, dateOfPrediction: Long)
        fun hourly(row: HourlyForecast)
        fun hourlyHistory(row: HourlyForecast, snapshotBucketMs: Long)
    }

    /** A fresh, empty database each call. */
    protected abstract fun open(): Pair<DailyHistoryRun.Host, Seed>

    private lateinit var host: DailyHistoryRun.Host
    private lateinit var seed: Seed

    @Before
    fun openDatabase() {
        val (h, s) = open()
        host = h
        seed = s
    }

    @Test
    fun `forecastFetches - every fetch in the box, both ends inclusive, implausible temps nulled`() = runBlocking {
        seed.forecast(fc(DAY0, high = 70f, fetchedAt = T1), dateOfPrediction = DAY0 - DAY)
        seed.forecast(fc(DAY0, high = 72f, fetchedAt = T2), dateOfPrediction = DAY0 - DAY)
        seed.forecast(fc(DAY1, high = 65f, fetchedAt = T1, lat = NEARBY_LAT), dateOfPrediction = DAY0)
        seed.forecast(fc(DAY1, high = 999f, fetchedAt = T2), dateOfPrediction = DAY0)
        seed.forecast(fc(DAY1, high = 50f, fetchedAt = T1, lat = OUTSIDE_LAT), dateOfPrediction = DAY0)
        seed.forecast(fc(DAY0 - DAY, high = 40f, fetchedAt = T1), dateOfPrediction = DAY0 - 2 * DAY)
        seed.forecast(fc(DAY1 + DAY, high = 41f, fetchedAt = T1), dateOfPrediction = DAY0)

        val rows = host.forecastFetches(DAY0, DAY1, LAT, LON)

        assertEquals(
            setOf(
                Triple(DAY0, 70f, LAT), Triple(DAY0, 72f, LAT),
                Triple(DAY1, 65f, NEARBY_LAT), Triple(DAY1, null, LAT),
            ),
            rows.map { Triple(it.dateMs, it.highTemp, it.locationLat) }.toSet(),
        )
        val fetch = rows.single { it.highTemp == 72f }
        assertEquals(T2, fetch.fetchedAt)
        assertEquals(T2, fetch.batchFetchedAt)
        assertEquals("NWS", fetch.source)
    }

    @Test
    fun `dailyHistory - rows in the box, both ends inclusive`() = runBlocking {
        host.insertDailyHistory(
            listOf(
                dh(DAY0, high = 70f), dh(DAY1, high = 71f, lat = NEARBY_LAT), dh(DAY1, high = 72f, lat = OUTSIDE_LAT),
                dh(DAY1 + DAY, high = 73f), dh(DAY0 - DAY, high = 74f),
            ),
        )

        assertEquals(
            setOf(DAY0 to 70f, DAY1 to 71f),
            host.dailyHistory(DAY0, DAY1, LAT, LON).map { it.date to it.computedHighTemp }.toSet(),
        )
    }

    @Test
    fun `hourlyForecasts - every source in the box, both ends inclusive`() = runBlocking {
        seed.hourly(hr(H0, 60f, "NWS"))
        seed.hourly(hr(H0 + HOUR, 61f, "NWS"))
        seed.hourly(hr(H0 + HOUR, 62f, "OPEN_METEO"))
        seed.hourly(hr(H0 + 2 * HOUR, 63f, "NWS"))
        seed.hourly(hr(H0 + 3 * HOUR, 64f, "NWS"))
        seed.hourly(hr(H0 + HOUR, 65f, "NWS", lat = OUTSIDE_LAT))
        seed.hourly(hr(H0 + HOUR, 66f, "NWS", lat = NEARBY_LAT))

        assertEquals(
            setOf(60f, 61f, 62f, 63f, 66f),
            host.hourlyForecasts(H0, H0 + 2 * HOUR, LAT, LON).map { it.temperature }.toSet(),
        )
    }

    /**
     * Every snapshot of the hour, unmerged — [HourlyForecastStitcher] picks the freshest and fills
     * its missing cloud from older ones, on both platforms. Desktop's Host used to return its
     * display read: one pre-merged row per hour, the end hour, and `Generic` rows for future hours.
     */
    @Test
    fun `hourlyHistory - every snapshot of the source, end exclusive, no Generic`() = runBlocking {
        seed.hourlyHistory(hr(H0, 60f, "NWS", cloud = 40, fetchedAt = T1), snapshotBucketMs = T1)
        seed.hourlyHistory(hr(H0, 62f, "NWS", cloud = null, fetchedAt = T2), snapshotBucketMs = T2)
        seed.hourlyHistory(hr(H0, 70f, "OPEN_METEO", fetchedAt = T2), snapshotBucketMs = T2)
        seed.hourlyHistory(hr(H0 + HOUR, 71f, "Generic", fetchedAt = T2), snapshotBucketMs = T2)
        seed.hourlyHistory(hr(H0 + 2 * HOUR, 72f, "NWS", fetchedAt = T2), snapshotBucketMs = T2)
        seed.hourlyHistory(hr(H0, 73f, "NWS", lat = OUTSIDE_LAT, fetchedAt = T2), snapshotBucketMs = T2)

        val rows = host.hourlyHistory(H0, H0 + 2 * HOUR, LAT, LON, "NWS")

        assertEquals(
            setOf(Triple(H0, 60f, 40), Triple(H0, 62f, null)),
            rows.map { Triple(it.dateTime, it.temperature, it.cloudCover) }.toSet(),
        )
        val stitched = HourlyForecastStitcher.stitch(emptyList(), rows, nowMs = H0, centerLat = LAT, centerLon = LON)
        assertEquals(listOf(Triple(H0, 62f, 40)), stitched.map { Triple(it.dateTime, it.temperature, it.cloudCover) })
    }

    @Test
    fun `insertDailyHistory replaces the row on its key`() = runBlocking {
        host.insertDailyHistory(listOf(dh(DAY0, high = 70f)))
        host.insertDailyHistory(listOf(dh(DAY0, high = 75f)))

        assertEquals(listOf(75f), host.dailyHistory(DAY0, DAY0, LAT, LON).map { it.computedHighTemp })
    }

    @Test
    fun `updateForecastOverlay writes only the overlay and lastWriter`() = runBlocking {
        host.insertDailyHistory(listOf(full()))
        host.updateForecastOverlay(
            full().copy(forecastHighTemp = 80f, forecastLowTemp = 50f, priorForecastHighTemp = 1f, noonCloudPercent = 1, computedHighTemp = 1f, lastWriter = "NEW"),
        )

        val row = host.dailyHistory(DAY0, DAY0, LAT, LON).single()
        assertEquals(listOf(80f, 50f), listOf(row.forecastHighTemp, row.forecastLowTemp))
        assertEquals("NEW", row.lastWriter)
        assertUntouched(row, overlay = false, prior = true)
    }

    @Test
    fun `updatePriorForecast writes only yesterday's forecast and lastWriter`() = runBlocking {
        host.insertDailyHistory(listOf(full()))
        host.updatePriorForecast(
            full().copy(priorForecastHighTemp = 81f, priorForecastLowTemp = 51f, forecastHighTemp = 1f, noonCloudPercent = 1, computedHighTemp = 1f, lastWriter = "NEW"),
        )

        val row = host.dailyHistory(DAY0, DAY0, LAT, LON).single()
        assertEquals(listOf(81f, 51f), listOf(row.priorForecastHighTemp, row.priorForecastLowTemp))
        assertEquals("NEW", row.lastWriter)
        assertUntouched(row, overlay = true, prior = false)
    }

    private fun assertUntouched(row: DailyHistory, overlay: Boolean, prior: Boolean) {
        val before = full()
        assertEquals(before.computedHighTemp, row.computedHighTemp)
        assertEquals(before.computedLowTemp, row.computedLowTemp)
        assertEquals(before.noonCloudPercent, row.noonCloudPercent)
        assertEquals(before.forecastDayPrecipChance, row.forecastDayPrecipChance)
        if (overlay) assertEquals(listOf(before.forecastHighTemp, before.forecastLowTemp), listOf(row.forecastHighTemp, row.forecastLowTemp))
        if (prior) assertEquals(listOf(before.priorForecastHighTemp, before.priorForecastLowTemp), listOf(row.priorForecastHighTemp, row.priorForecastLowTemp))
    }

    companion object {
        // Stored as quantized, so neither platform's writer would move them.
        const val LAT = 37.417
        const val LON = -122.089
        /** Inside the ±0.1° read box, a different site. */
        const val NEARBY_LAT = 37.467
        const val OUTSIDE_LAT = 37.567
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        val DAY0: Long = LocalDate.of(2030, 1, 10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val DAY1: Long = DAY0 + DAY
        /** In the future, so a `Generic` row would pass desktop's display read's "future only" gate. */
        val H0: Long = DAY0 + 18 * HOUR
        val T1: Long = DAY0 - 2 * DAY
        val T2: Long = DAY0 - DAY

        fun fc(dateMs: Long, high: Float?, fetchedAt: Long, lat: Double = LAT) =
            DailyHistoryMaintenance.ForecastHistoryRow(
                dateMs = dateMs, source = "NWS", locationLat = lat, locationLon = LON,
                highTemp = high, lowTemp = 40f, precipAmountMm = null, condition = "Sunny",
                fetchedAt = fetchedAt, isClimateNormal = false, batchFetchedAt = fetchedAt,
            )

        fun hr(dateTime: Long, temp: Float, source: String, lat: Double = LAT, cloud: Int? = null, fetchedAt: Long = T1) =
            HourlyForecast(
                dateTime = dateTime, temperature = temp, condition = "Cloudy", source = source,
                cloudCover = cloud, fetchedAt = fetchedAt, locationLat = lat, locationLon = LON,
            )

        fun dh(date: Long, high: Float, lat: Double = LAT) =
            DailyHistory(
                date = date, source = "NWS", locationLat = lat, locationLon = LON,
                computedHighTemp = high, computedLowTemp = 40f, condition = "Sunny", updatedAt = T1,
            )

        fun full() = dh(DAY0, high = 70f).copy(
            forecastHighTemp = 68f, forecastLowTemp = 45f,
            priorForecastHighTemp = 66f, priorForecastLowTemp = 44f,
            noonCloudPercent = 30, forecastDayPrecipChance = 20, lastWriter = "OLD",
        )
    }
}
