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

/**
 * Tests rate-limit behavior using real SharedPreferences (via Robolectric).
 * Verifies the critical bug fix: rate limit resets to 0 on fetch failure,
 * allowing immediate retry.
 */
@RunWith(RobolectricTestRunner::class)
class WeatherRepositoryRateLimitIntegrationTest {
    private lateinit var db: WeatherDatabase
    private val context = RuntimeEnvironment.getApplication()

    private fun buildRepo(
        nwsApi: NwsApi = mockk<NwsApi>().also {
            coEvery { it.getGridPoint(any(), any()) } throws Exception("unavailable")
        },
        openMeteoApi: OpenMeteoApi = mockk<OpenMeteoApi>().also {
            coEvery { it.getForecast(any(), any(), any()) } throws Exception("unavailable")
        },
        weatherApi: WeatherApi = mockk<WeatherApi>().also {
            coEvery { it.getForecast(any(), any(), any()) } throws Exception("unavailable")
        },
    ): WeatherRepository = WeatherRepository(
        context,
        db.weatherDao(),
        db.forecastSnapshotDao(),
        db.hourlyForecastDao(),
        db.appLogDao(),
        nwsApi,
        openMeteoApi,
        weatherApi,
        mockk<WidgetStateManager>(relaxed = true).also {
            every { it.isSourceVisible(any()) } returns true
        },
        TemperatureInterpolator(),
        db.climateNormalDao(),
        db.weatherObservationDao(),
    )

    @Before
    fun setup() {
        db = TestDatabase.create()
        // Clear prefs from previous test
        FetchMetadata.setLastFullFetchTime(context, 0L)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `rate limit timestamp is 0 initially`() {
        assertEquals(0L, FetchMetadata.getLastFullFetchTime(context))
    }

    @Test
    fun `rate limit resets to 0 on exception`() = runTest {
        // Set a non-zero rate limit
        FetchMetadata.setLastFullFetchTime(context, System.currentTimeMillis())
        assertTrue(FetchMetadata.getLastFullFetchTime(context) > 0)

        // All APIs throw — this triggers the catch block which resets rate limit
        val repo = buildRepo()
        repo.getWeatherData(LAT, LON, LOCATION_NAME, forceRefresh = true)

        assertEquals(0L, FetchMetadata.getLastFullFetchTime(context))
    }

    @Test
    fun `rate limiter blocks fetch within MIN_NETWORK_INTERVAL`() = runTest {
        // Pre-populate cache so rate limiter returns cached data instead of forcing fetch
        db.weatherDao().insertWeather(TestData.weather())

        // Set rate limit to "just now"
        FetchMetadata.setLastFullFetchTime(context, System.currentTimeMillis())

        val nwsApi = mockk<NwsApi>()
        coEvery { nwsApi.getGridPoint(any(), any()) } throws Exception("should not be called")
        val repo = buildRepo(nwsApi = nwsApi)

        repo.getWeatherData(LAT, LON, LOCATION_NAME, forceRefresh = true)

        // NWS API should NOT have been called (rate limited)
        coVerify(exactly = 0) { nwsApi.getGridPoint(any(), any()) }
    }
}
