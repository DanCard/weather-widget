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
 * (`plans/261024-shared-forecast-persist-rules.md`, phase 3): hourly reads are this source on the site's
 * exact quantized key; daily reads add the same site's jitter keys flagged `exactKey = false`, which only
 * today's cutoff guard reads (`plans/261119-hindcast-guard-same-site-priors.md`). A written row keeps
 * every field and its own `fetchedAt`, and a re-stamp moves `lastConfirmedAt` only.
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
    fun `daily reads are this source on this site, jitter keys flagged, both dates included`() = runBlocking {
        val hostOn = open()
        val host = hostOn(SOURCE, LAT, LON)
        host.writeDaily((0L..3L).map { row(DAY0.plusDays(it)) }, emptyList(), T0)
        hostOn(SOURCE, FRAGMENT_LAT, LON).writeDaily(listOf(row(DAY0.plusDays(1), high = 99f)), emptyList(), T0)
        hostOn(SOURCE, OTHER_SITE_LAT, LON).writeDaily(listOf(row(DAY0.plusDays(1), high = 97f)), emptyList(), T0)
        hostOn("OPEN_METEO", LAT, LON).writeDaily(listOf(row(DAY0.plusDays(2), high = 98f)), emptyList(), T0)

        val stored = host.storedDaily(DAY0.plusDays(1), DAY0.plusDays(2))
        println("daily reads: ${stored.map { "${it.date} ${it.values.highTemp} exact=${it.exactKey}" }}")
        val exact = stored.filter { it.exactKey }
        assertEquals(listOf(DAY0.plusDays(1), DAY0.plusDays(2)), exact.map { it.date }.sorted())
        assertEquals(setOf(70f), exact.map { it.values.highTemp }.toSet())
        assertEquals("the jitter key only, not the other site", listOf(99f), stored.filterNot { it.exactKey }.map { it.values.highTemp })
    }

    /**
     * Fold 4, OWM 2026-11-19, through the real store: a fix 0.001° off after 16:00 is a new key. Its
     * evening-only high must not replace the high held by the same site's 13:00 row on the old key.
     */
    @Test
    fun `after the cutoff a jitter key's fetch keeps the same site's held extremes`() = runBlocking {
        val hostOn = open()
        val zone = java.time.ZoneId.systemDefault()
        val today = LocalDate.parse("2026-11-19")
        val at = { h: Int -> today.atTime(h, 0).atZone(zone).toInstant().toEpochMilli() }
        val daily = { high: Float, low: Float -> listOf(com.weatherwidget.data.model.DailyForecast(today.toString(), high, low, "Rain")) }
        ForecastPersistRun(hostOn(SOURCE, LAT, LON))
            .persist(ForecastPersistRun.Fetch(hourly = emptyList(), daily = daily(53.22f, 50.09f), keep = null), at(13), zone)

        @Suppress("UNCHECKED_CAST")
        val moved = hostOn(SOURCE, FRAGMENT_LAT, LON) as ForecastPersistRun.Host<Any?>
        val plan = ForecastPersistRun(moved)
            .persist(ForecastPersistRun.Fetch(hourly = emptyList(), daily = daily(53.13f, 52.88f), keep = null), at(17), zone)
        println("jitter-key plan: ${plan}")

        val written = moved.storedDaily(today, today).filter { it.exactKey }.single().values
        println("jitter-key row: high=${written.highTemp} low=${written.lowTemp} hindcast=${written.hindcastHighTemp}/${written.hindcastLowTemp}")
        assertEquals(listOf(53.22f, 50.09f, 53.13f, 52.88f), listOf(written.highTemp, written.lowTemp, written.hindcastHighTemp, written.hindcastLowTemp))
    }

    @Test
    fun `a written day keeps every value, dated today and stamped now`() = runBlocking {
        val host = open()(SOURCE, LAT, LON)
        val full = row(DAY0.plusDays(1))
        host.writeDaily(listOf(full), emptyList(), T0)

        val stored = host.storedDaily(DAY0, DAY0.plusDays(3)).single()
        assertEquals(full.values, stored.values)
        assertEquals(listOf(PredictionDate.epochMs(T0), T0, T0), listOf(stored.dateOfPrediction, stored.lastConfirmedAt, stored.fetchedAt))
    }

    @Test
    fun `a re-stamp moves lastConfirmedAt only, writing no row`() = runBlocking {
        val host = open()(SOURCE, LAT, LON)
        host.writeDaily(listOf(row(DAY0.plusDays(1))), emptyList(), T0)
        restampAll(host, T0 + HOUR)

        val stored = host.storedDaily(DAY0, DAY0.plusDays(3)).single()
        assertEquals(listOf(T0 + HOUR, T0), listOf(stored.lastConfirmedAt, stored.fetchedAt))
    }

    /**
     * NWS 2026-11-17 through the real store: the 01:10 fetch forecast Patchy Fog at 06, 07 and 09; the
     * 10:09 download began at 01:00, all clear. Its fog is judged on the stored newest-per-hour rows
     * (00:00 still the 01:10 fetch's, the elapsed 01-09 filed by this fetch), which must cover the
     * 00:00-12:00 window and clear the old fog. Until the read started at the fog window, it began at
     * 08:00 and the old fog stayed. plans/261117-today-fog-stale-forecast-and-nws-metar-history-fill.md
     */
    @Test
    fun `a later all-clear fetch clears today's forecast fog through the stored hours`() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val host = open()(SOURCE, LAT, LON) as ForecastPersistRun.Host<Any?>
        val zone = java.time.ZoneId.systemDefault()
        val today = LocalDate.parse("2026-11-17")
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val run = ForecastPersistRun(host)
        val daily = { high: Float -> listOf(com.weatherwidget.data.model.DailyForecast(today.toString(), high, 42f, "Sunny")) }

        val foggy = setOf(6, 7, 9)
        val first = (0..23).map { i ->
            HourlyForecast(midnight + i * HOUR, 44f + i, if (i in foggy) "Patchy Fog" else "Mostly Clear", source = SOURCE)
        }
        run.persist(ForecastPersistRun.Fetch(hourly = first, daily = daily(60f), keep = null), midnight + 70 * 60_000L, zone)
        val before = host.storedDaily(today, today).single().values.summary.fog
        println("before: $before")
        assertEquals(com.weatherwidget.shared.fog.FogLevel.LIGHT, before?.level)

        val clear = (1..23).map { i -> HourlyForecast(midnight + i * HOUR, 44f + i, "Sunny", source = SOURCE, visibilityMi = 10f) }
        run.persist(ForecastPersistRun.Fetch(hourly = clear, daily = daily(61f), keep = null), midnight + (10 * 60 + 9) * 60_000L, zone)

        // Every fetch keeps its own row (history of forecasts); the newest is what today draws.
        val after = host.storedDaily(today, today).maxBy { it.fetchedAt }.values.summary.fog
        println("after: $after")
        assertEquals(com.weatherwidget.shared.fog.MorningFog.NONE, after)
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
        /** ~1.5 km off: a different site, never a prior. */
        const val OTHER_SITE_LAT = 37.4300
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
