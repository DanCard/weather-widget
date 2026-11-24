package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ObservationWriteRun
import com.weatherwidget.shared.observations.StationNetworkRefreshRun
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Storage contract for [StationNetworkRefreshRun.Host] on Android (Room) and Desktop (SQLite)
 * (plans/261123-targeted-sync-tail-parity.md): `newestStoredTimestampMs` returns null when empty
 * and the newest timestamp for that API at this site after [StationNetworkRefreshRun.Host.writeHost]
 * stores rows, ignoring other APIs and faraway sites.
 */
abstract class StationNetworkHostContract {
    protected abstract fun open(lat: Double, lon: Double): StationNetworkRefreshRun.Host
    protected abstract fun close()

    private lateinit var host: StationNetworkRefreshRun.Host

    @Before
    fun openHost() {
        host = open(LAT, LON)
    }

    @After
    fun closeHost() = close()

    private fun reading(
        station: String,
        timestamp: Long,
        api: String = WeatherSource.METAR.id,
        lat: Double = LAT,
        lon: Double = LON,
    ) = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = timestamp,
        temperature = 65f,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        distanceKm = 3.0f,
        stationType = StationType.OFFICIAL,
        api = api,
    )

    @Test
    fun `newestStoredTimestampMs is null when empty and tracks the newest row for the requested api at this site`() = runBlocking {
        assertNull(host.newestStoredTimestampMs(WeatherSource.METAR.id))

        val writer = ObservationWriteRun(host.writeHost())
        writer.write(
            ObservationWriteRun.Write(
                listOf(
                    reading("KNUQ", T0, api = WeatherSource.METAR.id),
                    reading("KPAO", T0 + 20 * MIN, api = WeatherSource.METAR.id),
                    // Newer row from another api must not affect METAR's newest timestamp.
                    reading("KNUQ", T0 + 40 * MIN, api = WeatherSource.NWS.id),
                    // Newer METAR row at a faraway site must not affect this site.
                    reading("KLAX", T0 + 60 * MIN, api = WeatherSource.METAR.id, lat = 34.0, lon = -118.4),
                ),
            ),
            nowMs = T0 + 65 * MIN,
        )

        assertEquals(T0 + 20 * MIN, host.newestStoredTimestampMs(WeatherSource.METAR.id))
        assertEquals(T0 + 40 * MIN, host.newestStoredTimestampMs(WeatherSource.NWS.id))
    }

    companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
        const val T0 = 1_760_000_000_000L
        const val MIN = 60_000L
    }
}
