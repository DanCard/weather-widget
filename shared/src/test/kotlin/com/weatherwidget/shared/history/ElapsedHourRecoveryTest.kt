package com.weatherwidget.shared.history

import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.shared.util.DailyForecastWrite
import com.weatherwidget.shared.util.ForecastPersistRun
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Desktop NWS, 2026-11-04 (plans/261104-file-every-elapsed-hour.md): last fetch 18:45 (1am 5%), then
 * asleep; the 04:00 refresh's gridpoint carried 22:00–04:00 at 25% as elapsed hours. Before the fix
 * those hours were dropped because 18:45 rows existed, so the desktop kept 5% while the Fold drew 25%.
 */
@Category(ShortDuration::class)
class ElapsedHourRecoveryTest {
    private val source = "NWS"
    private val lat = 37.417
    private val lon = -122.089
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.parse("2026-11-04")

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** One append-only table, as both platforms store `hourly_forecasts`. */
    private inner class Store : ForecastPersistRun.Host<String> {
        override val sourceId = source
        override val latitude = lat
        override val longitude = lon
        val rows = mutableListOf<HourlyForecast>()

        override suspend fun storedHourly(startMs: Long, endMs: Long) =
            rows.filter { it.dateTime in startMs..endMs }.groupBy { it.dateTime }.values.map { hour -> hour.maxBy { it.fetchedAt } }
        override suspend fun writeHourly(rows: List<HourlyForecast>) { this.rows += rows.map { it.copy(locationLat = lat, locationLon = lon) } }
        override fun elapsedStore(label: Long, nowMs: Long) = object : ElapsedForecastBackfill.Store {
            override suspend fun insert(rows: List<HourlyForecast>) { this@Store.rows += rows.map { it.copy(source = source, locationLat = lat, locationLon = lon) } }
        }
        override suspend fun storedDaily(from: LocalDate, to: LocalDate) = emptyList<DailyForecastWrite.Stored<String>>()
        override suspend fun writeDaily(write: List<DailyForecastWrite.Row>, restamp: List<String>, nowMs: Long) = Unit
        override suspend fun log(tag: String, message: String, level: String) = Unit
    }

    private fun hr(at: Long, chance: Int) = HourlyForecast(at, 55f, "Cloudy", precipProbability = chance, source = source)

    @Test
    fun `a refresh after sleep recovers the elapsed hours it carries`() = runBlocking {
        val store = Store()
        val evening = at(18, 45, day.minusDays(1))
        val hours = (1..4).map { at(it) }
        ForecastPersistRun(store).persist(ForecastPersistRun.Fetch(hourly = hours.map { hr(it, 5) }, daily = null, keep = null), evening, zone)

        val refresh = at(4, 0) + 6_000L // 04:00:06, as logged
        ForecastPersistRun(store).persist(
            ForecastPersistRun.Fetch(
                hourly = listOf(hr(at(4), 0)),
                elapsedHourly = (1..3).map { hr(at(it), 25) },
                daily = null,
                keep = null,
            ),
            refresh,
            zone,
        )

        val stitched = HourlyForecastStitcher.stitch(store.rows, lat, lon).associate { it.dateTime to it.precipProbability }
        println("stitched 1a..4a=${hours.map { stitched[it] }}")
        assertEquals(listOf(25, 25, 25, 0), hours.map { stitched[it] })
    }
}
