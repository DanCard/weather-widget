package com.weatherwidget.data.local

import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WeatherDaoTest {
    private lateinit var db: WeatherDatabase
    private lateinit var dao: ForecastDao

    @Before
    fun setup() {
        db = TestDatabase.create()
        dao = db.forecastDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and retrieve by date`() = runTest {
        val entity = TestData.forecast(targetDate = "2026-02-20", source = "NWS")
        dao.insertForecast(entity)

        val result = dao.getForecastForDate(dateEpoch("2026-02-20"), LAT, LON)
        assertNotNull(result)
        assertEquals("NWS", result!!.source)
        assertEquals(65f, result.highTemp)
    }

    @Test
    fun `composite key allows both NWS and OpenMeteo for same date`() = runTest {
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-20", source = "NWS", highTemp = 65f))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-20", source = "OPEN_METEO", highTemp = 67f))

        val nws = dao.getForecastForDateBySource(dateEpoch("2026-02-20"), dateEpoch("2026-02-20"), LAT, LON, "NWS")
        val meteo = dao.getForecastForDateBySource(dateEpoch("2026-02-20"), dateEpoch("2026-02-20"), LAT, LON, "OPEN_METEO")

        assertNotNull(nws)
        assertNotNull(meteo)
        assertEquals(65f, nws!!.highTemp)
        assertEquals(67f, meteo!!.highTemp)
    }

    @Test
    fun `REPLACE strategy overwrites same composite key`() = runTest {
        val ts = 1000L
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-20", source = "NWS", highTemp = 65f, fetchedAt = ts))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-20", source = "NWS", highTemp = 70f, fetchedAt = ts))

        val results = dao.getForecastsInRangeBySource(dateEpoch("2026-02-20"), dateEpoch("2026-02-20"), LAT, LON, "NWS")
        assertEquals(1, results.size)
        assertEquals(70f, results[0].highTemp)
    }

    @Test
    fun `getForecastsInRange returns ordered results`() = runTest {
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-22"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-20"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-21"))

        val range = dao.getForecastsInRange(dateEpoch("2026-02-20"), dateEpoch("2026-02-22"), LAT, LON)
        assertEquals(3, range.size)
        assertEquals(dateEpoch("2026-02-20"), range[0].targetDate)
        assertEquals(dateEpoch("2026-02-22"), range[2].targetDate)
    }

    @Test
    fun `getForecastsInRangeBySource filters by source`() = runTest {
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-20", source = "NWS"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-20", source = "OPEN_METEO"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-21", source = "NWS"))

        val nwsOnly = dao.getForecastsInRangeBySource(dateEpoch("2026-02-20"), dateEpoch("2026-02-21"), LAT, LON, "NWS")
        assertEquals(2, nwsOnly.size)
        assertTrue(nwsOnly.all { it.source == "NWS" })
    }

    @Test
    fun `location proximity filtering works within 0_1 degree`() = runTest {
        dao.insertForecast(TestData.forecast(lat = 37.42, lon = -122.08))

        // Within 0.1 degree — should match
        val nearby = dao.getForecastForDate(dateEpoch("2026-02-20"), 37.43, -122.07)
        assertNotNull(nearby)

        // Outside 0.1 degree — should NOT match
        val far = dao.getForecastForDate(dateEpoch("2026-02-20"), 38.0, -122.08)
        assertNull(far)
    }

    @Test
    fun `deleteOldForecasts removes entries before cutoff`() = runTest {
        val old = System.currentTimeMillis() - 100_000
        val recent = System.currentTimeMillis()
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-18", fetchedAt = old))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-20", fetchedAt = recent))

        dao.deleteOldForecasts(System.currentTimeMillis() - 50_000)

        assertNull(dao.getForecastForDate(dateEpoch("2026-02-18"), LAT, LON))
        assertNotNull(dao.getForecastForDate(dateEpoch("2026-02-20"), LAT, LON))
    }

    @Test
    fun `nullable temperatures are stored and retrieved correctly`() = runTest {
        dao.insertForecast(TestData.forecast(highTemp = null, lowTemp = null))
        val result = dao.getForecastForDate(dateEpoch("2026-02-20"), LAT, LON)
        assertNull(result!!.highTemp)
        assertNull(result.lowTemp)
    }
}
