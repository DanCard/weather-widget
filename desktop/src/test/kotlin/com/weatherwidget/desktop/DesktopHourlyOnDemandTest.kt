package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * [DesktopWeatherRepository.extendHourlyFor] against a real SQLite DAO: a tapped day past Google's
 * stored 72 h is fetched once, stored, and then served from the DB.
 */
@Category(ShortDuration::class)
class DesktopHourlyOnDemandTest {
    private val tempDbPath = Files.createTempFile("weather-hourly-on-demand-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val service = mockk<WeatherApiClient>()
    private val lat = 37.4220
    private val lon = -122.0841
    private val hour = 3_600_000L
    private val zone = ZoneId.systemDefault()

    private val now = (System.currentTimeMillis() / hour) * hour
    private val target: LocalDate = LocalDate.now(zone).plusDays(5)

    private fun hours(fromMs: Long, count: Int, source: String = WeatherSource.GOOGLE_WEATHER.id) =
        (0 until count).map { HourlyForecast(fromMs + it * hour, 60f + it % 10, "Clear", source = source, fetchedAt = now) }

    private fun repo(source: String) = DesktopWeatherRepository(service, dao, lat, lon, source)

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `Google fetches the tapped day once, stores it, then has nothing left to fetch`() = runTest {
        val google = WeatherSource.GOOGLE_WEATHER.id
        dao.upsertHourlyForecasts(lat, lon, google, hours(now, 72))
        val requested = mutableListOf<Int>()
        coEvery { service.fetchForecastAhead(any()) } answers {
            requested += firstArg<Int>()
            RawFetch(hourly = hours(now, firstArg()))
        }

        assertTrue(repo(google).extendHourlyFor(target, now))

        val dayEnd = target.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(1, requested.size)
        assertTrue("asked deep enough to reach the day's last hour", now + (requested.single() - 1) * hour >= dayEnd - hour)
        val stored = dao.getHourlyForecasts(lat, lon, google, now, dayEnd)
        assertTrue(stored.any { it.dateTime == dayEnd - hour })

        assertFalse("the day is stored now", repo(google).extendHourlyFor(target, now))
        assertEquals(1, requested.size)
    }

    @Test
    fun `any source with nothing stored for the day fetches it through its normal call`() = runTest {
        val nws = WeatherSource.NWS.id
        coEvery { service.fetchForecastAhead(any()) } returns RawFetch(hourly = hours(now, 156, nws))

        assertTrue(repo(nws).extendHourlyFor(target, now))

        coVerify(exactly = 1) { service.fetchForecastAhead(any()) }
        val dayEnd = target.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(dao.getHourlyForecasts(lat, lon, nws, now, dayEnd).any { it.dateTime >= dayEnd - hour })
    }

    @Test
    fun `a source fetched recently whose data ends sooner is not fetched again`() = runTest {
        val nws = WeatherSource.NWS.id
        // Fresh rows to now + 100 h: target (today + 5) is past them but inside NWS's 156 h.
        dao.upsertHourlyForecasts(lat, lon, nws, hours(now, 100, nws))

        assertFalse(repo(nws).extendHourlyFor(target, now))
        coVerify(exactly = 0) { service.fetchForecastAhead(any()) }
    }

    @Test
    fun `an empty fetch stores nothing and reports false`() = runTest {
        coEvery { service.fetchForecastAhead(any()) } returns RawFetch()
        assertFalse(repo(WeatherSource.GOOGLE_WEATHER.id).extendHourlyFor(target, now))
    }
}
