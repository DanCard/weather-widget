package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.shared.observations.ObservationWriteRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [ObservationWriteRun.Host] reads and writes, on Android's Room Host and desktop's SQLite Host
 * (`plans/261024-shared-observation-persist-rules.md`): key reads and deletes are one api's stations on
 * the site's exact quantized key, an insert replaces its key and keeps every field, a re-tag reaches
 * every site, and the retired-product cleanup removes a replaced product only where its replacement is
 * (that cleanup's site is `LocationMatch`'s same-site tolerance, not the exact key — shared SQL).
 */
abstract class ObservationWriteHostContract {
    /** A fresh, empty database. */
    interface Db {
        fun host(lat: Double, lon: Double): ObservationWriteRun.Host

        /** Every stored row, any site. */
        suspend fun rows(): List<ObservationReading>
    }

    protected abstract fun open(): Db

    protected companion object {
        const val LAT = 37.417
        const val LON = -122.089
        const val FRAGMENT_LAT = 37.418
        /** Beyond `LocationMatch.SAME_SITE_TOLERANCE_DEG`: another site for every clause. */
        const val OTHER_SITE_LAT = 37.427
        const val T0 = 1_761_400_800_000L
        const val MIN = 60_000L

        fun reading(
            station: String,
            timestamp: Long,
            api: String = WeatherSource.SYNOPTIC.id,
            type: StationType = StationType.PERSONAL,
            lat: Double = LAT,
            temp: Float = 60f,
            fetchedAt: Long = timestamp + MIN,
        ) = ObservationReading(
            stationId = station, stationName = "$station name", timestamp = timestamp, temperature = temp, condition = "Clear",
            locationLat = lat, locationLon = LON, stationType = type, api = api, fetchedAt = fetchedAt,
        )
    }

    @Test
    fun `key reads are one api's stations on this site's exact key, end exclusive`() = runBlocking {
        val db = open()
        val host = db.host(LAT, LON)
        host.insert(
            listOf(
                reading("F4751", T0),
                reading("F4751", T0 + 5 * MIN),
                reading("F4751", T0 + 10 * MIN),
                reading("F4751", T0 + 5 * MIN, lat = FRAGMENT_LAT),
                reading("F4751", T0 + 5 * MIN, api = WeatherSource.NWS.id),
                reading("G6550", T0 + 5 * MIN),
            ),
        )

        assertEquals(
            listOf(ObservationWriteRun.StoredKey("F4751", T0, StationType.PERSONAL), ObservationWriteRun.StoredKey("F4751", T0 + 5 * MIN, StationType.PERSONAL)),
            host.storedKeys(WeatherSource.SYNOPTIC.id, setOf("F4751"), T0, T0 + 10 * MIN).sortedBy { it.timestamp },
        )
    }

    @Test
    fun `an insert keeps every field and its own fetchedAt, and replaces its key`() = runBlocking {
        val db = open()
        val host = db.host(LAT, LON)
        host.insert(listOf(reading("KSJC", T0, type = StationType.OFFICIAL, fetchedAt = T0 + MIN)))
        val rewrite = reading("KSJC", T0, type = StationType.OFFICIAL, temp = 55f, fetchedAt = T0 + 7 * MIN).copy(
            distanceKm = 12.5f, maxTempLast24h = 71f, minTempLast24h = 48f, precipAmountMm = 0.4f, qcFailed = true,
            cloudCover = 80, cloudCoverLow = 60, isMetar = true, rawMetar = "KSJC 251400Z", cloudBaseLowMeters = 900,
        )
        host.insert(listOf(rewrite))

        assertEquals(listOf(rewrite), db.rows())
    }

    @Test
    fun `a delete removes only that api's station rows at this site and the given times`() = runBlocking {
        val db = open()
        val host = db.host(LAT, LON)
        host.insert(
            listOf(
                reading("F4751", T0),
                reading("F4751", T0 + 5 * MIN),
                reading("F4751", T0 + 10 * MIN),
                reading("F4751", T0 + 5 * MIN, lat = FRAGMENT_LAT),
                reading("F4751", T0 + 5 * MIN, api = WeatherSource.NWS.id),
                reading("G6550", T0 + 5 * MIN),
            ),
        )

        assertEquals(2, host.delete(WeatherSource.SYNOPTIC.id, "F4751", listOf(T0 + 5 * MIN, T0 + 10 * MIN)))
        assertEquals(
            setOf(
                Triple("F4751", T0, LAT),
                Triple("F4751", T0 + 5 * MIN, FRAGMENT_LAT),
                Triple("F4751", T0 + 5 * MIN, LAT),
                Triple("G6550", T0 + 5 * MIN, LAT),
            ),
            db.rows().map { Triple(it.stationId, it.timestamp, it.locationLat) }.toSet(),
        )
        assertEquals(setOf(WeatherSource.NWS.id), db.rows().filter { it.stationId == "F4751" && it.timestamp == T0 + 5 * MIN && it.locationLat == LAT }.map { it.api }.toSet())
    }

    @Test
    fun `a re-tag reaches every site of that api's stations and counts the rows it changed`() = runBlocking {
        val db = open()
        val host = db.host(LAT, LON)
        host.insert(
            listOf(
                reading("KXYZ", T0, type = StationType.OFFICIAL),
                reading("KXYZ", T0, type = StationType.OFFICIAL, lat = FRAGMENT_LAT),
                reading("KXYZ", T0 + 5 * MIN, type = StationType.RAWS),
                reading("KXYZ", T0, api = WeatherSource.NWS.id, type = StationType.OFFICIAL),
            ),
        )

        assertEquals(2, host.retag(WeatherSource.SYNOPTIC.id, setOf("KXYZ"), StationType.RAWS))
        assertEquals(
            mapOf(WeatherSource.SYNOPTIC.id to setOf(StationType.RAWS), WeatherSource.NWS.id to setOf(StationType.OFFICIAL)),
            db.rows().groupBy({ it.api }, { it.stationType }).mapValues { it.value.toSet() },
        )
    }

    @Test
    fun `retired products go only at a site where the replacement product has rows`() = runBlocking {
        val db = open()
        val tomorrow = WeatherSource.TOMORROW_IO.id
        val legacy = TomorrowIoActuals.RECENT_HISTORY_STATION_ID
        val current = TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID
        db.host(LAT, LON).insert(listOf(reading(current, T0, api = tomorrow, type = StationType.UNKNOWN), reading(legacy, T0, api = tomorrow, type = StationType.UNKNOWN)))
        db.host(OTHER_SITE_LAT, LON).insert(listOf(reading(legacy, T0, api = tomorrow, type = StationType.UNKNOWN, lat = OTHER_SITE_LAT)))

        db.host(LAT, LON).retireProducts(tomorrow)
        db.host(OTHER_SITE_LAT, LON).retireProducts(tomorrow)

        assertEquals(
            setOf(current to LAT, legacy to OTHER_SITE_LAT),
            db.rows().map { it.stationId to it.locationLat }.toSet(),
        )
    }
}
