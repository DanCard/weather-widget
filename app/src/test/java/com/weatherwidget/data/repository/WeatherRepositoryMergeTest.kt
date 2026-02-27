package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
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

@RunWith(RobolectricTestRunner::class)
class WeatherRepositoryMergeTest {
    private lateinit var db: WeatherDatabase
    private lateinit var weatherDao: WeatherDao
    private lateinit var repository: WeatherRepository

    private val yesterday = LocalDate.now().minusDays(1).toString()
    private val today = LocalDate.now().toString()

    @Before
    fun setup() {
        db = TestDatabase.create()
        weatherDao = db.weatherDao()
        val context = RuntimeEnvironment.getApplication()
        val forecastRepo = ForecastRepository(context, weatherDao, db.forecastSnapshotDao(), db.hourlyForecastDao(), db.appLogDao(), mockk(), mockk(), mockk(), mockk(relaxed = true), db.climateNormalDao())
        val currentRepo = CurrentTempRepository(context, db.currentTempDao(), db.weatherObservationDao(), db.hourlyForecastDao(), db.appLogDao(), mockk(), mockk(), mockk(), mockk(relaxed = true), TemperatureInterpolator())
        repository = WeatherRepository(context, forecastRepo, currentRepo, weatherDao, db.forecastSnapshotDao(), db.appLogDao(), db.currentTempDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `actual observation preserved when forecast arrives for same date`() = runTest {
        weatherDao.insertWeather(TestData.weather(date = yesterday, source = "NWS", highTemp = 62f, lowTemp = 44f, isActual = true, stationId = "KSFO"))
        val merged = repository.mergeWithExisting(listOf(TestData.weather(date = yesterday, source = "NWS", highTemp = 60f, lowTemp = 42f, isActual = false)), LAT, LON)
        assertEquals(1, merged.size)
        assertTrue("Actual flag should be preserved", merged[0].isActual)
        assertEquals(60f, merged[0].highTemp)
    }

    @Test
    fun `placeholder Observed condition gets overwritten by real forecast`() = runTest {
        weatherDao.insertWeather(TestData.weather(date = today, source = "NWS", condition = "Observed", isActual = true, highTemp = 60f))
        val merged = repository.mergeWithExisting(listOf(TestData.weather(date = today, source = "NWS", condition = "Sunny", isActual = false, highTemp = 65f)), LAT, LON)
        assertEquals("Sunny", merged[0].condition)
    }
}
