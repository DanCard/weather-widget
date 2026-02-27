package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.CurrentTempDao
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.local.WeatherObservationDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class WeatherRepositoryTest {
    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var weatherDao: WeatherDao
    private lateinit var forecastSnapshotDao: ForecastSnapshotDao
    private lateinit var hourlyForecastDao: HourlyForecastDao
    private lateinit var appLogDao: AppLogDao
    private lateinit var nwsApi: NwsApi
    private lateinit var openMeteoApi: OpenMeteoApi
    private lateinit var weatherApi: WeatherApi
    private lateinit var widgetStateManager: WidgetStateManager
    private lateinit var temperatureInterpolator: TemperatureInterpolator
    private lateinit var climateNormalDao: ClimateNormalDao
    private lateinit var weatherObservationDao: WeatherObservationDao
    private lateinit var currentTempDao: CurrentTempDao
    private lateinit var repository: WeatherRepository

    private val testLat = 37.42
    private val testLon = -122.08
    private val testLocationName = "Test Location"
    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sharedPrefs = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs

        weatherDao = mockk(relaxed = true)
        forecastSnapshotDao = mockk(relaxed = true)
        hourlyForecastDao = mockk(relaxed = true)
        appLogDao = mockk(relaxed = true)
        nwsApi = mockk()
        openMeteoApi = mockk()
        weatherApi = mockk()
        widgetStateManager = mockk(relaxed = true)
        temperatureInterpolator = TemperatureInterpolator()
        climateNormalDao = mockk(relaxed = true)
        weatherObservationDao = mockk(relaxed = true)
        currentTempDao = mockk(relaxed = true)

        repository =
            WeatherRepository(
                context,
                weatherDao,
                forecastSnapshotDao,
                hourlyForecastDao,
                appLogDao,
                nwsApi,
                openMeteoApi,
                weatherApi,
                widgetStateManager,
                temperatureInterpolator,
                climateNormalDao,
                weatherObservationDao,
                currentTempDao,
            )

        coEvery { weatherApi.getForecast(any(), any(), any()) } throws Exception("WeatherAPI unavailable")

        // By default, all sources are visible (so shouldFetchSource returns true)
        every { widgetStateManager.isSourceVisible(any()) } returns true
    }

    @Test
    fun `lastFullFetchTime is persisted via SharedPreferences`() =
        runTest {
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPrefs.edit() } returns editor

            val capturedTimes = mutableListOf<Long>()
            every { editor.putLong("last_full_fetch_time", any()) } answers {
                capturedTimes.add(secondArg())
                editor
            }
            every { sharedPrefs.getLong("last_full_fetch_time", 0L) } answers {
                capturedTimes.lastOrNull() ?: 0L
            }

            // Mock cache to be empty to force network fetch
            coEvery { weatherDao.getWeatherRange(any(), any(), any(), any()) } returns emptyList()
            coEvery { nwsApi.getGridPoint(any(), any()) } throws Exception("Forced failure")

            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

            // Verify SharedPreferences was written to (first set to now, then reset to 0 on failure)
            assertTrue("SharedPreferences should have been written to", capturedTimes.size >= 1)
        }

    @Test
    fun `getWeatherData preserves history when API returns partial data`() =
        runTest {
            // 1. Setup: DB has yesterday's actual history
            val existingHistory = createWeatherEntity(yesterday, 65, 45, isActual = true, source = "NWS")

            // Mock getCachedData (internal calls)
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns listOf(existingHistory)
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "NWS") } returns listOf(existingHistory)
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "Generic") } returns emptyList()

            // 2. Setup: API only returns today's forecast
            val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com")
            coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
            coEvery { nwsApi.getForecast(gridPoint) } returns
                listOf(
                    NwsApi.ForecastPeriod("Today", "${today}T06:00:00-08:00", 70, "F", "Sunny", true),
                )
            // Disable other APIs for simplicity
            coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()
            coEvery { openMeteoApi.getForecast(any(), any(), any()) } throws Exception("Skipped")

            // 3. Act
            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

            // 4. Assert: verify insertAll contains both yesterday (history) and today (forecast)
            coVerify {
                weatherDao.insertAll(
                    match { list ->
                        list.size >= 2 &&
                            list.any { it.date == yesterday && it.isActual } &&
                            list.any { it.date == today && !it.isActual }
                    },
                )
            }
        }

    @Test
    fun `getWeatherData logs MERGE_CONFLICT when Forecast threatens History`() =
        runTest {
            // 1. Setup: DB has today's actual data
            val existingHistory = createWeatherEntity(today, 68, 44, isActual = true, source = "NWS")
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns listOf(existingHistory)
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "NWS") } returns listOf(existingHistory)
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "Generic") } returns emptyList()

            // 2. Setup: API returns a new forecast for today
            val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com")
            coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
            coEvery { nwsApi.getForecast(gridPoint) } returns
                listOf(
                    NwsApi.ForecastPeriod("Today", "${today}T06:00:00-08:00", 72, "F", "Sunny", true),
                )
            coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()
            coEvery { openMeteoApi.getForecast(any(), any(), any()) } throws Exception("Skipped")

            // 3. Act
            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

            // 4. Assert: Verify audit log entry
            coVerify { appLogDao.insert(match { it.tag == "MERGE_CONFLICT" }) }
        }

    @Test
    fun `getWeatherData returns cached data when not forcing refresh`() =
        runTest {
            val recentFetch = System.currentTimeMillis() - 15 * 60 * 1000
            val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val cachedData = listOf(
                createWeatherEntity(today, 70, 50).copy(source = WeatherSource.NWS.id, fetchedAt = recentFetch),
                createWeatherEntity(today, 70, 50).copy(source = WeatherSource.OPEN_METEO.id, fetchedAt = recentFetch),
                createWeatherEntity(today, 70, 50).copy(source = WeatherSource.WEATHER_API.id, fetchedAt = recentFetch),
                createWeatherEntity(tomorrow, 75, 55).copy(source = WeatherSource.NWS.id, fetchedAt = recentFetch),
                createWeatherEntity(tomorrow, 75, 55).copy(source = WeatherSource.OPEN_METEO.id, fetchedAt = recentFetch),
                createWeatherEntity(tomorrow, 75, 55).copy(source = WeatherSource.WEATHER_API.id, fetchedAt = recentFetch),
            )
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns cachedData

            val result = repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = false)

            assertTrue(result.isSuccess)
            assertEquals(6, result.getOrNull()?.size)
            coVerify(exactly = 0) { nwsApi.getGridPoint(any(), any()) }
            coVerify(exactly = 0) { openMeteoApi.getForecast(any(), any(), any()) }
        }

    @Test
    fun `getWeatherData returns failure when both APIs fail and no cache`() =
        runTest {
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns emptyList()
            coEvery { nwsApi.getGridPoint(testLat, testLon) } throws Exception("NWS unavailable")
            coEvery { openMeteoApi.getForecast(testLat, testLon, any()) } throws Exception("OpenMeteo unavailable")

            val result = repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = false)

            assertTrue(result.isFailure)
        }

    @Test
    fun `getWeatherData returns cached data when both APIs fail`() =
        runTest {
            val cachedData = listOf(createWeatherEntity(today, 70, 50))

            // First call empty to trigger API, second call has cached data for fallback
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns emptyList() andThen cachedData

            coEvery { nwsApi.getGridPoint(testLat, testLon) } throws Exception("NWS unavailable")
            coEvery { openMeteoApi.getForecast(testLat, testLon, any()) } throws Exception("OpenMeteo unavailable")

            val result = repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = false)

            assertTrue(result.isSuccess)
            assertEquals(1, result.getOrNull()?.size)
        }

    @Test
    fun `getLatestLocation returns location from latest weather entry`() =
        runTest {
            val entity = createWeatherEntity(today, 70, 50)
            coEvery { weatherDao.getLatestWeather() } returns entity

            val location = repository.getLatestLocation()

            assertNotNull(location)
            assertEquals(testLat, location?.first)
            assertEquals(testLon, location?.second)
        }

    @Test
    fun `getLatestLocation returns null when no weather data`() =
        runTest {
            coEvery { weatherDao.getLatestWeather() } returns null

            val location = repository.getLatestLocation()

            assertNull(location)
        }

    @Test
    fun `rate limit resets when fetch throws exception`() =
        runTest {
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPrefs.edit() } returns editor

            var storedTime = 0L
            every { editor.putLong("last_full_fetch_time", any()) } answers {
                storedTime = secondArg()
                editor
            }
            every { sharedPrefs.getLong("last_full_fetch_time", 0L) } answers { storedTime }

            // Empty cache forces network fetch
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns emptyList()

            // Both APIs throw exceptions
            coEvery { nwsApi.getGridPoint(testLat, testLon) } throws Exception("NWS down")
            coEvery { openMeteoApi.getForecast(testLat, testLon, any()) } throws Exception("Meteo down")

            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

            // Rate limit should be reset to 0 so next attempt isn't blocked
            assertEquals("Rate limit should be reset after exception", 0L, storedTime)
        }

    @Test
    fun `rate limit resets when both APIs fail silently`() =
        runTest {
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPrefs.edit() } returns editor

            var storedTime = 0L
            every { editor.putLong("last_full_fetch_time", any()) } answers {
                storedTime = secondArg()
                editor
            }
            every { sharedPrefs.getLong("last_full_fetch_time", 0L) } answers { storedTime }

            // Stale cache forces network fetch
            val staleData =
                listOf(
                    createWeatherEntity(today, 70, 50).copy(
                        fetchedAt = System.currentTimeMillis() - 3 * 60 * 60 * 1000, // 3 hours old
                    ),
                )
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns staleData

            // All APIs throw (fetchFromAllApis catches these and returns null for each)
            coEvery { nwsApi.getGridPoint(testLat, testLon) } throws Exception("NWS timeout")
            coEvery { openMeteoApi.getForecast(testLat, testLon, any()) } throws Exception("Meteo timeout")

            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

            // Rate limit should be restored to previous value (0), not stuck at current time
            assertEquals("Rate limit should be reset when both APIs fail", 0L, storedTime)
        }

    @Test
    fun `rate limit preserved on successful fetch`() =
        runTest {
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPrefs.edit() } returns editor

            var storedTime = 0L
            every { editor.putLong("last_full_fetch_time", any()) } answers {
                storedTime = secondArg()
                editor
            }
            every { sharedPrefs.getLong("last_full_fetch_time", 0L) } answers { storedTime }

            // Empty cache forces network fetch
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns emptyList() andThen
                listOf(
                    createWeatherEntity(today, 70, 50),
                )
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, any()) } returns emptyList()

            val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com")
            coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
            coEvery { nwsApi.getForecast(gridPoint) } returns
                listOf(
                    NwsApi.ForecastPeriod("Today", "${today}T06:00:00-08:00", 70, "F", "Sunny", true),
                )
            coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()
            coEvery { openMeteoApi.getForecast(testLat, testLon, any()) } throws Exception("Skipped")

            val beforeFetch = System.currentTimeMillis()
            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

            // Rate limit should be set to approximately now (not reset to 0)
            assertTrue("Rate limit should be set after successful fetch", storedTime >= beforeFetch)
        }

    @Test
    fun `fetchFromNws prioritizes hourly precip probability for daily POP`() =
        runTest {
            // Setup
            val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com/forecast")
            coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
            
            // Daily forecast has 14% (e.g. Saturday Night)
            coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
                NwsApi.ForecastPeriod("Saturday Night", "${today}T18:00:00-08:00", 60, "F", "Cloudy", false, 14)
            )
            
            // Hourly forecast for the same day has max 5%
            coEvery { nwsApi.getHourlyForecast(gridPoint) } returns listOf(
                NwsApi.HourlyForecastPeriod("${today}T18:00:00-08:00", 60f, "Cloudy", 2),
                NwsApi.HourlyForecastPeriod("${today}T21:00:00-08:00", 58f, "Cloudy", 5),
                NwsApi.HourlyForecastPeriod("${today}T23:00:00-08:00", 56f, "Cloudy", 4)
            )

            // Act - call the internal method directly to test its parsing logic
            val result = repository.fetchFromNws(testLat, testLon, testLocationName)

            // Assert
            val todayEntry = result.find { it.date == today }
            assertNotNull("Should have entry for today", todayEntry)
            assertEquals("Daily POP should match the max from hourly data (5%), not the period POP (14%)", 5, todayEntry?.precipProbability)
        }

    @Test
    fun `climate normals cache hit returns Room data without API call`() =
        runTest {
            // Pre-populate DAO with cached normals for the rounded location key
            val roundedLat = (testLat * 10).roundToInt() / 10.0
            val roundedLon = (testLon * 10).roundToInt() / 10.0
            val locationKey = "${roundedLat}_${roundedLon}"

            val cachedNormals = listOf(
                ClimateNormalEntity("07-15", locationKey, 85, 60),
                ClimateNormalEntity("07-16", locationKey, 86, 61),
            )
            coEvery { climateNormalDao.getNormalsForLocation(locationKey) } returns cachedNormals

            // Setup a successful NWS fetch that only covers today (so gap fill is triggered)
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns emptyList() andThen
                listOf(createWeatherEntity(today, 70, 50))
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, any()) } returns emptyList()

            val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com")
            coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
            coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
                NwsApi.ForecastPeriod("Today", "${today}T06:00:00-08:00", 70, "F", "Sunny", true),
            )
            coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()
            coEvery { openMeteoApi.getForecast(testLat, testLon, any()) } throws Exception("Skipped")

            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

            // Climate API should NOT have been called — cache was sufficient
            coVerify(exactly = 0) { openMeteoApi.getClimateForecast(any(), any(), any(), any()) }
            // Room cache WAS read
            coVerify { climateNormalDao.getNormalsForLocation(locationKey) }
        }

    @Test
    fun `climate normals cache miss fetches from Climate API and persists`() =
        runTest {
            val roundedLat = (testLat * 10).roundToInt() / 10.0
            val roundedLon = (testLon * 10).roundToInt() / 10.0
            val locationKey = "${roundedLat}_${roundedLon}"

            // DAO returns empty — cache miss
            coEvery { climateNormalDao.getNormalsForLocation(locationKey) } returns emptyList()

            // Climate API returns two days of normals
            coEvery { openMeteoApi.getClimateForecast(roundedLat, roundedLon, any(), any()) } returns listOf(
                OpenMeteoApi.DailyForecast("2020-07-15", 85f, 60f, 0),
                OpenMeteoApi.DailyForecast("2020-07-16", 86f, 61f, 0),
            )

            // Setup a successful NWS fetch that only covers today
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns emptyList() andThen
                listOf(createWeatherEntity(today, 70, 50))
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, any()) } returns emptyList()

            val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com")
            coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
            coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
                NwsApi.ForecastPeriod("Today", "${today}T06:00:00-08:00", 70, "F", "Sunny", true),
            )
            coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()
            coEvery { openMeteoApi.getForecast(testLat, testLon, any()) } throws Exception("Skipped")

            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

            // Climate API should have been called
            coVerify { openMeteoApi.getClimateForecast(roundedLat, roundedLon, "2020-01-01", "2020-12-31") }
            // Old location data should be cleared, new normals persisted
            coVerify { climateNormalDao.deleteOtherLocations(locationKey) }
            coVerify { climateNormalDao.insertAll(match { it.size == 2 && it.all { e -> e.locationKey == locationKey } }) }
        }

    @Test
    fun `refreshCurrentTemperature updates only current temp on existing today row`() =
        runTest {
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPrefs.edit() } returns editor
            every { editor.putLong("last_current_temp_fetch_time", any()) } returns editor
            every { sharedPrefs.getLong("last_current_temp_fetch_time", 0L) } returns 0L

            coEvery { openMeteoApi.getCurrent(any(), any()) } returns OpenMeteoApi.CurrentReading(61.2f, 1)
            every { openMeteoApi.weatherCodeToCondition(any()) } returns "Mostly Clear"

            val result =
                repository.refreshCurrentTemperature(
                    testLat,
                    testLon,
                    testLocationName,
                    source = WeatherSource.OPEN_METEO,
                    reason = "test",
                )

            assertTrue(result.isSuccess)
            coVerify(exactly = 5) { openMeteoApi.getCurrent(any(), any()) }
            coVerify {
                currentTempDao.insert(
                    match { entity ->
                        entity.source == WeatherSource.OPEN_METEO.id &&
                            entity.temperature == 61.2f
                    },
                )
            }
        }

    @Test
    fun `refreshCurrentTemperature continues when one source fails`() =
        runTest {
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPrefs.edit() } returns editor
            every { editor.putLong("last_current_temp_fetch_time", any()) } returns editor
            every { sharedPrefs.getLong("last_current_temp_fetch_time", 0L) } returns 0L

            every { widgetStateManager.getVisibleSourcesOrder() } returns
                listOf(WeatherSource.WEATHER_API, WeatherSource.OPEN_METEO)
            coEvery { weatherApi.getCurrent(any(), any()) } throws IllegalStateException("Missing key")
            coEvery { openMeteoApi.getCurrent(any(), any()) } returns OpenMeteoApi.CurrentReading(59.5f, 2)
            every { openMeteoApi.weatherCodeToCondition(any()) } returns "Partly Cloudy"

            val meteoExisting = createWeatherEntity(today, 68, 49, source = WeatherSource.OPEN_METEO.id)
            coEvery {
                weatherDao.getWeatherForDateBySource(
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns meteoExisting

            val result = repository.refreshCurrentTemperature(testLat, testLon, testLocationName, reason = "test")

            assertTrue(result.isSuccess)
            coVerify(exactly = 5) { weatherApi.getCurrent(any(), any()) }
            coVerify(exactly = 5) { openMeteoApi.getCurrent(any(), any()) }
            coVerify(exactly = 1) {
                currentTempDao.insert(
                    match { entity ->
                        entity.source == WeatherSource.OPEN_METEO.id && entity.temperature == 59.5f
                    },
                )
            }
        }

    @Test
    fun `saveForecastSnapshot deduplicates identical consecutive forecasts`() =
        runTest {
            // 1. Setup: NWS API returns a forecast
            val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com")
            coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
            val apiForecast =
                listOf(
                    NwsApi.ForecastPeriod("Today", "${today}T06:00:00-08:00", 72, "F", "Sunny", true),
                )
            coEvery { nwsApi.getForecast(gridPoint) } returns apiForecast
            coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()
            coEvery { openMeteoApi.getForecast(any(), any(), any()) } throws Exception("Skipped")

            // 2. Mock existing snapshot in DB that is IDENTICAL to the new forecast
            val existingSnapshot =
                com.weatherwidget.data.local.ForecastSnapshotEntity(
                    targetDate = today,
                    forecastDate = yesterday,
                    locationLat = testLat,
                    locationLon = testLon,
                    highTemp = 72.0f,
                    lowTemp = null,
                    condition = "Sunny",
                    source = "NWS",
                    fetchedAt = System.currentTimeMillis() - 3600000,
                )
            coEvery { forecastSnapshotDao.getForecastsInRange(any(), any(), testLat, testLon) } returns listOf(existingSnapshot)

            // 3. Act: Trigger a fetch
            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

            // 4. Assert: Verify that NO new snapshots were saved (because it was identical)
            // Note: The snapshots list passed to insertAll should be empty or insertAll shouldn't be called for snapshots
            coVerify(exactly = 0) { forecastSnapshotDao.insertAll(any()) }
            coVerify { appLogDao.insert(match { it.tag == "SNAPSHOT_SKIP" }) }

            // 5. Act: Change the forecast and fetch again
            val changedForecast =
                listOf(
                    NwsApi.ForecastPeriod("Today", "${today}T06:00:00-08:00", 75, "F", "Cloudy", true),
                )
            coEvery { nwsApi.getForecast(gridPoint) } returns changedForecast

            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

            // 6. Assert: Verify that the CHANGED snapshot was saved
            coVerify(exactly = 1) {
                forecastSnapshotDao.insertAll(
                    match { list ->
                        list.size == 1 && list[0].highTemp?.toInt() == 75 && list[0].condition == "Cloudy"
                    },
                )
            }
            coVerify { appLogDao.insert(match { it.tag == "SNAPSHOT_SAVE" }) }
        }

    private fun createWeatherEntity(
        date: String,
        high: Int,
        low: Int,
        isActual: Boolean = false,
        source: String = "NWS",
    ) = WeatherEntity(
        date = date,
        locationLat = testLat,
        locationLon = testLon,
        locationName = testLocationName,
        highTemp = high.toFloat(),
        lowTemp = low.toFloat(),
        condition = "Sunny",
        isActual = isActual,
        source = source,
        stationId = null,
        fetchedAt = System.currentTimeMillis(),
    )
}
