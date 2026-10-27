package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.StationsList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * What [StationsList.Host] reads, on Android's Room Host and desktop's SQLite Host
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md): the proximity box since a time
 * (inclusive), every site since a time, and a source's own main reading today at the site.
 */
abstract class StationsListHostContract {
    /** Writes an `observations` row as given. */
    fun interface Seed {
        fun observation(stationId: String, api: String, timestamp: Long, lat: Double)
    }

    /** A fresh, empty database; the Host's active location is [LAT], [LON]. */
    protected abstract fun open(): Pair<StationsList.Host<*>, Seed>

    @Suppress("UNCHECKED_CAST")
    private fun ids(host: StationsList.Host<*>, rows: List<Any?>): Set<String> =
        rows.map { (host as StationsList.Host<Any?>).stationId(it) }.toSet()

    @Test
    fun `near - the proximity box since the cutoff, inclusive`() = runBlocking {
        val (host, seed) = open()
        seed.observation("KNUQ", "NWS", T0, LAT)
        seed.observation("KSJC", "NWS", T0 + HOUR, 37.467) // another town, inside the box
        seed.observation("KAUS", "NWS", T0 + HOUR, 30.267) // a previous city
        seed.observation("KPAO", "NWS", T0 - 1, LAT) // before the cutoff
        assertEquals(setOf("KNUQ", "KSJC"), ids(host, host.near(T0, LAT, LON)))
    }

    @Test
    fun `anywhere - every site since the cutoff`() = runBlocking {
        val (host, seed) = open()
        seed.observation("KNUQ", "NWS", T0, LAT)
        seed.observation("KAUS", "NWS", T0 + HOUR, 30.267)
        seed.observation("KPAO", "NWS", T0 - 1, LAT)
        assertEquals(setOf("KNUQ", "KAUS"), ids(host, host.anywhere(T0)))
    }

    @Test
    fun `latestMainReading - today's main row for that source, newest`() = runBlocking {
        val (host, seed) = open()
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        seed.observation("OPEN_METEO_MAIN", "OPEN_METEO", today + 60_000L, LAT)
        seed.observation("OPEN_METEO_MAIN", "OPEN_METEO", today + 120_000L, LAT)
        seed.observation("WEATHER_API_MAIN", "WEATHER_API", today + 180_000L, LAT)
        seed.observation("KNUQ", "OPEN_METEO", today + 240_000L, LAT) // a station, not a main row
        seed.observation("SILURIAN_MAIN", "SILURIAN", today - 60_000L, LAT) // yesterday

        @Suppress("UNCHECKED_CAST")
        val h = host as StationsList.Host<Any?>
        val main = h.latestMainReading(WeatherSource.OPEN_METEO)!!
        assertEquals("OPEN_METEO_MAIN", h.stationId(main))
        assertEquals(today + 120_000L, h.timestamp(main))
        assertNull(h.latestMainReading(WeatherSource.SILURIAN))
    }

    companion object {
        const val LAT = 37.4166014
        const val LON = -122.0888722
        const val HOUR = 3_600_000L
        const val T0 = 1_792_900_800_000L
    }
}
