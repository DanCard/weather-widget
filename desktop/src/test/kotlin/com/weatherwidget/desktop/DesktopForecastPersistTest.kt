package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ForecastHorizonCadence.Horizon
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * [DesktopWeatherRepository.refresh] + [DesktopWeatherDao]: a fetch's rows go through the `:shared`
 * write rules, as on Android (`plans/261024-shared-forecast-persist-rules.md`) — a field the reply
 * lacks keeps the stored value, every written hour is stamped as confirmed now, and an unchanged daily
 * row is re-stamped rather than stored again.
 */
@Category(ShortDuration::class)
class DesktopForecastPersistTest {
    private val tempDbPath = Files.createTempFile("weather-forecast-persist-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val service = mockk<WeatherApiClient>(relaxed = true)
    private val om = WeatherSource.OPEN_METEO.id
    private val lat = 37.4220
    private val lon = -122.0841
    private val hour = 3_600_000L
    private val today = LocalDate.now()
    private val now = today.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val firstHour = (now / hour + 1) * hour

    private fun hourly(cloud: Int?) = (0 until 3).map {
        HourlyForecast(dateTime = firstHour + it * hour, temperature = 60f, condition = "Cloudy", cloudCover = cloud)
    }

    private val daily = (1..3).map { DailyForecast(today.plusDays(it.toLong()).toString(), 70f, 50f, "Cloudy") }

    private suspend fun refresh(hours: List<HourlyForecast>, at: Long) {
        coEvery { service.fetchForecast(false) } returns RawFetch(daily = daily, hourly = hours)
        DesktopWeatherRepository(service, dao, lat, lon, om).refresh(now = at, reason = "test", horizon = Horizon.LONG)
    }

    private fun storedHours() =
        dao.getHourlyForecasts(LocationMatch.quantize(lat), LocationMatch.quantize(lon), om, firstHour, firstHour + 2 * hour)

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `a reply without cloud keeps the stored cloud, and every hour is stamped as confirmed now`() = runTest {
        refresh(hourly(cloud = 40), now)
        refresh(hourly(cloud = null), now + hour / 2)

        val stored = storedHours()
        assertEquals(3, stored.size)
        assertEquals("the reply lacked cloud; the stored value stays", listOf(40, 40, 40), stored.map { it.cloudCover })
        // The fetch's own clock, as on Android — not the wall clock at the write.
        assertEquals("fetchedAt = the confirming fetch", setOf(now + hour / 2), stored.map { it.fetchedAt }.toSet())
    }

    @Test
    fun `an unchanged daily row is re-stamped, not stored again`() = runTest {
        refresh(hourly(cloud = 40), now)
        refresh(hourly(cloud = 40), now + hour)

        database.getConnection().use { conn ->
            conn.prepareStatement("SELECT targetDate, COUNT(*) AS n, MAX(lastConfirmedAt) AS b FROM forecasts WHERE source = ? GROUP BY targetDate").use { stmt ->
                stmt.setString(1, om)
                val rs = stmt.executeQuery()
                var days = 0
                while (rs.next()) {
                    days++
                    assertEquals("one row per day", 1, rs.getInt("n"))
                    assertEquals("re-stamped by the second fetch", now + hour, rs.getLong("b"))
                }
                assertEquals(3, days)
            }
        }
    }
}
