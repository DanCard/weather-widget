package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The one fetch→store sequence both platforms run (`plans/261024-shared-forecast-persist-rules.md`,
 * phase 3), on an in-memory Host. The rules it strings together have their own tests; these pin the
 * order and what each step is fed.
 */
@Category(ShortDuration::class)
class ForecastPersistRunTest {
    private companion object {
        val today: LocalDate = LocalDate.parse("2026-10-24")
    }

    private val zone = ZoneOffset.UTC
    private val h = 3_600_000L
    private val now = today.atTime(10, 20).toInstant(zone).toEpochMilli()
    private val hour0 = now - now % h

    /** [elapsedIntoLive]: elapsed hours land in the live table, as Room's and desktop's stores do (one `hourly_forecasts`). */
    private class FakeHost(val elapsedIntoLive: Boolean = false) : ForecastPersistRun.Host<String> {
        override val sourceId = "OPEN_METEO"
        override val latitude = 37.4166
        override val longitude = -122.0889
        val live = sortedMapOf<Long, HourlyForecast>()
        val backfilled = mutableListOf<Pair<Long, HourlyForecast>>()
        val daily = mutableListOf<DailyForecastWrite.Stored<String>>()
        val restamped = mutableListOf<String>()
        var dailyReads = 0
        /** Read at each daily write, to show the version bump comes after the writes. */
        var versionProbe: () -> Long = { 0L }
        val versionAtDailyWrite = mutableListOf<Long>()

        override suspend fun storedHourly(startMs: Long, endMs: Long) = live.values.filter { it.dateTime in startMs..endMs }
        override suspend fun writeHourly(rows: List<HourlyForecast>) = rows.forEach { live[it.dateTime] = it }
        override fun elapsedStore(label: Long, nowMs: Long) = object : ElapsedForecastBackfill.Store {
            override suspend fun insert(rows: List<HourlyForecast>) {
                rows.forEach {
                    backfilled += label to it
                    if (elapsedIntoLive) live[it.dateTime] = it
                }
            }
        }
        override suspend fun storedDaily(from: LocalDate, to: LocalDate) = daily.filter { it.date in from..to }.also { dailyReads++ }
        override suspend fun writeDaily(write: List<DailyForecastWrite.Row>, restamp: List<String>, nowMs: Long) {
            versionAtDailyWrite += versionProbe()
            restamped += restamp
            write.forEach { row ->
                daily += DailyForecastWrite.Stored(row.date, row.values, today.toEpochDay() * 86_400_000L, nowMs, nowMs, "${row.date}@$nowMs")
            }
        }
        override suspend fun log(tag: String, message: String, level: String) = Unit
    }

    private fun hr(at: Long, pop: Int? = 10) = HourlyForecast(at, 60f, "Clear", precipProbability = pop, cloudCover = 40)
    private fun hours(from: Int, to: Int) = (from..to).map { hr(hour0 + it * h) }
    private fun day(offset: Long, high: Float = 70f) = DailyForecast(today.plusDays(offset).toString(), high, 50f, "Clear")

    private fun persist(host: FakeHost, fetch: ForecastPersistRun.Fetch) = runBlocking { ForecastPersistRun(host).persist(fetch, now, zone) }

    @Test
    fun `the kept hours are written and the elapsed hours filed under the fetch time`() {
        val host = FakeHost()
        persist(host, ForecastPersistRun.Fetch(hourly = hours(-5, 3), daily = null, keep = null))

        assertEquals((0..3).map { hour0 + it * h }, host.live.keys.toList())
        assertEquals(setOf(now), host.backfilled.map { it.first }.toSet())
        // Live and backfill meet at now − ELAPSED_BOUNDARY_MS (09:20): 09:00 is history, 10:00 live.
        assertEquals((-5..-1).map { hour0 + it * h }, host.backfilled.map { it.second.dateTime })
    }

    @Test
    fun `hours a provider sends apart (NWS's grid) are offered to the backfill`() {
        val host = FakeHost()
        persist(host, ForecastPersistRun.Fetch(hourly = hours(0, 3), elapsedHourly = hours(-6, -3), daily = null, keep = null))

        assertEquals((-6..-3).map { hour0 + it * h }, host.backfilled.map { it.second.dateTime })
        assertEquals((0..3).map { hour0 + it * h }, host.live.keys.toList())
    }

    @Test
    fun `an hourly-only fetch neither reads nor writes daily rows`() {
        val host = FakeHost()
        persist(host, ForecastPersistRun.Fetch(hourly = hours(0, 3), daily = null, keep = null))

        assertEquals(0, host.dailyReads)
        assertTrue(host.daily.isEmpty())
    }

    @Test
    fun `every fetch stores all eight days`() {
        // A SHORT fetch stored three until 2026-10-25 (performance/261025-every-forecast-fetch-long.md).
        val host = FakeHost()
        persist(host, ForecastPersistRun.Fetch(hourly = emptyList(), daily = (0L..7L).map { day(it) }, keep = null))

        assertEquals((0L..7L).map { today.plusDays(it) }, host.daily.map { it.date })
    }

    @Test
    fun `a day the download misses takes its rain maximum from the hours stored now`() {
        val host = FakeHost()
        // Day 5's hours were stored by an earlier deeper fetch; this one sends 24 h only.
        val day5 = today.plusDays(5).atStartOfDay(zone).toInstant().toEpochMilli()
        (0..32).forEach { host.live[day5 + it * h] = hr(day5 + it * h, pop = if (it == 14) 80 else 5) }

        persist(host, ForecastPersistRun.Fetch(hourly = hours(0, 24), daily = listOf(day(0), day(5)), keep = null))

        assertEquals(80, host.daily.single { it.date == today.plusDays(5) }.values.summary.dayPrecipMax)
        assertEquals(80, host.daily.single { it.date == today.plusDays(5) }.values.daytimePrecipProbability)
    }

    @Test
    fun `a download starting after midnight clears an older fetch's morning fog from the stored hours`() {
        // NWS 2026-11-17: the 01:10 fetch forecast Patchy Fog at 06, 07 and 09 (top 53.5); the 10:09
        // download began at 01:00 with every hour clear. The payload cannot judge 00:00-12:00, and the
        // stored read began at 08:00, so the old fog carried forward all day.
        // plans/261117-today-fog-stale-forecast-and-nws-metar-history-fill.md
        val host = FakeHost(elapsedIntoLive = true)
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val foggy = setOf(6, 7, 9)
        (0..23).forEach { i ->
            host.live[midnight + i * h] = HourlyForecast(midnight + i * h, 44f + i, if (i in foggy) "Patchy Fog" else "Mostly Clear")
        }
        persist(host, ForecastPersistRun.Fetch(hourly = emptyList(), daily = listOf(day(0)), keep = null))
        val before = host.daily.last { it.date == today }.values.summary.fog
        println("before: $before")
        assertEquals(com.weatherwidget.shared.fog.FogLevel.LIGHT, before?.level)

        val clear = (1..23).map { i -> HourlyForecast(midnight + i * h, 44f + i, "Sunny", visibilityMi = 10f) }
        persist(host, ForecastPersistRun.Fetch(hourly = clear, daily = listOf(day(0, high = 71f)), keep = null))

        val after = host.daily.last { it.date == today }.values.summary.fog
        println("after: $after")
        assertEquals(com.weatherwidget.shared.fog.MorningFog.NONE, after)
    }

    @Test
    fun `an unchanged day is re-stamped, a changed one written`() {
        val host = FakeHost()
        persist(host, ForecastPersistRun.Fetch(hourly = emptyList(), daily = listOf(day(1), day(2)), keep = null))
        host.restamped.clear()

        val result = persist(host, ForecastPersistRun.Fetch(hourly = emptyList(), daily = listOf(day(1), day(2, high = 75f)), keep = null))

        assertEquals(1, result.dailyRestamped)
        assertEquals(1, result.dailyWritten)
        assertEquals(listOf("${today.plusDays(1)}@$now"), host.restamped)
    }

    // --- stored-data version (plans/261029-stored-data-version-invalidates-interaction-cache.md) ---

    @Test
    fun `a persist that writes bumps the stored-data version once, after its writes`() {
        val version = StoredDataVersion()
        val host = FakeHost().apply { versionProbe = version::current }
        runBlocking {
            ForecastPersistRun(host, version).persist(
                ForecastPersistRun.Fetch(hourly = hours(-5, 3), daily = (0L..7L).map { day(it) }, keep = null),
                now,
                zone,
            )
        }

        assertEquals(listOf(0L), host.versionAtDailyWrite)
        assertEquals(1L, version.current())
    }

    @Test
    fun `an empty fetch writes nothing and leaves the version alone`() {
        val version = StoredDataVersion()
        runBlocking {
            ForecastPersistRun(FakeHost(), version).persist(
                ForecastPersistRun.Fetch(hourly = emptyList(), daily = null, keep = null),
                now,
                zone,
            )
        }

        assertEquals(0L, version.current())
    }
}
