package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.history.HourlySnapshotRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [HourlySnapshotRun.Host.lastSnapshotMs] reads, on Android's Room Host and desktop's SQLite Host
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md): the newest snapshot label of this
 * source at this site. The other Host members are app state, not the database.
 */
abstract class HourlySnapshotHostContract {
    /** Writes a `hourly_forecast_history` row as given. */
    fun interface Seed {
        fun hourlyHistory(row: HourlyForecast, label: Long)
    }

    /** A fresh, empty database; the Host is [SOURCE]'s at [LAT], [LON] (raw device coordinates). */
    protected abstract fun open(): Pair<HourlySnapshotRun.Host, Seed>

    @Test
    fun `nothing stored reads as null`() = runBlocking {
        assertEquals(null, open().first.lastSnapshotMs())
    }

    @Test
    fun `the newest label of this source at this site, a jitter fragment included`() = runBlocking {
        val (host, seed) = open()
        seed.hourlyHistory(hr(lat = 37.417), label = T0)
        seed.hourlyHistory(hr(lat = 37.418), label = T0 + 2 * HOUR) // same site, ~110 m off
        seed.hourlyHistory(hr(lat = 37.467), label = T0 + 5 * HOUR) // another town in the read box
        seed.hourlyHistory(hr(source = "OPEN_METEO"), label = T0 + 6 * HOUR)

        assertEquals(T0 + 2 * HOUR, host.lastSnapshotMs())
    }

    companion object {
        const val SOURCE = "NWS"
        const val LAT = 37.4166014
        const val LON = -122.0888722
        const val HOUR = 3_600_000L
        const val T0 = 1_792_800_000_000L

        fun hr(lat: Double = 37.417, source: String = SOURCE) = HourlyForecast(
            dateTime = T0 + 24 * HOUR, temperature = 60f, condition = "Cloudy", source = source,
            fetchedAt = T0, locationLat = lat, locationLon = -122.089,
        )
    }
}
