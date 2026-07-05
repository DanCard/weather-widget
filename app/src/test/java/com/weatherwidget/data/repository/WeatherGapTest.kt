package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category



@Category(MediumDuration::class)
class WeatherGapTest {
    private lateinit var context: Context
    private lateinit var forecastDao: ForecastDao
    private lateinit var hourlyForecastDao: HourlyForecastDao
    private lateinit var appLogDao: AppLogDao
    private lateinit var nwsApi: NwsApi
    private lateinit var openMeteoApi: OpenMeteoApi
    private lateinit var weatherApi: WeatherApi
    private lateinit var widgetStateManager: WidgetStateManager
    private lateinit var climateNormalDao: ClimateNormalDao
    private lateinit var observationDao: ObservationDao
    private lateinit var repository: WeatherRepository

    private val testLat = 37.42
    private val testLon = -122.08
    private val testLocationName = "Test Location"
    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs

        forecastDao = mockk(relaxed = true)
        hourlyForecastDao = mockk(relaxed = true)
        appLogDao = mockk(relaxed = true)
        nwsApi = mockk()
        openMeteoApi = mockk()
        weatherApi = mockk()
        widgetStateManager = mockk(relaxed = true)
        climateNormalDao = mockk(relaxed = true)
        observationDao = mockk(relaxed = true)

        val forecastRepo = ForecastRepository(
            context,
            forecastDao,
            hourlyForecastDao,
            mockk(relaxed = true),
            appLogDao,
            nwsApi,
            openMeteoApi,
            mockk(relaxed = true),
            weatherApi,
            mockk(relaxed = true),
            widgetStateManager,
            climateNormalDao,
            observationDao,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        val currentRepo = CurrentTempRepository(
            context,
            observationDao,
            hourlyForecastDao,
            appLogDao,
            nwsApi,
            openMeteoApi,
            mockk(relaxed = true),
            weatherApi,
            mockk(relaxed = true),
            widgetStateManager,
            
            mockk(relaxed = true),
            mockk(relaxed = true)
        )

        repository =
            WeatherRepository(context, forecastRepo, currentRepo, forecastDao, appLogDao, mockk(relaxed = true))

        coEvery { weatherApi.getForecast(any(), any()) } throws Exception("WeatherAPI unavailable")
    }

    @Test
    fun `getCachedDataBySource merges provider data with read-time climate-normal gap fill`() =
        runTest {
            val nwsData = listOf(createForecastEntity(today, 70, 50, "NWS"))
            coEvery { forecastDao.getForecastsInRangeBySource(any(), any(), testLat, testLon, "NWS") } returns nwsData

            val locationKey = ClimateNormals.locationKey(testLat, testLon)
            coEvery { climateNormalDao.getNormalsForLocation(locationKey) } returns
                (1..12).map { month ->
                    ClimateNormalEntity(
                        monthDay = "${month.toString().padStart(2, '0')}-15",
                        locationKey = locationKey,
                        highTemp = 66f,
                        lowTemp = 46f,
                    )
                }

            val result = repository.getCachedDataBySource(testLat, testLon, WeatherSource.NWS)

            assertEquals("NWS", result.find { it.targetDate == dateEpoch(today) }?.source)
            assertEquals(WeatherSource.GENERIC_GAP.id, result.find { it.targetDate == dateEpoch(tomorrow) }?.source)
            // Generation now extends out to the full cache horizon, not just "tomorrow" — no persisted
            // gap row is ever read back, so every future day's fallback comes from the cached normals.
            assertTrue(result.size > 2)
        }

    private fun createForecastEntity(date: String, high: Int, low: Int, source: String, isClimateNormal: Boolean = false) =
        ForecastEntity(
            targetDate = dateEpoch(date),
            forecastDate = dateEpoch(date),
            locationLat = testLat,
            locationLon = testLon,
            locationName = testLocationName,
            highTemp = high.toFloat(),
            lowTemp = low.toFloat(),
            condition = if (isClimateNormal) "Climate Avg" else "Sunny",
            isClimateNormal = isClimateNormal,
            source = source,
            fetchedAt = System.currentTimeMillis()
        )
}
