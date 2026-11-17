package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.daily.DailyColumnBuilder
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [DesktopWeatherRepository.refresh] → [DesktopWeatherDao] on real SQLite → [DailyColumnBuilder]:
 * Google's daily windows store their combined chance beside the max, and the daily view shows it once
 * the max reaches 85% — on days past the 72 h hourly store too. A v42 database gains the columns.
 * plans/261117-google-combined-daily-rain-chance.md
 */
@Category(ShortDuration::class)
class DesktopGoogleCombinedRainStorageTest {
    private val path = Files.createTempFile("weather-combined-rain-test", ".db")
    private val database = DesktopWeatherDatabase(path).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val service = mockk<WeatherApiClient>(relaxed = true)
    private val google = WeatherSource.GOOGLE_WEATHER.id
    private val lat = 37.4220
    private val lon = -122.0841
    private val hour = 3_600_000L
    private val zone = ZoneId.systemDefault()
    private val now = (System.currentTimeMillis() / hour) * hour + 10 * 60_000L
    private val today = LocalDate.now(zone)
    private val days = (0 until 10).map { today.plusDays(it.toLong()) }

    /** Day 1 and day 5: 85% from 8am to noon; every other hour the 10% drizzle floor. */
    private val stormDays = setOf(today.plusDays(1), today.plusDays(5))

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun download(): List<HourlyForecast> =
        generateSequence(at(today, 0)) { it + hour }.takeWhile { it < at(today.plusDays(9), 0) }.map { ms ->
            val local = java.time.Instant.ofEpochMilli(ms).atZone(zone)
            val storm = local.toLocalDate() in stormDays && local.hour in 8..12
            HourlyForecast(
                dateTime = ms, temperature = 58f, condition = "Rain",
                precipProbability = if (storm) 85 else 10, precipAmountMm = if (storm) 8f else 0f,
            )
        }.toList()

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(path)
    }

    @Test
    fun `a Google fetch stores each window's combined chance and the daily view shows it from 85 percent`() = runTest {
        val daily = days.map { DailyForecast(it.toString(), 60f, 50f, "Rain", precipProbability = 85) }
        coEvery { service.fetchForecast() } returns RawFetch(daily = daily, hourly = download())
        DesktopWeatherRepository(service, dao, lat, lon, google)
            .refresh(now = now, reason = "test", horizon = com.weatherwidget.data.remote.ForecastHorizonCadence.Horizon.LONG)

        val rows = dao.getDailyForecasts(lat, lon, google).associateBy { LocalDate.parse(it.date) }
        // Day 5 is past the 72 h store: the daily view reads the row alone, as it does far days.
        listOf(today.plusDays(1), today.plusDays(2), today.plusDays(5)).forEach { day ->
            val row = rows.getValue(day)
            val column = DailyColumnBuilder.build(
                date = day, label = "x", today = today, now = LocalDateTime.now(zone), forecast = row,
                actual = null, snapshots = emptyList(), hourly = emptyList(), currentTemp = null,
                displaySourceId = google, centerLat = lat, centerLon = lon, use24HourClock = false,
            )
            println("$day max=${row.hourlyDayPrecipMax} combined=${row.hourlyDayPrecipCombined} night=${row.hourlyNightPrecipMax}/${row.hourlyNightPrecipCombined} shown=${column.dayPrecipProbability}/${column.nightPrecipProbability}")
            if (day in stormDays) {
                assertEquals(85, row.hourlyDayPrecipMax)
                assertEquals(100, row.hourlyDayPrecipCombined)
                assertEquals(100, column.dayPrecipProbability)
            } else {
                assertEquals(10, row.hourlyDayPrecipMax)
                assertEquals("twelve 10% hours", 72, row.hourlyDayPrecipCombined)
                assertEquals("below 85% the max stands", 10, column.dayPrecipProbability)
            }
            assertEquals("a drizzle-floor night keeps its max", 10, column.nightPrecipProbability)
        }
    }

    @Test
    fun `a v42 database gains the columns and keeps its rows`() {
        val day = today.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, condition, " +
                        "isClimateNormal, source, lastConfirmedAt, fetchedAt, hourlyDayPrecipMax) " +
                        "VALUES ($day, $day, $lat, $lon, 60.0, 50.0, 'Rain', 0, '$google', 1, 1, 85)",
                )
                DesktopWeatherDatabase.FORECAST_PRECIP_COMBINED_COLUMNS.forEach {
                    st.execute("ALTER TABLE forecasts DROP COLUMN $it")
                }
                st.execute("PRAGMA user_version = 42")
            }
        }

        DesktopWeatherDatabase(path).initialize()

        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, it.getInt(1)) }
                val columns = mutableSetOf<String>()
                st.executeQuery("PRAGMA table_info(forecasts)").use { while (it.next()) columns += it.getString("name") }
                assertTrue(columns.containsAll(DesktopWeatherDatabase.FORECAST_PRECIP_COMBINED_COLUMNS))
                st.executeQuery("SELECT hourlyDayPrecipMax, hourlyDayPrecipCombined FROM forecasts").use {
                    assertTrue(it.next())
                    assertEquals(85, it.getInt(1))
                    it.getInt(2); assertTrue("old rows carry no combined chance", it.wasNull())
                }
            }
        }
    }
}
