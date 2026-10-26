package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * NWS's forecast fetch is the forecast only, as on Android (F6, `plans/261025-shared-source-fetch-table.md`):
 * an NWS refresh takes its station observations through the shared `ActualsFeedPolicy.fullRefreshFetch`
 * pull, the recent window when stored rows reach the last hour and the week only on a real gap. Desktop's
 * NWS forecast fetch pulled the week itself on every refresh. Real DB, mocked network seam.
 */
@Category(ShortDuration::class)
class DesktopNwsForecastOnlyRefreshTest {
    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private val service = mockk<WeatherApiClient>(relaxed = true)

    private val lat = 37.417
    private val lon = -122.089
    private val now = (System.currentTimeMillis() / 3600_000L) * 3600_000L

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-nws-forecast-only-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        coEvery { service.fetchForecast(any()) } returns RawFetch(hourly = listOf(HourlyForecast(now, 72f, "Clear")))
        coEvery { service.fetchObservationsOnly(any(), any()) } returns RawFetch(rawObservations = listOf(reading(now - 10 * 60_000L)))
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private fun reading(ts: Long) = ObservationReading(
        stationId = "KNUQ",
        stationName = "KNUQ",
        timestamp = ts,
        temperature = 71f,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        api = WeatherSource.NWS.id,
    )

    private suspend fun refresh() =
        DesktopWeatherRepository(service, dao, lat, lon, WeatherSource.NWS.id, currentTimeMillis = { now }).refresh(now)

    @Test
    fun `an NWS refresh with no stored rows takes the week through the observation pull and stores it`() = runTest {
        refresh()

        coVerify(exactly = 1) { service.fetchObservationsOnly(recentOnly = false, userLocationChange = false) }
        assertEquals(1, dao.getObservationsInRange(now - 3_600_000L, now, lat, lon).count { it.api == WeatherSource.NWS.id })
    }

    @Test
    fun `an NWS refresh whose stored rows reach the last hour takes only the recent window`() = runTest {
        dao.upsertObservations(
            listOf(
                DesktopObservationEntity(
                    stationId = "KNUQ", stationName = "KNUQ", timestamp = now - 20 * 60_000L,
                    temperature = 70f, condition = "Clear", locationLat = lat, locationLon = lon,
                    fetchedAt = now - 20 * 60_000L, api = WeatherSource.NWS.id,
                ),
            ),
        )

        refresh()

        coVerify(exactly = 1) { service.fetchObservationsOnly(recentOnly = true, userLocationChange = false) }
        coVerify(exactly = 0) { service.fetchObservationsOnly(recentOnly = false, any()) }
    }
}
