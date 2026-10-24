package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.observations.NwsStationBundles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [NwsStationBundles.Host.touchLatest] writes, on Android's Room Host and desktop's SQLite Host
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md): an attempt that stored nothing
 * stamps the station's newest reading **at this site** with the attempt time, so the observations
 * screen can tell a silent station from a stalled pipeline.
 */
abstract class NwsStationBundlesHostContract {
    interface Db {
        /** Writes an observation as given (coordinates already on the quantized key). */
        fun observation(row: ObservationReading)

        /** (stationId, timestamp, locationLat) → fetchedAt for every stored row. */
        fun fetchedAts(): Map<Triple<String, Long, Double>, Long>
    }

    /** A fresh, empty database; the Host sits at [LAT], [LON] (raw device coordinates) and stamps [NOW]. */
    protected abstract fun open(): Pair<NwsStationBundles.Host, Db>

    @Test
    fun `touchLatest stamps only this station's newest reading at this site`() = runBlocking {
        val (host, db) = open()
        db.observation(obs("KNUQ", T0))
        db.observation(obs("KNUQ", T0 + HOUR)) // newest here: the one stamped
        db.observation(obs("KNUQ", T0 + 2 * HOUR, lat = 37.467)) // newer, but another town in the box
        db.observation(obs("KNUQ", T0 + 3 * HOUR, lat = 37.418)) // newer, a jitter fragment's key
        db.observation(obs("KSJC", T0 + 4 * HOUR))

        host.touchLatest("KNUQ")

        assertEquals(
            mapOf(
                Triple("KNUQ", T0, 37.417) to FETCHED,
                Triple("KNUQ", T0 + HOUR, 37.417) to NOW,
                Triple("KNUQ", T0 + 2 * HOUR, 37.467) to FETCHED,
                Triple("KNUQ", T0 + 3 * HOUR, 37.418) to FETCHED,
                Triple("KSJC", T0 + 4 * HOUR, 37.417) to FETCHED,
            ),
            db.fetchedAts(),
        )
    }

    @Test
    fun `touchLatest for a station with nothing stored here changes nothing`() = runBlocking {
        val (host, db) = open()
        db.observation(obs("KSJC", T0))

        host.touchLatest("KNUQ")

        assertEquals(mapOf(Triple("KSJC", T0, 37.417) to FETCHED), db.fetchedAts())
    }

    companion object {
        const val LAT = 37.4166014
        const val LON = -122.0888722
        const val HOUR = 3_600_000L
        const val T0 = 1_792_800_000_000L
        const val FETCHED = T0 + 5 * HOUR
        const val NOW = T0 + 9 * HOUR

        fun obs(station: String, timestamp: Long, lat: Double = 37.417) = ObservationReading(
            stationId = station, stationName = station, timestamp = timestamp, temperature = 60f,
            condition = "Clear", locationLat = lat, locationLon = -122.089, api = "NWS", fetchedAt = FETCHED,
        )
    }
}
