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
 * Tomorrow.io on desktop, night of 2026-10-31, replayed through the persist run, stitcher and
 * night-chance resolver (`plans/261101-one-hourly-forecast-table.md`). The 02:31 fetch said 97 % for
 * 03:00; a 03:17 fetch then said 20 %. Each fetch only adds rows, so the 97 % settles.
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

    /** The one table: rows append, the newest per hour is what a write reads back. */
    private inner class Store : ForecastPersistRun.Host<String> {
        override val sourceId = source
        override val latitude = lat
        override val longitude = lon
        val rows = mutableListOf<HourlyForecast>()

        override suspend fun storedHourly(startMs: Long, endMs: Long) =
            rows.filter { it.dateTime in startMs..endMs }.groupBy { it.dateTime }.values.map { hour -> hour.maxBy { it.fetchedAt } }
        override suspend fun writeHourly(rows: List<HourlyForecast>) { this.rows += rows.map { it.copy(locationLat = lat, locationLon = lon) } }
        override fun elapsedStore(label: Long, nowMs: Long) = object : ElapsedForecastBackfill.Store {
            override suspend fun coveredHours(startMs: Long, endMs: Long) = emptySet<Long>()
            override suspend fun insert(rows: List<HourlyForecast>) = Unit
        }
        override suspend fun storedDaily(from: LocalDate, to: LocalDate) = emptyList<DailyForecastWrite.Stored<String>>()
        override suspend fun writeDaily(write: List<DailyForecastWrite.Row>, restamp: List<String>, nowMs: Long) = Unit
        override suspend fun log(tag: String, message: String, level: String) = Unit
    }

    private fun Store.fetch(nowMs: Long, vararg chanceByHour: Pair<Int, Int>) = runBlocking {
        val hours = chanceByHour.map { (hour, chance) -> HourlyForecast(early(hour), 50f, "Rain", precipProbability = chance) }
        ForecastPersistRun(this@fetch).persist(ForecastPersistRun.Fetch(hourly = hours, daily = null, keep = null), nowMs, zone)
    }

    private fun Store.replay() {
        fetch(at(night, 17, 46), 2 to 35, 3 to 30)
        fetch(at(night, 21, 44), 2 to 35, 3 to 55)
        fetch(early(2, 31), 2 to 20, 3 to 97)
        fetch(early(3, 17), 3 to 20, 4 to 0)
    }

    @Test
    fun `a fetch during the hour cannot erase the last forecast made before it`() {
        val store = Store().apply { replay() }
        val stitched = HourlyForecastStitcher.stitch(store.rows, store.rows, early(3, 30), lat, lon)

        assertEquals(97, DailyRainLabels.resolveLiveDayNightChance(source, null, null, null, stitched, night, zone).nightPrecip)
    }

    @Test
    fun `a fetch never changes a row an earlier fetch wrote`() {
        val store = Store()
        store.fetch(at(night, 17, 46), 2 to 35, 3 to 30)
        val first = store.rows.toList()

        store.fetch(early(2, 31), 2 to 20, 3 to 97)
        store.fetch(early(3, 17), 3 to 20, 4 to 0)

        assertEquals(first, store.rows.take(first.size))
    }
}
