package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests that observation station data is correctly saved and retrievable.
 * Station data now lives in the observations table (ObservationEntity).
 */
@RunWith(RobolectricTestRunner::class)
class WeatherRepositoryStationFallbackTest {
    private lateinit var db: WeatherDatabase

    @Before
    fun setup() {
        db = TestDatabase.create()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `stationId is saved and retrievable`() = runTest {
        val obs = TestData.observation(stationId = "KSFO", temperature = 62f)
        db.observationDao().insertAll(listOf(obs))

        val result = db.observationDao().getLatestForStation("KSFO")
        assertNotNull(result)
        assertEquals("KSFO", result!!.stationId)
    }

    @Test
    fun `multiple stations can store data for same time window`() = runTest {
        val ts = System.currentTimeMillis()
        db.observationDao().insertAll(listOf(
            TestData.observation(stationId = "KSFO", temperature = 62f, timestamp = ts),
            TestData.observation(stationId = "KOAK", temperature = 58f, timestamp = ts),
            TestData.observation(stationId = "KSJC", temperature = 65f, timestamp = ts),
        ))

        val ksfo = db.observationDao().getLatestForStation("KSFO")
        val koak = db.observationDao().getLatestForStation("KOAK")
        val ksjc = db.observationDao().getLatestForStation("KSJC")

        assertEquals(62f, ksfo!!.temperature)
        assertEquals(58f, koak!!.temperature)
        assertEquals(65f, ksjc!!.temperature)
    }

    @Test
    fun `forecasts are saved without stationId`() = runTest {
        val forecastDao = db.forecastDao()
        forecastDao.insertForecast(TestData.forecast(source = "OPEN_METEO"))

        val results = forecastDao.getForecastsInRangeBySource("2026-02-20", "2026-02-20", LAT, LON, "OPEN_METEO")
        assertEquals(1, results.size)
    }
}
