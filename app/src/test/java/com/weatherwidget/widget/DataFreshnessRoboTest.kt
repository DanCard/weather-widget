package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(ShortDuration::class)
class DataFreshnessRoboTest {
    private lateinit var context: Context
    private lateinit var database: WeatherDatabase
    private lateinit var forecastDao: ForecastDao
    private lateinit var stateManager: WidgetStateManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        database = mockk()
        forecastDao = mockk()
        stateManager = mockk()

        mockkObject(WeatherDatabase)
        every { WeatherDatabase.getDatabase(any()) } returns database
        every { database.forecastDao() } returns forecastDao
        
        // Mock WidgetStateManager construction if necessary or its methods
        // Since DataFreshness.isDataStale creates its own stateManager, we need to mock the constructor
        mockkConstructor(WidgetStateManager::class)
        every { anyConstructed<WidgetStateManager>().getVisibleSourcesOrder() } returns listOf(WeatherSource.NWS)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createForecast(source: String, batchFetchedAt: Long): ForecastEntity {
        return ForecastEntity(
            targetDate = 0L,
            forecastDate = 0L,
            locationLat = 0.0,
            locationLon = 0.0,
            highTemp = 70f,
            lowTemp = 50f,
            condition = "Sunny",
            source = source,
            batchFetchedAt = batchFetchedAt
        )
    }

    @Test
    fun `isDataStale returns true when NWS is primary and stale but Open-Meteo is fresh`() = runTest {
        val now = System.currentTimeMillis()
        val staleTime = now - (70 * 60 * 1000L) // 70 mins ago (stale for primary)
        val freshTime = now - (10 * 60 * 1000L) // 10 mins ago
        
        // Setup: NWS (Primary) is visible, Open-Meteo is also visible
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        every { anyConstructed<WidgetStateManager>().getVisibleSourcesOrder() } returns visibleSources
        
        // NWS is stale
        coEvery { forecastDao.getLatestWeatherBySource(WeatherSource.NWS.id) } returns createForecast(WeatherSource.NWS.id, staleTime)
        // Open-Meteo is fresh
        coEvery { forecastDao.getLatestWeatherBySource(WeatherSource.OPEN_METEO.id) } returns createForecast(WeatherSource.OPEN_METEO.id, freshTime)
        
        // Execute
        val result = DataFreshness.isDataStale(context)
        
        // Verify: Should be stale because NWS is stale
        assertTrue("Should be stale because NWS is stale", result)
    }

    @Test
    fun `isDataStale returns false when all visible sources are fresh`() = runTest {
        val now = System.currentTimeMillis()
        val freshTime = now - (10 * 60 * 1000L) // 10 mins ago
        
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        every { anyConstructed<WidgetStateManager>().getVisibleSourcesOrder() } returns visibleSources
        
        coEvery { forecastDao.getLatestWeatherBySource(WeatherSource.NWS.id) } returns createForecast(WeatherSource.NWS.id, freshTime)
        coEvery { forecastDao.getLatestWeatherBySource(WeatherSource.OPEN_METEO.id) } returns createForecast(WeatherSource.OPEN_METEO.id, freshTime)
        
        val result = DataFreshness.isDataStale(context)
        
        assertFalse("Should not be stale when all sources are fresh", result)
    }

    @Test
    fun `isDataStale returns true when visible source has no data`() = runTest {
        val visibleSources = listOf(WeatherSource.NWS)
        every { anyConstructed<WidgetStateManager>().getVisibleSourcesOrder() } returns visibleSources
        
        coEvery { forecastDao.getLatestWeatherBySource(WeatherSource.NWS.id) } returns null
        
        val result = DataFreshness.isDataStale(context)
        
        assertTrue("Should be stale when source has no data", result)
    }

    @Test
    fun `isDataStale returns false when no sources are visible`() = runTest {
        every { anyConstructed<WidgetStateManager>().getVisibleSourcesOrder() } returns emptyList()
        
        val result = DataFreshness.isDataStale(context)
        
        assertFalse("Should not be stale when no sources are visible", result)
    }
}
