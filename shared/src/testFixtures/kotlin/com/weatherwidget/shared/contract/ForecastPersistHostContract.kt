package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.DailyForecastWrite
import com.weatherwidget.shared.util.DailyHourlySummaries
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.shared.util.PredictionDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * What [ForecastPersistRun.Host] reads and writes, on Android's Room Host and desktop's SQLite Host
 * (`plans/261024-shared-forecast-persist-rules.md`, phase 3): every read is this source on the site's
 * exact quantized key — a jitter fragment's rows are another site's prior (decision D1) — a written row
 * keeps every field and its own `fetchedAt`, and a re-stamp moves `batchFetchedAt` only.
 */
abstract class ForecastPersistHostContract {
    /**
     * A fresh, empty database; the returned function opens a Host on it for any source and raw
     * coordinates, its snapshot label fixed at [LABEL].
     */
    protected abstract fun open(): (sourceId: String, lat: Double, lon: Double) -> ForecastPersistRun.Host<*>

    @Test
    fun `hourly reads are this source on this site's exact key`() = runBlocking {
        val hostOn = open()
        val host = hostOn(SOURCE, LAT, LON)
        host.writeHourly(listOf(hr(T0 + HOUR, temp = 60f)))
        hostOn(SOURCE, FRAGMENT_LAT, LON).writeHourly(listOf(hr(T0 + 2 * HOUR, temp = 61f)))
        hostOn("OPEN_METEO", LAT, LON).writeHourly(listOf(hr(T0 + 3 * HOUR, temp = 62f)))

        assertEquals(listOf(T0 + HOUR), host.storedHourly(T0, T0 + 10 * HOUR).map { it.dateTime })
    }

    @Test
    fun `a written hour keeps every field and its own fetchedAt, and a rewrite replaces it`() = runBlocking {
        val host = open()(SOURCE, LAT, LON)
        host.writeHourly(listOf(hr(T0 + HOUR, fetchedAt = T0)))
        val rewrite = hr(T0 + HOUR, temp = 55f, fetchedAt = T0 + HOUR).copy(
            precipProbability = 70, cloudCover = 90, cloudCoverLow = 80, cloudCoverMid = 30, cloudCoverHigh = 10, precipAmountMm = 1.5f,
        )
        host.writeHourly(listOf(rewrite))

        val stored = host.storedHourly(T0, T0 + 2 * HOUR).single()
        assertEquals(
            listOf(55f, "Cloudy", 70, 90, 80, 30, 10, 1.5f, T0 + HOUR),
            with(stored) { listOf(temperature, condition, precipProbability, cloudCover, cloudCoverLow, cloudCoverMid, cloudCoverHigh, precipAmountMm, fetchedAt) },
        )
    }

    @Test
    fun `a snapshot is filed at this site, for this source`() = runBlocking {
        val hostOn = open()
        val host = hostOn(SOURCE, LAT, LON)
        host.writeSnapshot(LABEL, listOf(hr(T0 + HOUR), hr(T0 + 2 * HOUR)))

        assertEquals(setOf(T0 + HOUR, T0 + 2 * HOUR), host.elapsedStore(LABEL, T0).coveredHours(T0, T0 + 3 * HOUR))
        assertEquals(emptySet<Long>(), hostOn("OPEN_METEO", LAT, LON).elapsedStore(LABEL, T0).coveredHours(T0, T0 + 3 * HOUR))
    }

    @Test
    fun `daily reads are this source on this site's exact key, both dates included`() = runBlocking {
        val hostOn = open()
        val host = hostOn(SOURCE, LAT, LON)
        host.writeDaily((0L..3L).map { row(DAY0.plusDays(it)) }, emptyList(), T0)
        hostOn(SOURCE, FRAGMENT_LAT, LON).writeDaily(listOf(row(DAY0.plusDays(1), high = 99f)), emptyList(), T0)
        hostOn("OPEN_METEO", LAT, LON).writeDaily(listOf(row(DAY0.plusDays(2), high = 98f)), emptyList(), T0)

        val stored = host.storedDaily(DAY0.plusDays(1), DAY0.plusDays(2))
        assertEquals(listOf(DAY0.plusDays(1), DAY0.plusDays(2)), stored.map { it.date }.sorted())
        assertEquals(setOf(70f), stored.map { it.values.highTemp }.toSet())
    }

    @Test
    fun `a written day keeps every value, dated today and stamped now`() = runBlocking {
        val host = open()(SOURCE, LAT, LON)
        val full = row(DAY0.plusDays(1))
        host.writeDaily(listOf(full), emptyList(), T0)

        val stored = host.storedDaily(DAY0, DAY0.plusDays(3)).single()
        assertEquals(full.values, stored.values)
        assertEquals(listOf(PredictionDate.epochMs(T0), T0, T0), listOf(stored.dateOfPrediction, stored.batchFetchedAt, stored.fetchedAt))
    }

    @Test
    fun `a re-stamp moves batchFetchedAt only, writing no row`() = runBlocking {
        val host = open()(SOURCE, LAT, LON)
        host.writeDaily(listOf(row(DAY0.plusDays(1))), emptyList(), T0)
        restampAll(host, T0 + HOUR)

        val stored = host.storedDaily(DAY0, DAY0.plusDays(3)).single()
        assertEquals(listOf(T0 + HOUR, T0), listOf(stored.batchFetchedAt, stored.fetchedAt))
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun restampAll(host: ForecastPersistRun.Host<*>, nowMs: Long) {
        val typed = host as ForecastPersistRun.Host<Any?>
        typed.writeDaily(emptyList(), typed.storedDaily(DAY0, DAY0.plusDays(7)).map { it.ref }, nowMs)
    }

    companion object {
        const val SOURCE = "NWS"
        const val LAT = 37.4166014
        const val LON = -122.0888722
        /** Quantizes to the next key (~110 m off): the same site to a reader, another key to a write. */
        const val FRAGMENT_LAT = 37.4182
        const val LABEL = 1_792_790_000_000L
        const val HOUR = 3_600_000L
        const val T0 = 1_792_800_000_000L
        val DAY0: LocalDate = PredictionDate.of(T0)

        fun hr(at: Long, temp: Float = 60f, fetchedAt: Long = T0) =
            HourlyForecast(dateTime = at, temperature = temp, condition = "Cloudy", source = SOURCE, fetchedAt = fetchedAt)

        fun row(date: LocalDate, high: Float = 70f) = DailyForecastWrite.Row(
            date,
            DailyForecastWrite.Values(
                highTemp = high,
                lowTemp = 50f,
                condition = "Rain",
                iconToken = "rain",
                precipProbability = 60,
                precipAmountMm = 2.5f,
                daytimePrecipProbability = 55,
                nighttimePrecipProbability = 40,
                hindcastHighTemp = 71f,
                hindcastLowTemp = null,
                summary = DailyHourlySummaries.Summary(noonCloudPercent = 80, dayPrecipMax = 55, nightPrecipMax = 40, dayPrecipPeakAt = T0 + 5 * HOUR),
            ),
        )
    }
}
