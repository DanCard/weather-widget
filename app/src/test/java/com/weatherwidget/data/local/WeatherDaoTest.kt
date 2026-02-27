package com.weatherwidget.data.local

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

@RunWith(RobolectricTestRunner::class)
class WeatherDaoTest {
    private lateinit var db: WeatherDatabase
    private lateinit var dao: WeatherDao

    @Before
    fun setup() {
        db = TestDatabase.create()
        dao = db.weatherDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and retrieve by date`() = runTest {
        val entity = TestData.weather(date = "2026-02-20", source = "NWS")
        dao.insertWeather(entity)

        val result = dao.getWeatherForDate("2026-02-20", LAT, LON)
        assertNotNull(result)
        assertEquals("NWS", result!!.source)
        assertEquals(65f, result.highTemp)
    }

    @Test
    fun `composite key allows both NWS and OpenMeteo for same date`() = runTest {
        dao.insertWeather(TestData.weather(date = "2026-02-20", source = "NWS", highTemp = 65f))
        dao.insertWeather(TestData.weather(date = "2026-02-20", source = "OPEN_METEO", highTemp = 67f))

        val nws = dao.getWeatherForDateBySource("2026-02-20", LAT, LON, "NWS")
        val meteo = dao.getWeatherForDateBySource("2026-02-20", LAT, LON, "OPEN_METEO")

        assertNotNull(nws)
        assertNotNull(meteo)
        assertEquals(65f, nws!!.highTemp)
        assertEquals(67f, meteo!!.highTemp)
    }

    @Test
    fun `REPLACE strategy overwrites same date+source`() = runTest {
        dao.insertWeather(TestData.weather(date = "2026-02-20", source = "NWS", highTemp = 65f))
        dao.insertWeather(TestData.weather(date = "2026-02-20", source = "NWS", highTemp = 70f))

        val result = dao.getWeatherForDateBySource("2026-02-20", LAT, LON, "NWS")
        assertEquals(70f, result!!.highTemp)
    }

    @Test
    fun `getWeatherRange returns ordered results`() = runTest {
        dao.insertWeather(TestData.weather(date = "2026-02-22"))
        dao.insertWeather(TestData.weather(date = "2026-02-20"))
        dao.insertWeather(TestData.weather(date = "2026-02-21"))

        val range = dao.getWeatherRange("2026-02-20", "2026-02-22", LAT, LON)
        assertEquals(3, range.size)
        assertEquals("2026-02-20", range[0].date)
        assertEquals("2026-02-22", range[2].date)
    }

    @Test
    fun `getWeatherRangeBySource filters by source`() = runTest {
        dao.insertWeather(TestData.weather(date = "2026-02-20", source = "NWS"))
        dao.insertWeather(TestData.weather(date = "2026-02-20", source = "OPEN_METEO"))
        dao.insertWeather(TestData.weather(date = "2026-02-21", source = "NWS"))

        val nwsOnly = dao.getWeatherRangeBySource("2026-02-20", "2026-02-21", LAT, LON, "NWS")
        assertEquals(2, nwsOnly.size)
        assertTrue(nwsOnly.all { it.source == "NWS" })
    }

    @Test
    fun `location proximity filtering works within 0_1 degree`() = runTest {
        dao.insertWeather(TestData.weather(lat = 37.42, lon = -122.08))

        // Within 0.1 degree — should match
        val nearby = dao.getWeatherForDate("2026-02-20", 37.43, -122.07)
        assertNotNull(nearby)

        // Outside 0.1 degree — should NOT match
        val far = dao.getWeatherForDate("2026-02-20", 38.0, -122.08)
        assertNull(far)
    }

    @Test
    fun `deleteOldData removes entries before cutoff`() = runTest {
        val old = System.currentTimeMillis() - 100_000
        val recent = System.currentTimeMillis()
        dao.insertWeather(TestData.weather(date = "2026-02-18", fetchedAt = old))
        dao.insertWeather(TestData.weather(date = "2026-02-20", fetchedAt = recent))

        dao.deleteOldData(System.currentTimeMillis() - 50_000)

        assertNull(dao.getWeatherForDate("2026-02-18", LAT, LON))
        assertNotNull(dao.getWeatherForDate("2026-02-20", LAT, LON))
    }

    @Test
    fun `nullable temperatures are stored and retrieved correctly`() = runTest {
        dao.insertWeather(TestData.weather(highTemp = null, lowTemp = null))
        val result = dao.getWeatherForDate("2026-02-20", LAT, LON)
        assertNull(result!!.highTemp)
        assertNull(result.lowTemp)
    }
}
