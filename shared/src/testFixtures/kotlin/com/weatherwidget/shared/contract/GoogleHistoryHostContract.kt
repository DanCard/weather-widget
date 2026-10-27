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
 * What [GoogleHistoryRun.Host] reads, on Android's Room Host and desktop's SQLite Host
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md): Google's history hours on every
 * same-site fragment, `[start, end)`, any bucket.
 */
abstract class GoogleHistoryHostContract {
    interface Seed {
        /** Writes a live `hourly_forecasts` row as given (never read by the run; staleness no longer matters). */
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

    /**
     * The previous-day refill is gone (plans/261027-remove-google-history-refill.md): `history/hours`
     * is requested only for a site with no history in the last day, however stale its live hours are.
     */
    @Test
    fun `run - history only for a site without coverage, never for stale live hours`() = runBlocking {
        val now = System.currentTimeMillis()
        val hourStart = now - Math.floorMod(now, HOUR)
        val stale = hourStart - 5 * HOUR

        val (bare, bareSeed) = open()
        bareSeed.live(hr(stale, 10).copy(fetchedAt = stale - 8 * HOUR))
        assertTrue("no history at the site", GoogleHistoryRun(bare).needsHistory(now))

        val (covered, coveredSeed) = open()
        coveredSeed.live(hr(stale, 10).copy(fetchedAt = stale - 8 * HOUR))
        coveredSeed.history(hr(stale - HOUR, 20), bucket = stale - 9 * HOUR)
        assertFalse("covered site, stale live hour", GoogleHistoryRun(covered).needsHistory(now))
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
