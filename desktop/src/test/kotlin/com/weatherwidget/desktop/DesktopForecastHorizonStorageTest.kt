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
 * [DesktopWeatherRepository] + [DesktopWeatherDao] + [DesktopForecastHorizon]: every refresh is LONG,
 * stores every daily row and sets the marker; a newer fetch covering fewer days leaves the later days
 * to the older rows (newest row per day, not newest batch); the "older forecasts" reader skips
 * exactly the rows shown. performance/261025-every-forecast-fetch-long.md
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

    private fun daily(high: Float, days: Int = 8) = (0 until days).map { DailyForecast(today.plusDays(it.toLong()).toString(), high, 50f, "Cloudy") }
    private val hourly = (1..5).map { HourlyForecast(dateTime = (now / hour + it) * hour, temperature = 60f, condition = "Cloudy") }

    private fun repo() = DesktopWeatherRepository(service, dao, lat, lon, om)
    private fun markers(source: String = om, la: Double = lat, lo: Double = lon) = DesktopForecastHorizon(dao, source, la, lo)

    private suspend fun refresh(high: Float, horizon: Horizon?, at: Long = now, days: Int = 8) {
        coEvery { service.fetchForecast() } returns RawFetch(daily = daily(high, days), hourly = hourly)
        repo().refresh(now = at, reason = "test", horizon = horizon)
    }

    private fun shownHighs() = dao.getDailyForecasts(lat, lon, om).associate { LocalDate.parse(it.date) to it.highTemp }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `a LONG refresh stores every daily row and sets the marker`() = runTest {
        refresh(high = 70f, horizon = Horizon.LONG)

        assertEquals((0 until 8).map { today.plusDays(it.toLong()) }.toSet(), shownHighs().keys)
        assertNotNull(markers().lastLongFetch())
    }

    @Test
    fun `a refresh 4 h later, judged at fetch time, rewrites all 8 days`() = runTest {
        // Was SHORT before 2026-10-25: days 4+ kept the earlier fetch's 70.
        refresh(high = 70f, horizon = Horizon.LONG)
        Thread.sleep(5)
        refresh(high = 80f, horizon = null, at = now + 4 * hour)

        val highs = shownHighs()
        assertEquals(8, highs.size)
        assertTrue(highs.toString(), (0L until 8L).all { highs[today.plusDays(it)] == 80f })
    }

    @Test
    fun `a newer fetch covering fewer days keeps the later days from the older one`() = runTest {
        refresh(high = 70f, horizon = Horizon.LONG)
        Thread.sleep(5)
        refresh(high = 80f, horizon = Horizon.LONG, at = now + 4 * hour, days = 3)

        val highs = shownHighs()
        assertEquals("all 8 days still shown", 8, highs.size)
        assertTrue((0L..2L).all { highs[today.plusDays(it)] == 80f })
        assertTrue((3L until 8L).all { highs[today.plusDays(it)] == 70f })
    }

    @Test
    fun `rows more than 36 h older than the newest fetch are not shown`() = runTest {
        dao.upsertForecasts(lat, lon, om, daily(60f), nowMs = now - 40 * hour)
        refresh(high = 80f, horizon = Horizon.LONG, days = 3)

        val highs = shownHighs()
        assertEquals("only the newest fetch's 3 days", (0L..2L).map { today.plusDays(it) }.toSet(), highs.keys)
    }

    @Test
    fun `older forecasts are every row but the one shown, per day`() = runTest {
        refresh(high = 70f, horizon = Horizon.LONG)
        Thread.sleep(5)
        refresh(high = 80f, horizon = Horizon.LONG, at = now + 4 * hour, days = 3)

        val start = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val snapshots = dao.getDailyForecastSnapshots(start, start + 10 * 86_400_000L, lat, lon, om)
        assertEquals("tomorrow: the first fetch's row is the older forecast", listOf(70f), snapshots[today.plusDays(1).toString()]?.map { it.highTemp })
        assertNull("day 5: the first fetch's row is the one shown", snapshots[today.plusDays(5).toString()])
    }

    @Test
    fun `a refresh with no horizon given is judged LONG and moves the marker`() = runTest {
        coEvery { service.fetchForecast() } returns RawFetch(daily = daily(70f), hourly = hourly)
        DesktopWeatherRepository(service, dao, lat, lon, om).refresh(now = now, reason = "test")
        assertNotNull("with a DB every refresh is judged LONG", markers().lastLongFetch())
    }

    @Test
    fun `a user refresh asks for and stores a LONG fetch`() = runTest {
        coEvery { service.fetchForecast(ForecastParts.BOTH, Horizon.LONG) } returns RawFetch(daily = daily(70f), hourly = hourly)

        repo().refresh(now = now, reason = "user_refresh:popup", userRefresh = true)

        coVerify(exactly = 1) { service.fetchForecast(ForecastParts.BOTH, Horizon.LONG) }
        assertNotNull(markers().lastLongFetch())
        assertEquals(8, shownHighs().size)
    }

    @Test
    fun `an hourly-only refresh (daily refused) stores hours, no daily rows and no REFRESH row`() = runTest {
        coEvery { service.fetchForecast(ForecastParts.HOURLY) } returns RawFetch(daily = daily(70f), hourly = hourly)

        repo().refresh(now = now, reason = "test", parts = ForecastParts.HOURLY, horizon = Horizon.LONG)

        assertTrue(dao.getHourlyForecasts(lat, lon, om, now, now + 10 * hour).isNotEmpty())
        assertTrue("daily rows untouched", dao.getDailyForecasts(lat, lon, om).isEmpty())
        assertNull("no daily fetch recorded", dao.getLastSuccessfulFetch(om))
        assertNotNull(markers().lastLongFetch())
    }

    @Test
    fun `a daily-only refresh (hours refused) asks for daily and stores it`() = runTest {
        coEvery { service.fetchForecast(ForecastParts.DAILY) } returns RawFetch(daily = daily(70f))

        repo().refresh(now = now, reason = "test", parts = ForecastParts.DAILY, horizon = Horizon.LONG)

        coVerify(exactly = 1) { service.fetchForecast(ForecastParts.DAILY) }
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
        assertNull(DesktopForecastHorizon(null, om, lat, lon).forced())
        assertNull(DesktopForecastHorizon(null, om, lat, lon).decision(now))
    }

    @Test
    fun `the last-view table exists at the current schema version`() {
        dao.recordLastView(om, 1L)
        assertEquals(mapOf(om to 1L), dao.lastViews())
        assertTrue("source_last_view arrived in v33", DesktopWeatherDatabase.SCHEMA_VERSION >= 33)
    }
}
