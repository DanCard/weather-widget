package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.repository.ClimateGapFiller
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.testutil.TestData
import com.weatherwidget.util.NavigationUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Integration test verifying that [DailyInteractionRenderer.loadData] populates and reuses
 * the multi-series cache bundle ([WidgetInteractionCache.Data]) across renders without re-querying Room DAOs.
 *
 * This guards against regression to commit f7b4b5d3ab6d3ffc6aaed64bda3b3caa6e00d0bc where
 * forecastSnapshots, hourlyForecasts, and currentTemps were added to the interaction cache.
 */
@Category(MediumDuration::class)
class DailyInteractionRendererMultiSeriesCacheIntegrationTest : RobolectricTest() {

    private lateinit var context: Context
    private lateinit var database: WeatherDatabase
    private lateinit var forecastDao: ForecastDao
    private lateinit var hourlyDao: HourlyForecastDao
    private lateinit var observationDao: ObservationDao
    private lateinit var dailyHistoryDao: DailyHistoryDao
    private lateinit var climateNormalDao: ClimateNormalDao
    private lateinit var appLogDao: AppLogDao

    private val lat = TestData.LAT
    private val lon = TestData.LON
    private val today = LocalDate.of(2026, 10, 28)
    private val window = NavigationUtils.DailyLoadWindow(historyDays = 2, forecastDays = 9)
    private lateinit var range: DailyInteractionRenderer.DailyRange
    private lateinit var timeBounds: DailyInteractionRenderer.TimeBounds

    @Before
    fun setUp() {
        WidgetInteractionCache.clear()
        context = ApplicationProvider.getApplicationContext()

        database = mockk(relaxed = true)
        forecastDao = mockk(relaxed = true)
        hourlyDao = mockk(relaxed = true)
        observationDao = mockk(relaxed = true)
        dailyHistoryDao = mockk(relaxed = true)
        climateNormalDao = mockk(relaxed = true)
        appLogDao = mockk(relaxed = true)

        every { database.forecastDao() } returns forecastDao
        every { database.hourlyForecastDao() } returns hourlyDao
        every { database.observationDao() } returns observationDao
        every { database.dailyHistoryDao() } returns dailyHistoryDao
        every { database.climateNormalDao() } returns climateNormalDao
        every { database.appLogDao() } returns appLogDao

        range = DailyInteractionRenderer.rangeFor(today, window)
        timeBounds = DailyInteractionRenderer.timeBounds(today.atTime(12, 0), ZoneId.systemDefault())

        coEvery {
            forecastDao.getForecastsInRangeAllSites(any(), any(), any(), any())
        } returns listOf(TestData.forecast(lat = lat, lon = lon))

        coEvery {
            hourlyDao.getHourlyForecastsForSources(any(), any(), any(), any(), any())
        } returns listOf(TestData.hourly(lat = lat, lon = lon))

        coEvery {
            observationDao.getLatestMainObservations(any(), any(), any())
        } returns listOf(TestData.observation(lat = lat, lon = lon))
    }

    @After
    fun tearDown() {
        WidgetInteractionCache.clear()
    }

    @Test
    fun `loadData populates all series on miss and reuses cached bundle on hit`() = runTest {
        val gapFiller = ClimateGapFiller(climateNormalDao)

        // First call: cache miss -> queries Room DAOs
        val firstResult = DailyInteractionRenderer.loadData(
            context = context,
            database = database,
            lat = lat,
            lon = lon,
            today = today,
            range = range,
            timeBounds = timeBounds,
            gapFiller = gapFiller,
        )

        assertEquals(1, firstResult.weatherListRaw.size)
        assertNotNull("forecastSnapshots must be populated", firstResult.forecastSnapshots)
        assertNotNull("hourlyForecasts must be populated", firstResult.hourlyForecasts)
        assertEquals(1, firstResult.hourlyForecasts?.size)
        assertNotNull("currentTemps must be populated", firstResult.currentTemps)
        assertEquals(1, firstResult.currentTemps?.size)

        coVerify(exactly = 1) { forecastDao.getForecastsInRangeAllSites(any(), any(), lat, lon) }
        coVerify(exactly = 1) { hourlyDao.getHourlyForecastsForSources(any(), any(), lat, lon, any()) }
        coVerify(exactly = 1) { observationDao.getLatestMainObservations(lat, lon, any()) }

        // Second call: cache hit -> reuses cached bundle, 0 additional DAO queries
        val secondResult = DailyInteractionRenderer.loadData(
            context = context,
            database = database,
            lat = lat,
            lon = lon,
            today = today,
            range = range,
            timeBounds = timeBounds,
            gapFiller = gapFiller,
        )

        assertSame("weatherListRaw must be reused from cache", firstResult.weatherListRaw, secondResult.weatherListRaw)
        assertSame("forecastSnapshots must be reused from cache", firstResult.forecastSnapshots, secondResult.forecastSnapshots)
        assertSame("hourlyForecasts must be reused from cache", firstResult.hourlyForecasts, secondResult.hourlyForecasts)
        assertSame("currentTemps must be reused from cache", firstResult.currentTemps, secondResult.currentTemps)
        assertSame("dailyActuals must be reused from cache", firstResult.dailyActuals, secondResult.dailyActuals)

        // Verifying call counts remain 1 ensures no duplicate Room disk queries occurred
        coVerify(exactly = 1) { forecastDao.getForecastsInRangeAllSites(any(), any(), lat, lon) }
        coVerify(exactly = 1) { hourlyDao.getHourlyForecastsForSources(any(), any(), lat, lon, any()) }
        coVerify(exactly = 1) { observationDao.getLatestMainObservations(lat, lon, any()) }

        // Cache clear: forces reload on next interaction
        WidgetInteractionCache.clear()

        val thirdResult = DailyInteractionRenderer.loadData(
            context = context,
            database = database,
            lat = lat,
            lon = lon,
            today = today,
            range = range,
            timeBounds = timeBounds,
            gapFiller = gapFiller,
        )

        assertNotNull(thirdResult)
        coVerify(exactly = 2) { forecastDao.getForecastsInRangeAllSites(any(), any(), lat, lon) }
        coVerify(exactly = 2) { hourlyDao.getHourlyForecastsForSources(any(), any(), lat, lon, any()) }
        coVerify(exactly = 2) { observationDao.getLatestMainObservations(lat, lon, any()) }
    }

    @Test
    fun `loadData isolates entries across locations and re-queries for new site`() = runTest {
        val gapFiller = ClimateGapFiller(climateNormalDao)
        val otherLat = 40.71
        val otherLon = -74.01

        DailyInteractionRenderer.loadData(
            context = context,
            database = database,
            lat = lat,
            lon = lon,
            today = today,
            range = range,
            timeBounds = timeBounds,
            gapFiller = gapFiller,
        )

        coVerify(exactly = 1) { forecastDao.getForecastsInRangeAllSites(any(), any(), lat, lon) }
        coVerify(exactly = 0) { forecastDao.getForecastsInRangeAllSites(any(), any(), otherLat, otherLon) }

        // Different site must not hit the cache of the first site
        DailyInteractionRenderer.loadData(
            context = context,
            database = database,
            lat = otherLat,
            lon = otherLon,
            today = today,
            range = range,
            timeBounds = timeBounds,
            gapFiller = gapFiller,
        )

        coVerify(exactly = 1) { forecastDao.getForecastsInRangeAllSites(any(), any(), otherLat, otherLon) }
    }
}
