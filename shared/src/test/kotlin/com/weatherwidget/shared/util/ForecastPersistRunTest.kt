package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.remote.ForecastHorizonCadence
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

    private class FakeHost(var label: Long? = 42L) : ForecastPersistRun.Host<String> {
        override val sourceId = "OPEN_METEO"
        override val latitude = 37.4166
        override val longitude = -122.0889
        val live = sortedMapOf<Long, HourlyForecast>()
        val snapshots = mutableListOf<Pair<Long, HourlyForecast>>()
        val backfilled = mutableListOf<Pair<Long, HourlyForecast>>()
        val daily = mutableListOf<DailyForecastWrite.Stored<String>>()
        val restamped = mutableListOf<String>()
        var labelAsks = 0
        var dailyReads = 0

        override suspend fun storedHourly(startMs: Long, endMs: Long) = live.values.filter { it.dateTime in startMs..endMs }
        override suspend fun writeHourly(rows: List<HourlyForecast>) = rows.forEach { live[it.dateTime] = it }
        override suspend fun snapshotLabel(nowMs: Long) = label.also { labelAsks++ }
        override suspend fun writeSnapshot(label: Long, rows: List<HourlyForecast>) { rows.forEach { snapshots += label to it } }
        override fun elapsedStore(label: Long, nowMs: Long) = object : ElapsedForecastBackfill.Store {
            override suspend fun coveredHours(startMs: Long, endMs: Long) =
                (snapshots + backfilled).map { it.second.dateTime }.filter { it in startMs until endMs }.toSet()
            override suspend fun insert(rows: List<HourlyForecast>) { rows.forEach { backfilled += label to it } }
        }
        override suspend fun storedDaily(from: LocalDate, to: LocalDate) = daily.filter { it.date in from..to }.also { dailyReads++ }
        override suspend fun writeDaily(write: List<DailyForecastWrite.Row>, restamp: List<String>, nowMs: Long) {
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
    fun `one label, asked once, files the snapshot and the elapsed hours`() {
        val host = FakeHost(label = 42L)
        val result = persist(host, ForecastPersistRun.Fetch(hourly = hours(-5, 3), daily = null, keep = null))

        assertEquals(1, host.labelAsks)
        assertEquals((0..3).map { hour0 + it * h }, host.live.keys.toList())
        assertEquals(setOf(42L), host.snapshots.map { it.first }.toSet())
        assertEquals((0..3).map { hour0 + it * h }, host.snapshots.map { it.second.dateTime })
        assertEquals(setOf(42L), host.backfilled.map { it.first }.toSet())
        // Live and backfill meet at now − ELAPSED_BOUNDARY_MS (09:20): 09:00 is history, 10:00 live.
        assertEquals((-5..-1).map { hour0 + it * h }, host.backfilled.map { it.second.dateTime })
        assertEquals(42L, result.snapshotLabel)
    }

    @Test
    fun `no label - no snapshot and no backfill, the live hours still written`() {
        val host = FakeHost(label = null)
        val result = persist(host, ForecastPersistRun.Fetch(hourly = hours(-5, 3), daily = null, keep = null))

        assertEquals(4, host.live.size)
        assertTrue(host.snapshots.isEmpty())
        assertTrue(host.backfilled.isEmpty())
        assertNull(result.elapsed)
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
    fun `a SHORT fetch stores its first three days only`() {
        val host = FakeHost()
        persist(host, ForecastPersistRun.Fetch(hourly = emptyList(), daily = (0L..7L).map { day(it) }, keep = null, horizon = ForecastHorizonCadence.Horizon.SHORT))

        assertEquals((0L..2L).map { today.plusDays(it) }, host.daily.map { it.date })
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
    fun `an unchanged day is re-stamped, a changed one written`() {
        val host = FakeHost()
        persist(host, ForecastPersistRun.Fetch(hourly = emptyList(), daily = listOf(day(1), day(2)), keep = null))
        host.restamped.clear()

        val result = persist(host, ForecastPersistRun.Fetch(hourly = emptyList(), daily = listOf(day(1), day(2, high = 75f)), keep = null))

        assertEquals(1, result.dailyRestamped)
        assertEquals(1, result.dailyWritten)
        assertEquals(listOf("${today.plusDays(1)}@$now"), host.restamped)
    }
}
