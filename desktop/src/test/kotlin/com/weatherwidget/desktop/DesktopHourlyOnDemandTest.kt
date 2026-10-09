package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
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
    fun `fetches the tapped day once, stores it, then has nothing left to fetch`() = runTest {
        val google = WeatherSource.GOOGLE_WEATHER.id
        dao.upsertHourlyForecasts(lat, lon, google, hours(now, 72))
        val requested = mutableListOf<Int>()
        coEvery { service.fetchHourlyAhead(any()) } answers {
            requested += firstArg<Int>()
            hours(now, firstArg())
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
    fun `a source that holds its whole horizon never fetches`() = runTest {
        assertFalse(repo(WeatherSource.NWS.id).extendHourlyFor(target, now))
        coVerify(exactly = 0) { service.fetchHourlyAhead(any()) }
    }

    @Test
    fun `the prune drops stale Google hours past 72 h and leaves other sources whole`() {
        val google = WeatherSource.GOOGLE_WEATHER.id
        val nws = WeatherSource.NWS.id
        dao.upsertHourlyForecasts(lat, lon, google, hours(now, 72) + hours(now + 96 * hour, 2))
        dao.upsertHourlyForecasts(lat, lon, nws, hours(now, 120, nws))
        // The save stamps fetchedAt itself; age everything 13 h, then add fresh on-demand hours.
        database.getConnection().use { it.createStatement().executeUpdate("UPDATE hourly_forecasts SET fetchedAt = ${now - 13 * hour}") }
        dao.upsertHourlyForecasts(lat, lon, google, hours(now + 120 * hour, 2))

        repo(google).pruneHourlyBeyondWindow(now)

        val googleLeft = dao.getHourlyForecasts(lat, lon, google, now, now + 168 * hour).map { it.dateTime }
        assertEquals(
            "routine 72 h stay however old; stale on-demand hours go; fresh ones stay",
            hours(now, 72).map { it.dateTime } + hours(now + 120 * hour, 2).map { it.dateTime },
            googleLeft,
        )
        assertEquals(120, dao.getHourlyForecasts(lat, lon, nws, now, now + 168 * hour).size)
    }

    @Test
    fun `an empty fetch stores nothing and reports false`() = runTest {
        coEvery { service.fetchHourlyAhead(any()) } returns emptyList()
        assertFalse(repo(WeatherSource.GOOGLE_WEATHER.id).extendHourlyFor(target, now))
    }
}
