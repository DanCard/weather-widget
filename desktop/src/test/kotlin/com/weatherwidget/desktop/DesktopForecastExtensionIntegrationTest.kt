package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.config.ForecastHorizon
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * On-demand daily-forecast extension ([DesktopWeatherRepository.ensureForecastDays]): when the user
 * navigates the daily view past the baseline horizon, the repository fetches straight to
 * [ForecastHorizon.MAX_DAYS] once and persists the wider batch. Guards both the request threading
 * (the Open-Meteo `days` count the service is asked for) and the idempotence that keeps rapid pans
 * from re-fetching.
 */
class DesktopForecastExtensionIntegrationTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository
    private val weatherService = mockk<DesktopWeatherService>()

    private val lat = 37.4220
    private val lon = -122.0841
    private val source = WeatherSource.OPEN_METEO.id

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-forecast-ext-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        repository = DesktopWeatherRepository(weatherService, dao, lat, lon, source)

        // The service returns exactly as many daily rows as it was asked for, starting today.
        coEvery { weatherService.fetchForecast(any()) } answers {
            ForecastResult(currentTemp = 70f, hourly = emptyList(), daily = nDayForecast(firstArg()))
        }
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private fun nDayForecast(days: Int): List<DailyForecast> {
        val today = LocalDate.now()
        return (0 until days).map { i ->
            DailyForecast(
                date = today.plusDays(i.toLong()).toString(),
                highTemp = 70f + i,
                lowTemp = 50f + i,
                condition = "Clear",
            )
        }
    }

    @Test
    fun `ensureForecastDays fetches the full horizon and persists it`() = runTest {
        val widened = repository.ensureForecastDays(ForecastHorizon.MAX_DAYS)

        assertTrue("extension reported new data", widened)
        coVerify(exactly = 1) { weatherService.fetchForecast(ForecastHorizon.MAX_DAYS) }

        // The persisted batch reaches the full 16-day horizon (today + 15).
        val persisted = dao.getDailyForecasts(lat, lon, source).filter { !it.isClimateNormal }
        assertEquals(ForecastHorizon.MAX_DAYS, persisted.size)
        val maxDate = persisted.maxOf { LocalDate.parse(it.date) }
        assertEquals(LocalDate.now().plusDays((ForecastHorizon.MAX_DAYS - 1).toLong()), maxDate)
    }

    @Test
    fun `a second extension at the same horizon is a no-op`() = runTest {
        assertTrue(repository.ensureForecastDays(ForecastHorizon.MAX_DAYS))

        // Already covered → the second call is a no-op and issues no further fetch.
        assertFalse(repository.ensureForecastDays(ForecastHorizon.MAX_DAYS))
        coVerify(exactly = 1) { weatherService.fetchForecast(ForecastHorizon.MAX_DAYS) }
    }
}
