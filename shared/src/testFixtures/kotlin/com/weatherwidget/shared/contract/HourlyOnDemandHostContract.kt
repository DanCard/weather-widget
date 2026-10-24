package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.data.remote.HourlyOnDemandRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What [HourlyOnDemandRun.Host.storedHourly] — the re-check before an on-demand fetch — reads, on
 * Android's Room Host and desktop's SQLite Host
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md). The fetch is network, not part of it.
 */
abstract class HourlyOnDemandHostContract {
    /** Writes live hourly rows as given (no rounding, quantizing or clock). */
    fun interface Seed {
        fun hourly(row: HourlyForecast)
    }

    /** A fresh, empty database with a Host for [SOURCE] at [LAT], [LON], reading at [NOW]. */
    protected abstract fun open(): Pair<HourlyOnDemandRun.Host, Seed>

    /**
     * [SOURCE]'s live rows anywhere in the ±0.1° box, from the hour before now through
     * [HourlyOnDemand.REACH_HOURS] ahead, both ends inclusive. The box (not this site) is what both
     * platforms read today; `hoursToCover` takes any of them as coverage.
     */
    @Test
    fun `storedHourly - this source in the box, an hour back through the reach, both ends inclusive`() = runBlocking {
        val (host, seed) = open()
        seed.hourly(hr(NOW - 2 * HOUR, 1f))
        seed.hourly(hr(NOW - HOUR, 2f))
        seed.hourly(hr(NOW + 5 * HOUR, 3f))
        seed.hourly(hr(NOW + 5 * HOUR, 4f, lat = NEARBY_LAT))
        seed.hourly(hr(NOW + 5 * HOUR, 5f, lat = OUTSIDE_LAT))
        seed.hourly(hr(NOW + 5 * HOUR, 6f, source = "OPEN_METEO"))
        seed.hourly(hr(NOW + HourlyOnDemand.REACH_HOURS * HOUR, 7f))
        seed.hourly(hr(NOW + (HourlyOnDemand.REACH_HOURS + 1) * HOUR, 8f))

        assertEquals(setOf(2f, 3f, 4f, 7f), host.storedHourly().map { it.temperature }.toSet())
        assertEquals(setOf(SOURCE), host.storedHourly().map { it.source }.toSet())
        assertEquals(NOW - 10 * HOUR, host.storedHourly().first { it.temperature == 3f }.fetchedAt)
    }

    companion object {
        const val SOURCE = "NWS"
        const val LAT = 37.417
        const val LON = -122.089
        /** Inside the ±0.1° read box, another site. */
        const val NEARBY_LAT = 37.467
        const val OUTSIDE_LAT = 37.567
        const val HOUR = 3_600_000L
        val NOW: Long = LocalDate.of(2026, 10, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 15 * HOUR

        fun hr(dateTime: Long, temp: Float, source: String = SOURCE, lat: Double = LAT) = HourlyForecast(
            dateTime = dateTime, temperature = temp, condition = "Cloudy", source = source,
            fetchedAt = NOW - 10 * HOUR, locationLat = lat, locationLon = LON,
        )
    }
}
