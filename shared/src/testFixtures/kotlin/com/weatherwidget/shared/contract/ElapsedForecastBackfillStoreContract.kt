package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What [ElapsedForecastBackfill.Store] means, on Android's Room store and desktop's SQLite store
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md): which hours count as already
 * snapshotted, and that an insert makes them so.
 */
abstract class ElapsedForecastBackfillStoreContract {
    /** Writes a `hourly_forecast_history` row as given. */
    fun interface Seed {
        fun hourlyHistory(row: HourlyForecast, snapshotBucketMs: Long)
    }

    /** A fresh, empty database; the store is [SOURCE]'s at [LAT], [LON] (raw device coordinates), filing under [BUCKET]. */
    protected abstract fun open(): Pair<ElapsedForecastBackfill.Store, Seed>

    @Test
    fun `coveredHours - this source at this site, any bucket, start inclusive, end exclusive`() = runBlocking {
        val (store, seed) = open()
        seed.hourlyHistory(hr(H0, lat = 37.417), snapshotBucketMs = BUCKET - 8 * HOUR)
        seed.hourlyHistory(hr(H0 + HOUR, lat = 37.418), snapshotBucketMs = BUCKET) // same-site fragment
        seed.hourlyHistory(hr(H0 + 2 * HOUR, lat = 37.467), snapshotBucketMs = BUCKET) // another town in the box
        seed.hourlyHistory(hr(H0 + 3 * HOUR, source = "OPEN_METEO"), snapshotBucketMs = BUCKET)
        seed.hourlyHistory(hr(H0 + 4 * HOUR), snapshotBucketMs = BUCKET)

        assertEquals(setOf(H0, H0 + HOUR), store.coveredHours(H0, H0 + 4 * HOUR))
    }

    @Test
    fun `inserted hours are covered afterwards, and a second backfill stores nothing`() = runBlocking {
        val (store, _) = open()
        val fetched = (0 until 3).map { hr(NOW - (5 - it) * HOUR) }

        val first = ElapsedForecastBackfill.backfill(fetched, NOW, store)
        val second = ElapsedForecastBackfill.backfill(fetched, NOW, store)

        assertEquals(3, first?.stored)
        assertEquals(ElapsedForecastBackfill.Summary(offered = 3, covered = 3, stored = 0), second)
    }

    companion object {
        const val SOURCE = "NWS"
        const val LAT = 37.4166014
        const val LON = -122.0888722
        const val HOUR = 3_600_000L
        val H0: Long = LocalDate.of(2026, 10, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 6 * HOUR
        val NOW: Long = H0 + 12 * HOUR
        val BUCKET: Long = (NOW / (4 * HOUR)) * (4 * HOUR)

        fun hr(dateTime: Long, source: String = SOURCE, lat: Double = 37.417) = HourlyForecast(
            dateTime = dateTime, temperature = 60f, condition = "Cloudy", source = source,
            fetchedAt = NOW - HOUR, locationLat = lat, locationLon = -122.089,
        )
    }
}
