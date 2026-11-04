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
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md): an insert files every elapsed hour.
 */
abstract class ElapsedForecastBackfillStoreContract {
    /** Writes a `hourly_forecasts` row as given. */
    fun interface Seed {
        fun hourlyHistory(row: HourlyForecast, snapshotBucketMs: Long)
    }

    /** A fresh, empty database; the store is [SOURCE]'s at [LAT], [LON] (raw device coordinates), filing under [BUCKET]. */
    protected abstract fun open(): Pair<ElapsedForecastBackfill.Store, Seed>

    /** No coverage check since 2026-11-04 (plans/261104-file-every-elapsed-hour.md): every fetch files its elapsed hours. */
    @Test
    fun `every backfill files every elapsed hour, already-stored ones included`() = runBlocking {
        val (store, seed) = open()
        seed.hourlyHistory(hr(NOW - 5 * HOUR), snapshotBucketMs = BUCKET - 8 * HOUR)
        val fetched = (0 until 3).map { hr(NOW - (5 - it) * HOUR) }

        val first = ElapsedForecastBackfill.backfill(fetched, NOW, store)
        val second = ElapsedForecastBackfill.backfill(fetched, NOW + HOUR / 2, store)

        assertEquals(ElapsedForecastBackfill.Summary(offered = 3, stored = 3), first)
        assertEquals(ElapsedForecastBackfill.Summary(offered = 3, stored = 3), second)
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
