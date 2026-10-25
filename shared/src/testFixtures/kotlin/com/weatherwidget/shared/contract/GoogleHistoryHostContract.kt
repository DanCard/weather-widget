package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleHistoryRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [GoogleHistoryRun.Host] reads and writes, on Android's Room Host and desktop's SQLite Host
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md): Google's rows on every same-site
 * fragment — history `[start, end)` any bucket, live both ends inclusive — and the refill written over
 * the quantized key's live row, stamped now. Which hour is stale is the run's (each hour's newest row).
 */
abstract class GoogleHistoryHostContract {
    interface Seed {
        /** Writes a live `hourly_forecasts` row as given. */
        fun live(row: HourlyForecast)

        /** Writes an `hourly_forecast_history` row as given, under [bucket]. */
        fun history(row: HourlyForecast, bucket: Long)
    }

    /** A fresh, empty database; the Host sits at [LAT], [LON]. */
    protected abstract fun open(): Pair<GoogleHistoryRun.Host, Seed>

    @Test
    fun `coveredHistoryHours - Google, same-site fragments, any bucket, end exclusive`() = runBlocking {
        val (host, seed) = open()
        seed.history(hr(H0, 10), bucket = H0 - 8 * HOUR)
        seed.history(hr(H0 + HOUR, 20, lat = FRAGMENT_LAT), bucket = H0 - 4 * HOUR)
        seed.history(hr(H0 + 2 * HOUR, 30, lat = TOWN_LAT), bucket = H0 - 4 * HOUR) // inside the box, another site
        seed.history(hr(H0 + 3 * HOUR, 40, source = NWS), bucket = H0 - 4 * HOUR)
        seed.history(hr(H0 + 4 * HOUR, 50), bucket = H0 - 4 * HOUR) // the end
        seed.history(hr(H0 - HOUR, 60), bucket = H0 - 4 * HOUR)

        assertEquals(setOf(H0, H0 + HOUR), host.coveredHistoryHours(H0, H0 + 4 * HOUR).toSet())
    }

    @Test
    fun `liveHours - Google, every same-site fragment, both ends inclusive`() = runBlocking {
        val (host, seed) = open()
        seed.live(hr(H0, 10))
        seed.live(hr(H0, 20, lat = FRAGMENT_LAT)) // the same hour on a fragment: both come back
        seed.live(hr(H0 + HOUR, 30, lat = FRAGMENT_LAT)) // an hour only a fragment has
        seed.live(hr(H0 + 2 * HOUR, 40, lat = TOWN_LAT))
        seed.live(hr(H0 + 3 * HOUR, 50, source = NWS))
        seed.live(hr(H0 + 4 * HOUR, 60)) // the end
        seed.live(hr(H0 + 5 * HOUR, 70))

        assertEquals(setOf(10, 20, 30, 60), host.liveHours(H0, H0 + 4 * HOUR).map { it.precipProbability }.toSet())
    }

    @Test
    fun `saveRefill - replaces the quantized key's live row, stamped now, fragments and history untouched`() = runBlocking {
        val (host, seed) = open()
        seed.live(hr(H0, 10))
        seed.live(hr(H0, 20, lat = FRAGMENT_LAT))
        seed.history(hr(H0, 30), bucket = H0 - 8 * HOUR)
        val before = System.currentTimeMillis()

        host.saveRefill(listOf(hr(H0, 90, lat = LAT, lon = LON).copy(temperature = 71.5f, fetchedAt = 0)))

        val rows = host.liveHours(H0, H0).associateBy { it.precipProbability }
        assertEquals(setOf(20, 90), rows.keys)
        val refilled = rows.getValue(90)
        assertEquals(71.5f, refilled.temperature)
        assertEquals(KEY_LAT, refilled.locationLat!!, 1e-9)
        assertEquals(KEY_LON, refilled.locationLon!!, 1e-9)
        assertTrue("fetchedAt ${refilled.fetchedAt} stamped now (≥ $before)", refilled.fetchedAt >= before)
        assertEquals(H0 - 2 * HOUR, rows.getValue(20).fetchedAt)
        assertEquals(setOf(H0), host.coveredHistoryHours(H0, H0 + HOUR).toSet())
    }

    @Test
    fun `run - an hour kept current on a fragment only is not stale, a refill clears a stale one`() = runBlocking {
        val (host, seed) = open()
        val now = System.currentTimeMillis()
        val fresh = now - Math.floorMod(now, HOUR) - 3 * HOUR
        val stale = fresh - HOUR
        seed.live(hr(fresh, 10, lat = FRAGMENT_LAT).copy(fetchedAt = fresh - HOUR))
        seed.live(hr(stale, 20).copy(fetchedAt = stale - 7 * HOUR))
        val run = GoogleHistoryRun(host)

        val before = run.staleHours(null, now)
        assertFalse("fragment-only hour fetched 1 h ahead is current", fresh in before)
        assertTrue("hour fetched 7 h ahead is stale", stale in before)

        host.saveRefill(listOf(hr(stale, 30)))
        assertFalse(stale in run.staleHours(null, System.currentTimeMillis()))
    }

    companion object {
        const val LAT = 37.4166014
        const val LON = -122.0888722
        const val KEY_LAT = 37.417
        const val KEY_LON = -122.089
        const val FRAGMENT_LAT = 37.418
        const val TOWN_LAT = 37.467
        const val HOUR = 3_600_000L
        const val H0 = 1_792_900_800_000L
        val GOOGLE = WeatherSource.GOOGLE_WEATHER.id
        val NWS = WeatherSource.NWS.id

        fun hr(dateTime: Long, pop: Int, source: String = GOOGLE, lat: Double = KEY_LAT, lon: Double = KEY_LON) = HourlyForecast(
            dateTime = dateTime, temperature = 60f, condition = "Cloudy", precipProbability = pop, source = source,
            fetchedAt = H0 - 2 * HOUR, locationLat = lat, locationLon = lon,
        )
    }
}
