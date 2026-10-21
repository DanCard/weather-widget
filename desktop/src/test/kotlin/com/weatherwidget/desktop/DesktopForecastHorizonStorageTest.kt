package com.weatherwidget.desktop

import kotlinx.coroutines.runBlocking
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ForecastHorizonCadence.Horizon
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.LocalDate

/**
 * [DesktopWeatherRepository] + [DesktopWeatherDao] + [DesktopForecastHorizon]: a LONG refresh stores
 * every daily row and sets both markers; a SHORT one rewrites only the next 3 days, and the daily
 * view still reads days 4+ from the LONG fetch (newest row per day, not newest batch); the "older
 * forecasts" reader skips exactly the rows shown.
 * performance/261016-unified-long-short-forecast-horizons.md
 */
@Category(ShortDuration::class)
class DesktopForecastHorizonStorageTest {
    private val tempDbPath = Files.createTempFile("weather-forecast-horizon-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val service = mockk<WeatherApiClient>(relaxed = true)
    private val om = WeatherSource.OPEN_METEO.id
    private val lat = 37.4220
    private val lon = -122.0841
    private val hour = 3_600_000L
    private val today = LocalDate.now()
    private val now = today.atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun daily(high: Float) = (0 until 8).map { DailyForecast(today.plusDays(it.toLong()).toString(), high, 50f, "Cloudy") }
    private val hourly = (1..5).map { HourlyForecast(dateTime = (now / hour + it) * hour, temperature = 60f, condition = "Cloudy") }

    private fun repo() = DesktopWeatherRepository(service, dao, lat, lon, om)
    private fun markers(source: String = om, la: Double = lat, lo: Double = lon) = DesktopForecastHorizon(dao, source, la, lo)

    private suspend fun refresh(high: Float, horizon: Horizon, at: Long = now) {
        coEvery { service.fetchForecast(false) } returns RawFetch(daily = daily(high), hourly = hourly)
        repo().refresh(now = at, reason = "test", horizon = horizon)
    }

    private fun shownHighs() = dao.getDailyForecasts(lat, lon, om).associate { LocalDate.parse(it.date) to it.highTemp }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `a LONG refresh stores every daily row and sets both markers`() = runTest {
        refresh(high = 70f, horizon = Horizon.LONG)

        assertEquals((0 until 8).map { today.plusDays(it.toLong()) }.toSet(), shownHighs().keys)
        assertNotNull(markers().lastLongFetch())
        assertNotNull(markers().lastShortFetch())
    }

    @Test
    fun `a SHORT refresh rewrites the next 3 days, the view keeps days 4+ from the LONG fetch`() = runTest {
        refresh(high = 70f, horizon = Horizon.LONG)
        Thread.sleep(5)
        refresh(high = 80f, horizon = Horizon.SHORT, at = now + 4 * hour)

        val highs = shownHighs()
        assertEquals("all 8 days still shown", 8, highs.size)
        assertTrue((0L..2L).all { highs[today.plusDays(it)] == 80f })
        assertTrue((3L until 8L).all { highs[today.plusDays(it)] == 70f })
    }

    @Test
    fun `rows more than 36 h older than the newest fetch are not shown`() = runTest {
        dao.upsertForecasts(lat, lon, om, daily(60f), nowMs = now - 40 * hour)
        refresh(high = 80f, horizon = Horizon.SHORT)

        val highs = shownHighs()
        assertEquals("only the SHORT fetch's 3 days", (0L..2L).map { today.plusDays(it) }.toSet(), highs.keys)
    }

    @Test
    fun `older forecasts are every row but the one shown, per day`() = runTest {
        refresh(high = 70f, horizon = Horizon.LONG)
        Thread.sleep(5)
        refresh(high = 80f, horizon = Horizon.SHORT, at = now + 4 * hour)

        val start = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val snapshots = dao.getDailyForecastSnapshots(start, start + 10 * 86_400_000L, lat, lon, om)
        assertEquals("tomorrow: the LONG row is the older forecast", listOf(70f), snapshots[today.plusDays(1).toString()]?.map { it.highTemp })
        assertNull("day 5: the LONG row is the one shown", snapshots[today.plusDays(5).toString()])
    }

    @Test
    fun `a SHORT refresh moves the SHORT marker only`() = runTest {
        refresh(high = 70f, horizon = Horizon.SHORT)
        assertNotNull(markers().lastShortFetch())
        assertNull(markers().lastLongFetch())
    }

    @Test
    fun `a user refresh asks for and stores a LONG fetch`() = runTest {
        coEvery { service.fetchForecast(false, ForecastParts.BOTH, null, Horizon.LONG) } returns RawFetch(daily = daily(70f), hourly = hourly)

        repo().refresh(now = now, reason = "user_refresh:popup", userRefresh = true)

        coVerify(exactly = 1) { service.fetchForecast(false, ForecastParts.BOTH, null, Horizon.LONG) }
        assertNotNull(markers().lastLongFetch())
        assertEquals(8, shownHighs().size)
    }

    @Test
    fun `an hourly-only refresh (daily refused) stores hours, no daily rows and no REFRESH row`() = runTest {
        coEvery { service.fetchForecast(true, ForecastParts.HOURLY) } returns RawFetch(daily = daily(70f), hourly = hourly)

        repo().refresh(now = now, reason = "test", parts = ForecastParts.HOURLY, horizon = Horizon.SHORT)

        assertTrue(dao.getHourlyForecasts(lat, lon, om, now, now + 10 * hour).isNotEmpty())
        assertTrue("daily rows untouched", dao.getDailyForecasts(lat, lon, om).isEmpty())
        assertNull("no daily fetch recorded", dao.getLastSuccessfulFetch(om))
        assertNotNull(markers().lastShortFetch())
    }

    @Test
    fun `a daily-only refresh (hours refused) asks for daily and stores it`() = runTest {
        coEvery { service.fetchForecast(false, ForecastParts.DAILY) } returns RawFetch(daily = daily(70f))

        repo().refresh(now = now, reason = "test", parts = ForecastParts.DAILY, horizon = Horizon.LONG)

        coVerify(exactly = 1) { service.fetchForecast(false, ForecastParts.DAILY) }
        assertEquals(8, shownHighs().size)
    }

    @Test
    fun `markers are per source and per site`() {
        runBlocking { markers().mark(Horizon.LONG) }
        assertNotNull(markers().lastLongFetch())
        assertNull(markers(source = WeatherSource.NWS.id).lastLongFetch())
        assertNull(markers(la = 50.45, lo = 30.52).lastLongFetch())
    }

    @Test
    fun `no DB means no horizon, the unchanged routine fetch`() {
        assertNull(DesktopForecastHorizon(null, om, lat, lon).forced(now))
        assertNull(DesktopForecastHorizon(null, om, lat, lon).decision(now))
    }

    @Test
    fun `the last-view table exists at the current schema version`() {
        dao.recordLastView(om, 1L)
        assertEquals(mapOf(om to 1L), dao.lastViews())
        assertTrue("source_last_view arrived in v33", DesktopWeatherDatabase.SCHEMA_VERSION >= 33)
    }
}
