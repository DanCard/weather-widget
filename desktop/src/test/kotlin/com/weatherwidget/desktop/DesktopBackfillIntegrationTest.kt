package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import org.junit.experimental.categories.Category

/**
 * GENERIC_GAP ('Generic') is a FUTURE-ONLY forecast filler — it covers forecast hours beyond the
 * selected API's horizon and must NEVER stand in for history (its Open-Meteo decimals would
 * masquerade as the real, whole-degree NWS forecast, or invent a forecast for hours we never made).
 *
 * These tests pin that contract: refresh() does not backfill Open-Meteo into the past, and
 * getHourlyHistory() excludes past Generic rows while still admitting future ones. (This class used
 * to verify a one-time Open-Meteo history backfill — that behaviour was removed precisely because it
 * violated the future-only rule.)
 */
@Category(MediumDuration::class)
class DesktopBackfillIntegrationTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository
    private val weatherService = mockk<DesktopWeatherService>()

    private val lat = 37.4220
    private val lon = -122.0841
    private val source = "NWS"

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-backfill-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        repository = DesktopWeatherRepository(weatherService, dao, lat, lon, source)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `refresh never backfills Open-Meteo into history`() = runTest {
        val baseHour = (System.currentTimeMillis() / 3600_000L) * 3600_000L
        coEvery { weatherService.fetchForecast() } returns RawFetch(
            providerCurrentTemp = 70f,
            hourly = listOf(HourlyForecast(baseHour, 70f, "Clear", source = "NWS")),
            daily = emptyList(),
        )

        repository.refresh()

        // History must come only from real accumulated snapshots — never an Open-Meteo past_days pull.
        coVerify(exactly = 0) { weatherService.fetchHistory(any()) }
    }

    @Test
    fun `getHourlyHistory excludes past Generic rows`() {
        val now = System.currentTimeMillis()
        val baseHour = (now / 3600_000L) * 3600_000L
        val pastHour = baseHour - 48 * 3600_000L

        // A real NWS snapshot and an Open-Meteo Generic row for the SAME past hour.
        dao.upsertHourlyForecastHistory(
            lat, lon, "NWS", now - 4 * 3600_000L,
            listOf(HourlyForecast(pastHour, 60f, "Clear", source = "NWS")),
        )
        dao.upsertHourlyForecastHistory(
            lat, lon, WeatherSource.GENERIC_GAP.id, 0L,
            listOf(HourlyForecast(pastHour, 61.6f, "Cloudy", cloudCover = 80, source = WeatherSource.GENERIC_GAP.id)),
        )

        val rows = dao.getHourlyHistory(lat, lon, "NWS", pastHour - 3600_000L, pastHour + 3600_000L, nowMs = now)

        // Only the NWS row survives; the past Generic row (Open-Meteo decimals) is excluded.
        assertEquals(1, rows.size)
        assertEquals("NWS", rows[0].source)
        assertEquals(60f, rows[0].temperature)
    }

    @Test
    fun `getHourlyHistory includes future Generic rows`() {
        val now = System.currentTimeMillis()
        val baseHour = (now / 3600_000L) * 3600_000L
        val futureHour = baseHour + 48 * 3600_000L

        dao.upsertHourlyForecastHistory(
            lat, lon, WeatherSource.GENERIC_GAP.id, 0L,
            listOf(HourlyForecast(futureHour, 75.4f, "Clear", source = WeatherSource.GENERIC_GAP.id)),
        )

        val rows = dao.getHourlyHistory(lat, lon, "NWS", futureHour - 3600_000L, futureHour + 3600_000L, nowMs = now)

        // Future Generic legitimately fills beyond the API horizon, so it IS admitted.
        assertEquals(1, rows.size)
        assertEquals(WeatherSource.GENERIC_GAP.id, rows[0].source)
    }
}
