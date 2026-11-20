package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.DaySourceComparison
import com.weatherwidget.test.category.MediumDuration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

/**
 * The header's rain % and the Forecast Comparison "All sources" Now column read the same stored
 * fetches the same way: the header through [DesktopWeatherRepository.loadCached] and
 * [desktopHeaderContent], the table through [DesktopDaySourceComparisonHost] and the shared runner,
 * over one real SQLite database.
 *
 * Desktop 2026-11-20: Tomorrow.io's 10:42 fetch hindcast 0 % for the started 10am hour its 09:02 fetch
 * had at 47 %; the header (stitched) read 13 % and Now (newest row per hour) 0 %.
 * plans/261120-all-sources-header-rain-column.md
 */
@Category(MediumDuration::class)
class DesktopHeaderRainParityTest {
    private lateinit var path: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    private val lat = 37.42
    private val lon = -122.08
    private val source = WeatherSource.TOMORROW_IO
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val now = today.atTime(10, 40)
    private val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    @Before
    fun setup() {
        path = Files.createTempFile("header-rain-parity", ".db")
        db = DesktopWeatherDatabase(path).apply { initialize() }
        dao = DesktopWeatherDao(db)
    }

    @After
    fun teardown() {
        Files.deleteIfExists(path)
    }

    private fun insert(sql: String, vararg args: Any?) {
        db.getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                args.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
                stmt.executeUpdate()
            }
        }
    }

    private fun hourMs(h: Int) = today.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** Every fetch as the fetch writers leave it: one row per hour, keyed by its fetch time. */
    private fun seedFetch(fetchedAt: Long, chanceAt: (Int) -> Int) {
        for (h in 6..23) {
            insert(
                "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, " +
                    "precipProbability, fetchedAt, timestampToGroupPredictions) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                hourMs(h), lat, lon, 52f, "Cloudy", source.id, chanceAt(h), fetchedAt, fetchedAt,
            )
        }
    }

    private fun headerChance(lookahead: Int): Int? = runBlocking {
        val repository = DesktopWeatherRepository(DesktopWeatherService(lat, lon, source.id), dao, lat, lon, source.id)
        val snapshot = repository.loadCached(nowMs) ?: error("no cached snapshot")
        desktopHeaderContent(
            forecast = snapshot, displaySourceId = source.id, viewMode = ViewMode.DAILY, currentTemp = null,
            deltaFromYesterday = null, now = now, centerTime = now, lat = lat, lon = lon, useCelsius = false,
            widthDp = 600, rainNow = null, rainLookaheadHours = lookahead,
        ).computedPrecipProbability
    }

    private fun nowColumn(lookahead: Int): Int? = runBlocking {
        DaySourceComparison.load(
            DesktopDaySourceComparisonHost(dao, listOf(source), emptyMap()), today, lat, lon, now, zone,
            use24HourClock = false, rainLookaheadHours = lookahead,
        ).single().headerRainPercent
    }

    @Test
    fun `header rain and the Now column agree when a later fetch hindcasts a started hour`() {
        insert(
            "INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, condition, " +
                "isClimateNormal, source, lastConfirmedAt, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, 'Cloudy', 0, ?, ?, ?)",
            today.toEpochDay() * 86_400_000L, today.toEpochDay() * 86_400_000L, lat, lon, 56f, 46f, source.id,
            nowMs - 2 * minute, nowMs - 2 * minute,
        )
        // Before the hour: 60 % at 10am. After it started: a hindcast of 0 % everywhere.
        seedFetch(hourMs(9) + 2 * minute) { h -> if (h == 10) 60 else 0 }
        seedFetch(nowMs - 2 * minute) { 0 }

        for (lookahead in listOf(1, 4)) {
            val header = headerChance(lookahead)
            val table = nowColumn(lookahead)
            println("DesktopHeaderRainParityTest lookahead=${lookahead}h header=$header now=$table")
            assertEquals("header and Now at ${lookahead}h", header, table)
            // 60 % at 10am fading to 0 % at 11am: 20 % at 10:40. The newest fetch alone reads 0.
            assertEquals(20, table)
        }
    }
}
