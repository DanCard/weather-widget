package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
import com.weatherwidget.testutil.TestData.LOCATION_NAME
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WeatherRepositoryRateLimitIntegrationTest {
    private lateinit var db: WeatherDatabase
    private val context = RuntimeEnvironment.getApplication()

    private fun buildRepo(
        nwsApi: NwsApi = mockk<NwsApi>().also { coEvery { it.getGridPoint(any(), any()) } throws Exception("unavailable") },
        openMeteoApi: OpenMeteoApi = mockk<OpenMeteoApi>().also { coEvery { it.getForecast(any(), any(), any()) } throws Exception("unavailable") },
        weatherApi: WeatherApi = mockk<WeatherApi>().also { coEvery { it.getForecast(any(), any(), any()) } throws Exception("unavailable") },
    ): WeatherRepository {
        val widgetStateManager = mockk<WidgetStateManager>(relaxed = true)
        every { widgetStateManager.isSourceVisible(any()) } returns true
        val forecastRepo = ForecastRepository(context, db.forecastDao(), db.hourlyForecastDao(), db.appLogDao(), nwsApi, openMeteoApi, weatherApi, mockk(relaxed = true), widgetStateManager, db.climateNormalDao(), db.observationDao(), mockk(relaxed = true), mockk(relaxed = true))
        val currentRepo = CurrentTempRepository(context, db.observationDao(), db.hourlyForecastDao(), db.appLogDao(), nwsApi, openMeteoApi, weatherApi, mockk(relaxed = true), widgetStateManager, TemperatureInterpolator(), mockk(relaxed = true), mockk(relaxed = true))
        return WeatherRepository(context, forecastRepo, currentRepo, db.forecastDao(), db.appLogDao(), mockk(relaxed = true))
    }

    @Before
    fun setup() {
        db = TestDatabase.create()
        FetchMetadata.setLastFullFetchTime(context, 0L)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `rate limit resets to 0 on exception`() = runTest {
        FetchMetadata.setLastFullFetchTime(context, System.currentTimeMillis())
        val repo = buildRepo()
        repo.getWeatherData(LAT, LON, LOCATION_NAME, forceRefresh = true)
        assertEquals(0L, FetchMetadata.getLastFullFetchTime(context))
    }
}
