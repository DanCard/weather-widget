package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.HourlyForecast
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The hourly line each platform draws — its real live + history reads through the shared
 * `HourlyForecastStitcher` — on Android's Room database and desktop's SQLite database. Same seeds,
 * same expected hours: the selection rule (`SettledForecastPick`) is shared, so a platform that
 * pre-reduces its rows (desktop merged history to the freshest snapshot until 2026-10-27, so its
 * past hours never froze) fails here. plans/261027-pre-hour-freeze-retention-and-shared-pick.md
 */
abstract class StitchedHourlyReadContract {
    /** Writes rows as given (no rounding, quantizing or clock) through the platform's database. */
    interface Seed {
        fun hourly(row: HourlyForecast)
        fun hourlyHistory(row: HourlyForecast, snapshotBucketMs: Long)
    }

    /** The platform's stitched reads at [LAT], [LON]; [startMs] and [endMs] fall between hours. */
    interface Reader {
        /** [SOURCE] alone. */
        suspend fun read(startMs: Long, endMs: Long, nowMs: Long): List<HourlyForecast>

        /** [SOURCE] and [OTHER_SOURCE], each settled on its own rows. */
        suspend fun readAllSources(startMs: Long, endMs: Long, nowMs: Long): List<HourlyForecast>
    }

    /** A fresh, empty database. */
    protected abstract fun open(): Pair<Reader, Seed>

    private lateinit var reader: Reader
    private lateinit var seed: Seed

    @Before
    fun openDatabase() {
        val (r, s) = open()
        reader = r
        seed = s
    }

    private suspend fun readHour(hour: Long, nowMs: Long): Pair<Float, Int?> =
        reader.read(hour - HOUR / 2, hour + HOUR / 2, nowMs).single { it.dateTime == hour }
            .let { it.temperature to it.cloudCover }

    private fun history(hour: Long, temp: Float, fetchedAt: Long, cloud: Int? = null) =
        seed.hourlyHistory(hr(hour, temp, fetchedAt, cloud), snapshotBucketMs = fetchedAt)

    /** No pre-hour freeze since 2026-11-04 (plans/261104-remove-hourly-pre-hour-freeze.md): the newest fetch stands. */
    @Test
    fun `a past hour takes its newest fetch, a null field from the next-newest`() = runBlocking {
        history(H0, 58f, fetchedAt = H0 - 6 * DAY, cloud = 10) // long-range
        history(H0, 61f, fetchedAt = H0 - HOUR, cloud = 40)
        history(H0, 62f, fetchedAt = H0 - 20 * MINUTE) // NWS near-term: no sky cover
        history(H0, 70f, fetchedAt = H0 + 27 * MINUTE, cloud = 94)
        seed.hourly(hr(H0, 70f, fetchedAt = H0 + 27 * MINUTE, cloud = 94))

        history(H0, 72f, fetchedAt = H0 + 40 * MINUTE) // newest: no sky cover

        // Two days later: a past day, not just today's elapsed hours.
        assertEquals(72f to 94, readHour(H0, nowMs = H0 + 2 * DAY))
    }

    /** Each source reads its own newest fetch; another source's row never stands in. */
    @Test
    fun `every source settles on its own newest fetch`() = runBlocking {
        history(H0, 61f, fetchedAt = H0 - HOUR)
        seed.hourly(hr(H0, 70f, fetchedAt = H0 + 20 * MINUTE))
        seed.hourlyHistory(hr(H0, 52f, fetchedAt = H0 - HOUR).copy(source = OTHER_SOURCE), snapshotBucketMs = H0 - HOUR)
        seed.hourly(hr(H0, 58f, fetchedAt = H0 + 20 * MINUTE).copy(source = OTHER_SOURCE))

        val rows = reader.readAllSources(H0 - HOUR / 2, H0 + HOUR / 2, nowMs = H0 + 30 * MINUTE)

        assertEquals(mapOf(SOURCE to 70f, OTHER_SOURCE to 58f), rows.associate { it.source to it.temperature })
    }

    /** A later live rewrite (a Google refill) replaces a stale forecast: newest wins. */
    @Test
    fun `a refilled live row replaces a stale past hour's forecast`() = runBlocking {
        history(H0, 71.7f, fetchedAt = H0 - 8 * HOUR, cloud = 20)
        seed.hourly(hr(H0, 69.5f, fetchedAt = H0 + 20 * HOUR, cloud = 60))

        assertEquals(69.5f to 60, readHour(H0, nowMs = H0 + DAY))
    }

    @Test
    fun `an hour not yet started takes the newest fetch`() = runBlocking {
        history(H0, 50f, fetchedAt = H0 - 3 * HOUR, cloud = 30)
        seed.hourly(hr(H0, 55f, fetchedAt = H0 - HOUR, cloud = 35))

        assertEquals(55f to 35, readHour(H0, nowMs = H0 - 30 * MINUTE))
    }

    companion object {
        const val SOURCE = "NWS"
        const val OTHER_SOURCE = "OPEN_METEO"
        const val LAT = 37.417
        const val LON = -122.089
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        /** 2026-10-25T19:00Z, hour-aligned. */
        const val H0 = 1_792_954_800_000L

        fun hr(dateTime: Long, temp: Float, fetchedAt: Long, cloud: Int? = null) = HourlyForecast(
            dateTime = dateTime, temperature = temp, condition = "Cloudy", cloudCover = cloud,
            source = SOURCE, fetchedAt = fetchedAt, locationLat = LAT, locationLon = LON,
        )
    }
}
