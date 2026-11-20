package com.weatherwidget.ui

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.DaySourceComparison
import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.handlers.GraphDataLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/**
 * The widget header's rain % and the Forecast Comparison "All sources" Now column read the same Room
 * fetches the same way: the header through [GraphDataLoader.loadCurrentTempInputHourlyForecasts],
 * [GraphDataLoader.nowCentred] and [HeaderContent.build] (as `DailyHeaderResolver`), the table through
 * [RoomDaySourceComparisonHost] and the shared runner. Desktop half: `DesktopHeaderRainParityTest`.
 *
 * Desktop 2026-11-20: Tomorrow.io's 10:42 fetch hindcast 0 % for the started 10am hour its 09:02 fetch
 * had at 47 %; the header (stitched) read 13 % and Now (newest row per hour) 0 %.
 * plans/261120-all-sources-header-rain-column.md
 */
@RunWith(RobolectricTestRunner::class)
@Category(MediumDuration::class)
class HeaderRainParityRoboTest {
    private lateinit var db: WeatherDatabase

    private val lat = 37.42
    private val lon = -122.08
    private val source = WeatherSource.TOMORROW_IO
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val now = today.atTime(10, 40)
    private val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    @Before
    fun setUp() {
        db = TestDatabase.create()
    }

    @After
    fun tearDown() = db.close()

    private fun hourMs(h: Int) = today.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** One fetch: a row per hour keyed by its fetch time (the entity's default group key). */
    private fun seedFetch(fetchedAt: Long, chanceAt: (Int) -> Int) = runBlocking {
        db.hourlyForecastDao().insertAll(
            (6..23).map { h ->
                HourlyForecastEntity(
                    dateTime = hourMs(h), locationLat = lat, locationLon = lon, temperature = 52f, condition = "Cloudy",
                    source = source.id, precipProbability = chanceAt(h), fetchedAt = fetchedAt,
                )
            },
        )
    }

    private fun headerChance(lookahead: Int): Int? = runBlocking {
        val input = GraphDataLoader.loadCurrentTempInputHourlyForecasts(
            db.hourlyForecastDao(), db.hourlyForecastHistoryDao(), source, lat, lon, now,
        )
        val rows = GraphDataLoader.nowCentred(input, now).map(HourlyForecastEntity::toHourlyForecast)
        HeaderContent.build(
            view = HeaderContent.View.DAILY, hourly = rows, displaySourceId = source.id, dailyFallback = null,
            fallbackDailyPrecip = null, currentTemp = null, isStaleEstimate = false, numColumns = 5,
            deltaFromYesterday = null, now = now, centerTime = now, lat = lat, lon = lon, useCelsius = false,
            widthDp = 400, rainNow = null, rainLookaheadHours = lookahead, precipHourly = rows,
        ).computedPrecipProbability
    }

    private fun nowColumn(lookahead: Int): Int? = runBlocking {
        DaySourceComparison.load(
            RoomDaySourceComparisonHost(
                db.forecastDao(), db.dailyHistoryDao(), db.hourlyForecastHistoryDao(), listOf(source),
            ) { _, _, _ -> emptyMap() },
            today, lat, lon, now, zone, use24HourClock = false, rainLookaheadHours = lookahead,
        ).single().headerRainPercent
    }

    @Test
    fun `header rain and the Now column agree when a later fetch hindcasts a started hour`() {
        runBlocking {
            db.forecastDao().insertForecast(
                ForecastEntity(
                    targetDate = today.toEpochDay() * 86_400_000L, dateOfPrediction = today.toEpochDay() * 86_400_000L,
                    locationLat = lat, locationLon = lon, highTemp = 56f, lowTemp = 46f, condition = "Cloudy",
                    source = source.id, fetchedAt = nowMs - 2 * minute, lastConfirmedAt = nowMs - 2 * minute,
                ),
            )
        }
        // Before the hour: 60 % at 10am. After it started: a hindcast of 0 % everywhere.
        seedFetch(hourMs(9) + 2 * minute) { h -> if (h == 10) 60 else 0 }
        seedFetch(nowMs - 2 * minute) { 0 }

        for (lookahead in listOf(1, 4)) {
            val header = headerChance(lookahead)
            val table = nowColumn(lookahead)
            println("HeaderRainParityRoboTest lookahead=${lookahead}h header=$header now=$table")
            assertEquals("header and Now at ${lookahead}h", header, table)
            // 60 % at 10am fading to 0 % at 11am: 20 % at 10:40. The newest fetch alone reads 0.
            assertEquals(20, table)
        }
    }
}
