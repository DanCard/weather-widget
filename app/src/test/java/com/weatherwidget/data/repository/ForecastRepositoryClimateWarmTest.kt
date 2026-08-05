package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestData
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ForecastRepositoryClimateWarmTest {

    @Test
    fun `climate normal warm propagates cancellation`() = runTest {
        val climateNormalDao = emptyClimateNormalDao()
        val openMeteoApi = mockk<OpenMeteoApi>()
        coEvery {
            openMeteoApi.getHistoricalDailyTemps(any(), any(), any(), any())
        } throws CancellationException("worker stopped")
        val repository = repository(climateNormalDao, openMeteoApi = openMeteoApi)

        var propagated = false
        try {
            repository.getWeatherData(TestData.LAT, TestData.LON, forceRefresh = true)
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue("CancellationException from climate warming must propagate", propagated)
    }

    @Test
    fun `climate normal warm logs ordinary failure and completes weather fetch`() = runTest {
        val climateNormalDao = emptyClimateNormalDao()
        val openMeteoApi = mockk<OpenMeteoApi>()
        coEvery {
            openMeteoApi.getHistoricalDailyTemps(any(), any(), any(), any())
        } throws IllegalStateException("cache unavailable")
        val appLogDao = mockk<AppLogDao>(relaxed = true)
        val repository = repository(climateNormalDao, appLogDao, openMeteoApi)

        val result = repository.getWeatherData(TestData.LAT, TestData.LON, forceRefresh = true)

        assertTrue(result.isSuccess)
        coVerify {
            appLogDao.insert(
                match {
                    it.tag == "CLIMATE_WARM_FAIL" &&
                        it.message.contains("cache unavailable")
                },
            )
        }
    }

    private fun repository(
        climateNormalDao: ClimateNormalDao,
        appLogDao: AppLogDao = mockk(relaxed = true),
        openMeteoApi: OpenMeteoApi = mockk(relaxed = true),
    ): ForecastRepository {
        val widgetStateManager = mockk<WidgetStateManager>(relaxed = true)
        every { widgetStateManager.getVisibleSourcesOrder() } returns emptyList()
        every { widgetStateManager.isSourceVisible(WeatherSource.OPEN_METEO) } returns true

        return ForecastRepository(
            context = RuntimeEnvironment.getApplication(),
            forecastDao = mockk<ForecastDao>(relaxed = true),
            hourlyForecastDao = mockk<HourlyForecastDao>(relaxed = true),
            hourlyForecastHistoryDao = mockk<HourlyForecastHistoryDao>(relaxed = true),
            appLogDao = appLogDao,
            nwsApi = mockk(relaxed = true),
            openMeteoApi = openMeteoApi,
            visualCrossingApi = mockk(relaxed = true),
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = widgetStateManager,
            climateNormalDao = climateNormalDao,
            observationDao = mockk<ObservationDao>(relaxed = true),
            dailyHistoryDao = mockk<DailyHistoryDao>(relaxed = true),
            observationRepository = mockk(relaxed = true),
            tomorrowIoApi = mockk(relaxed = true),
            openWeatherMapApi = mockk(relaxed = true),
            nwsForecastMapper = mockk(relaxed = true),
            dailyActualsStore = DailyActualsStore(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
        )
    }

    private fun emptyClimateNormalDao(): ClimateNormalDao =
        mockk<ClimateNormalDao>().also {
            coEvery { it.getNormalsForLocation(any()) } returns emptyList()
        }
}
