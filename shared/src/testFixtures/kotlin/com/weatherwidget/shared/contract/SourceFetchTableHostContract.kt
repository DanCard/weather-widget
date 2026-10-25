package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.SourceFetchTable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [SourceFetchTable.Host] reads, on Android's Room Host and desktop's SQLite Host
 * (`plans/261025-shared-source-fetch-table.md`, F2): Google's stored hours are Google's on the site's
 * exact quantized key, both ends inclusive — a jitter fragment's hours are another site's, and counting
 * them would skip pages this site has never stored.
 */
abstract class SourceFetchTableHostContract {
    /** A fresh, empty database. */
    interface Db {
        fun host(): SourceFetchTable.Host

        /** Stores [rows] live for [sourceId] at [lat]/[lon], as a fetch would. */
        suspend fun writeHourly(sourceId: String, lat: Double, lon: Double, rows: List<HourlyForecast>)
    }

    protected abstract fun open(): Db

    protected companion object {
        const val LAT = 37.417
        const val LON = -122.089
        const val FRAGMENT_LAT = 37.418
        const val T0 = 1_792_951_200_000L
        const val HOUR = 3_600_000L

        fun hour(at: Long, source: String) = HourlyForecast(at, 60f, "Clear", source = source)
    }

    @Test
    fun `Google's stored hours are Google's on this site's exact key, both ends inclusive`() = runBlocking {
        val db = open()
        val google = WeatherSource.GOOGLE_WEATHER.id
        db.writeHourly(google, LAT, LON, (0L..4L).map { hour(T0 + it * HOUR, google) })
        db.writeHourly(google, FRAGMENT_LAT, LON, listOf(hour(T0 + 5 * HOUR, google)))
        db.writeHourly(WeatherSource.OPEN_METEO.id, LAT, LON, listOf(hour(T0 + 6 * HOUR, WeatherSource.OPEN_METEO.id)))

        assertEquals(
            listOf(T0 + HOUR, T0 + 2 * HOUR, T0 + 3 * HOUR),
            db.host().storedGoogleHours(LAT, LON, T0 + HOUR, T0 + 3 * HOUR).map { it.dateTime }.sorted(),
        )
        assertEquals(
            "the fragment's hour is not this site's",
            emptyList<Long>(),
            db.host().storedGoogleHours(LAT, LON, T0 + 5 * HOUR, T0 + 6 * HOUR).map { it.dateTime },
        )
    }
}
