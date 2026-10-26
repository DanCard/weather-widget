package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * What rides a desktop refresh is `:shared` `SourceFetchTable.followUps`, as on Android: prior-day cloud
 * on Open-Meteo's fetch, NWS station actuals on NWS's, a failed one included (decisions F3, F5 in
 * `plans/261025-shared-source-fetch-table.md`). Desktop ran both after every visible source's successful
 * refresh.
 * Real DB, mocked network seam.
 */
@Category(ShortDuration::class)
class DesktopRefreshFollowUpsTest {
    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private val service = mockk<WeatherApiClient>(relaxed = true)

    private val lat = 37.4220
    private val lon = -122.0841
    private val now = (System.currentTimeMillis() / 3600_000L) * 3600_000L

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-follow-ups-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        coEvery { service.fetchForecast() } returns RawFetch(hourly = listOf(HourlyForecast(now, 72f, "Clear")))
        coEvery { service.nearestStationsForDailyActuals() } returns emptyList()
        // Yesterday's NWS row without a station-derived actual: the run has a date to fill, so it asks for stations.
        val yesterday = java.time.LocalDate.now().minusDays(1).toEpochDay() * 86_400_000L
        kotlinx.coroutines.runBlocking {
            dao.upsertDailyHistory(listOf(DailyHistory(yesterday, WeatherSource.NWS.id, lat, lon, 70f, 50f, "Clear", now)))
        }
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private suspend fun refresh(source: WeatherSource) =
        DesktopWeatherRepository(service, dao, lat, lon, source.id, currentTimeMillis = { now }).refresh(now)

    @Test
    fun `another source's refresh runs neither prior-day cloud nor NWS station actuals`() = runTest {
        refresh(WeatherSource.OPEN_WEATHER_MAP)

        coVerify(exactly = 0) { service.fetchPriorDayCloudForecast(any()) }
        coVerify(exactly = 0) { service.nearestStationsForDailyActuals() }
    }

    @Test
    fun `Open-Meteo's refresh asks for prior-day cloud and not NWS station actuals`() = runTest {
        refresh(WeatherSource.OPEN_METEO)

        coVerify(exactly = 1) { service.fetchPriorDayCloudForecast(any()) }
        coVerify(exactly = 0) { service.nearestStationsForDailyActuals() }
    }

    @Test
    fun `NWS's refresh fills station actuals and asks for no prior-day cloud`() = runTest {
        refresh(WeatherSource.NWS)

        coVerify(exactly = 0) { service.fetchPriorDayCloudForecast(any()) }
        coVerify(exactly = 1) { service.nearestStationsForDailyActuals() }
    }

    @Test
    fun `a failed NWS refresh still fills station actuals`() = runTest {
        // The station endpoints are not the gridpoint ones: a forecast outage keeps the actuals, as on Android.
        coEvery { service.fetchForecast() } throws java.io.IOException("gridpoints 500")

        runCatching { refresh(WeatherSource.NWS) }

        coVerify(exactly = 1) { service.nearestStationsForDailyActuals() }
        coVerify(exactly = 0) { service.fetchPriorDayCloudForecast(any()) }
    }
}
