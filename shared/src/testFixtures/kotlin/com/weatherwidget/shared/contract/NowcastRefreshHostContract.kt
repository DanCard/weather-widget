package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.nowcast.NowcastRefreshRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [NowcastRefreshRun.Host.hourly] reads, on Android's Room Host and desktop's SQLite Host
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md): every source's live rows in the read
 * box, both ends inclusive. Which sources count is the run's ([NowcastRefreshRun.Host.usableSourceIds]).
 */
abstract class NowcastRefreshHostContract {
    /** Writes a live `hourly_forecasts` row as given. */
    fun interface Seed {
        fun hourly(row: HourlyForecast)
    }

    /** A fresh, empty database; the Host sits at [LAT], [LON]. */
    protected abstract fun open(): Pair<NowcastRefreshRun.Host, Seed>

    @Test
    fun `hourly - every source in the read box, both ends inclusive`() = runBlocking {
        val (host, seed) = open()
        seed.hourly(hr(H0, 10, "OPEN_METEO"))
        seed.hourly(hr(H0 + HOUR, 20, "NWS"))
        seed.hourly(hr(H0 + HOUR, 30, "Generic"))
        seed.hourly(hr(H0 + 2 * HOUR, 40, "TOMORROW_IO", lat = 37.467)) // another town, inside the box
        seed.hourly(hr(H0 + 3 * HOUR, 50, "OPEN_METEO")) // the window's end
        seed.hourly(hr(H0 + HOUR, 60, "OPEN_METEO", lat = 37.567)) // outside the box
        seed.hourly(hr(H0 - HOUR, 70, "OPEN_METEO"))
        seed.hourly(hr(H0 + 4 * HOUR, 80, "OPEN_METEO"))

        assertEquals(setOf(10, 20, 30, 40, 50), host.hourly(H0, H0 + 3 * HOUR).map { it.precipProbability }.toSet())
    }

    companion object {
        const val LAT = 37.4166014
        const val LON = -122.0888722
        const val HOUR = 3_600_000L
        const val H0 = 1_792_900_800_000L

        fun hr(dateTime: Long, pop: Int, source: String, lat: Double = 37.417) = HourlyForecast(
            dateTime = dateTime, temperature = 60f, condition = "Rain", precipProbability = pop, source = source,
            fetchedAt = H0 - HOUR, locationLat = lat, locationLon = -122.089,
        )
    }
}
