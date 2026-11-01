package com.weatherwidget.shared.history

import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.shared.util.DailyForecastWrite
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tomorrow.io on desktop, night of 2026-10-31, replayed through the real snapshot rule, persist run,
 * stitcher and night-chance resolver (`plans/261101-every-fetch-saves-an-hourly-snapshot.md`). The 02:31
 * fetch said 97 % for 03:00, and a 03:17 fetch replaced that live row with 20 %.
 */
@Category(ShortDuration::class)
class PreHourForecastSurvivesTest {
    private val source = "TOMORROW_IO"
    private val lat = 37.417
    private val lon = -122.089
    private val zone = ZoneOffset.UTC
    private val night = LocalDate.parse("2026-10-31")

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).toInstant(zone).toEpochMilli()
    private fun early(hour: Int, minute: Int = 0) = at(night.plusDays(1), hour, minute)

    private inner class Store(val saveSnapshot: (nowMs: Long) -> Boolean) : ForecastPersistRun.Host<String> {
        override val sourceId = source
        override val latitude = lat
        override val longitude = lon
        val live = sortedMapOf<Long, HourlyForecast>()
        val history = mutableListOf<HourlyForecast>()
        private var lastLabel: Long? = null

        override suspend fun storedHourly(startMs: Long, endMs: Long) = live.values.filter { it.dateTime in startMs..endMs }
        override suspend fun writeHourly(rows: List<HourlyForecast>) = rows.forEach { live[it.dateTime] = it.sited() }
        override suspend fun snapshotLabel(nowMs: Long): Long? =
            if (saveSnapshot(nowMs)) HourlySnapshotRun.decide(nowMs, lastLabel).labelMs.also { lastLabel = it } else null
        override suspend fun writeSnapshot(label: Long, rows: List<HourlyForecast>) { history += rows.map { it.sited() } }
        override fun elapsedStore(label: Long, nowMs: Long) = object : ElapsedForecastBackfill.Store {
            override suspend fun coveredHours(startMs: Long, endMs: Long) = emptySet<Long>()
            override suspend fun insert(rows: List<HourlyForecast>) = Unit
        }
        override suspend fun storedDaily(from: LocalDate, to: LocalDate) = emptyList<DailyForecastWrite.Stored<String>>()
        override suspend fun writeDaily(write: List<DailyForecastWrite.Row>, restamp: List<String>, nowMs: Long) = Unit
        override suspend fun log(tag: String, message: String, level: String) = Unit

        private fun HourlyForecast.sited() = copy(locationLat = lat, locationLon = lon)
    }

    private fun Store.fetch(nowMs: Long, vararg chanceByHour: Pair<Int, Int>) = runBlocking {
        val hours = chanceByHour.map { (hour, chance) -> HourlyForecast(early(hour), 50f, "Rain", precipProbability = chance) }
        ForecastPersistRun(this@fetch).persist(ForecastPersistRun.Fetch(hourly = hours, daily = null, keep = null), nowMs, zone)
    }

    /** The night chance after the table in the plan, read at 03:30 as the freeze reads it. */
    private fun nightChance(saveSnapshot: (nowMs: Long) -> Boolean): Int? {
        val store = Store(saveSnapshot)
        store.fetch(at(night, 17, 46), 2 to 35, 3 to 30)
        store.fetch(at(night, 21, 44), 2 to 35, 3 to 55)
        store.fetch(early(2, 31), 2 to 20, 3 to 97)
        store.fetch(early(3, 17), 3 to 20, 4 to 0)

        val stitched = HourlyForecastStitcher.stitch(store.live.values.toList(), store.history, early(3, 30), lat, lon)
        return DailyRainLabels.resolveLiveDayNightChance(source, null, null, null, stitched, night, zone).nightPrecip
    }

    @Test
    fun `a fetch during the hour cannot erase the last forecast made before it`() {
        assertEquals(97, nightChance { true })
    }

    /** The rule this replaced: proves the replay tells the two apart. */
    @Test
    fun `with the two middle fetches unsaved the forecast is lost, as it was on 2026-11-01`() {
        val skipped = setOf(at(night, 21, 44), early(2, 31))
        assertEquals(35, nightChance { it !in skipped })
    }
}
