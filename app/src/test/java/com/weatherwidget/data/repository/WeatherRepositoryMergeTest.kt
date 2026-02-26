package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.WeatherDao
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Integration tests for WeatherRepository.mergeWithExisting() using a real Room in-memory DB.
 * The merge method is now `internal` so we can call it directly.
 */
@RunWith(RobolectricTestRunner::class)
class WeatherRepositoryMergeTest {
    private lateinit var db: WeatherDatabase
    private lateinit var weatherDao: WeatherDao
    private lateinit var repository: WeatherRepository

    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        db = TestDatabase.create()
        weatherDao = db.weatherDao()

        repository = WeatherRepository(
            RuntimeEnvironment.getApplication(),
            weatherDao,
            db.forecastSnapshotDao(),
            db.hourlyForecastDao(),
            db.appLogDao(),
            mockk(),
            mockk(),
            mockk(),
            mockk<WidgetStateManager>(relaxed = true).also {
                every { it.isSourceVisible(any()) } returns true
            },
            TemperatureInterpolator(),
            db.climateNormalDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `actual observation preserved when forecast arrives for same date`() = runTest {
        // Pre-populate with actual observation
        weatherDao.insertWeather(
            TestData.weather(date = yesterday, source = "NWS", highTemp = 62f, lowTemp = 44f, isActual = true, stationId = "KSFO"),
        )

        // New forecast data for the same date
        val newForecast = listOf(
            TestData.weather(date = yesterday, source = "NWS", highTemp = 60f, lowTemp = 42f, isActual = false),
        )

        val merged = repository.mergeWithExisting(newForecast, LAT, LON)
        assertEquals(1, merged.size)
        // Actual record is preserved (isActual stays true)
        assertTrue("Actual flag should be preserved", merged[0].isActual)
        // Merge takes new non-null temps even when preserving actual flag
        assertEquals(60f, merged[0].highTemp)
    }

    @Test
    fun `new forecast fills null temps from existing`() = runTest {
        weatherDao.insertWeather(
            TestData.weather(date = today, source = "NWS", highTemp = 70f, lowTemp = 50f),
        )

        val newData = listOf(
            TestData.weather(date = today, source = "NWS", highTemp = null, lowTemp = 52f),
        )

        val merged = repository.mergeWithExisting(newData, LAT, LON)
        assertEquals(70f, merged[0].highTemp) // filled from existing
        assertEquals(52f, merged[0].lowTemp)  // used new (non-null)
    }

    @Test
    fun `dates only in DB are preserved through merge`() = runTest {
        weatherDao.insertWeather(TestData.weather(date = yesterday, source = "NWS"))
        weatherDao.insertWeather(TestData.weather(date = today, source = "NWS"))

        // API only returns today and tomorrow
        val newData = listOf(
            TestData.weather(date = today, source = "NWS", highTemp = 72f),
            TestData.weather(date = tomorrow, source = "NWS", highTemp = 68f),
        )

        val merged = repository.mergeWithExisting(newData, LAT, LON)
        val dates = merged.map { it.date }.toSet()
        assertTrue("Yesterday should be preserved", yesterday in dates)
        assertTrue("Today should be present", today in dates)
        assertTrue("Tomorrow should be added", tomorrow in dates)
        assertEquals(3, merged.size)
    }

    @Test
    fun `identical data is deduplicated`() = runTest {
        val existing = TestData.weather(
            date = today,
            source = "NWS",
            highTemp = 65f,
            lowTemp = 45f,
            condition = "Sunny",
            fetchedAt = 1000L,
        )
        weatherDao.insertWeather(existing)

        val identical = listOf(existing.copy(fetchedAt = 2000L))
        val merged = repository.mergeWithExisting(identical, LAT, LON)

        // Should return the existing (older fetchedAt) since data is unchanged
        assertEquals(1000L, merged[0].fetchedAt)
    }

    @Test
    fun `placeholder Observed condition gets overwritten by real forecast`() = runTest {
        weatherDao.insertWeather(
            TestData.weather(date = today, source = "NWS", condition = "Observed", isActual = true, highTemp = 60f),
        )

        val newData = listOf(
            TestData.weather(date = today, source = "NWS", condition = "Sunny", isActual = false, highTemp = 65f),
        )

        val merged = repository.mergeWithExisting(newData, LAT, LON)
        assertEquals("Sunny", merged[0].condition)
        assertEquals(65f, merged[0].highTemp)
    }

    @Test
    fun `merge handles multiple sources independently`() = runTest {
        weatherDao.insertWeather(TestData.weather(date = today, source = "NWS", highTemp = 65f))
        weatherDao.insertWeather(TestData.weather(date = today, source = "OPEN_METEO", highTemp = 67f))

        val newData = listOf(
            TestData.weather(date = today, source = "NWS", highTemp = 70f),
        )

        val merged = repository.mergeWithExisting(newData, LAT, LON)
        // Only NWS entries should be in the merge result (merge groups by source)
        assertTrue(merged.all { it.source == "NWS" })
        assertEquals(70f, merged.first { it.date == today }.highTemp)
    }
}
