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

/**
 * Tests that station fallback logic correctly saves the stationId
 * of whichever station actually provided usable data.
 *
 * Since fetchAndApplyObservations is private and deeply intertwined with
 * NWS API calls, we test at the DAO level: verify that when data with
 * different stationIds is inserted, the correct one persists.
 */
@RunWith(RobolectricTestRunner::class)
class WeatherRepositoryStationFallbackTest {
    private lateinit var db: WeatherDatabase
    private lateinit var weatherDao: WeatherDao

    @Before
    fun setup() {
        db = TestDatabase.create()
        weatherDao = db.weatherDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `stationId is saved and retrievable`() = runTest {
        weatherDao.insertWeather(
            TestData.weather(date = "2026-02-20", source = "NWS", isActual = true, stationId = "KSFO"),
        )

        val result = weatherDao.getWeatherForDateBySource("2026-02-20", LAT, LON, "NWS")
        assertEquals("KSFO", result!!.stationId)
    }

    @Test
    fun `REPLACE strategy updates stationId when fallback station provides data`() = runTest {
        // Station 1 wrote data first
        weatherDao.insertWeather(
            TestData.weather(date = "2026-02-20", source = "NWS", isActual = true, stationId = "KSFO", highTemp = null),
        )

        // Station 2 provides better data (via REPLACE on same composite key)
        weatherDao.insertWeather(
            TestData.weather(date = "2026-02-20", source = "NWS", isActual = true, stationId = "KOAK", highTemp = 62f),
        )

        val result = weatherDao.getWeatherForDateBySource("2026-02-20", LAT, LON, "NWS")
        assertEquals("KOAK", result!!.stationId)
        assertEquals(62f, result.highTemp)
    }

    @Test
    fun `stationId null for non-NWS sources`() = runTest {
        weatherDao.insertWeather(TestData.weather(source = "OPEN_METEO", stationId = null))

        val result = weatherDao.getWeatherForDateBySource("2026-02-20", LAT, LON, "OPEN_METEO")
        assertNull(result!!.stationId)
    }

    @Test
    fun `multiple days can have different stationIds`() = runTest {
        weatherDao.insertWeather(TestData.weather(date = "2026-02-18", stationId = "KSFO"))
        weatherDao.insertWeather(TestData.weather(date = "2026-02-19", stationId = "KOAK"))
        weatherDao.insertWeather(TestData.weather(date = "2026-02-20", stationId = "KSJC"))

        val range = weatherDao.getWeatherRangeBySource("2026-02-18", "2026-02-20", LAT, LON, "NWS")
        assertEquals("KSFO", range[0].stationId)
        assertEquals("KOAK", range[1].stationId)
        assertEquals("KSJC", range[2].stationId)
    }
}
