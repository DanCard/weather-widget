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
class HourlyForecastDaoTest {
    private lateinit var db: WeatherDatabase
    private lateinit var dao: HourlyForecastDao

    @Before
    fun setup() {
        db = TestDatabase.create()
        dao = db.hourlyForecastDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getHourlyForecasts returns data within time window`() = runTest {
        val forecasts = (10..16).map { hour ->
            TestData.hourly(dateTime = "2026-02-20T${hour}:00", temperature = 50f + hour)
        }
        dao.insertAll(forecasts)

        val result = dao.getHourlyForecasts("2026-02-20T12:00", "2026-02-20T14:00", LAT, LON)
        assertEquals(3, result.size)
        assertEquals("2026-02-20T12:00", result[0].dateTime)
        assertEquals("2026-02-20T14:00", result[2].dateTime)
    }

    @Test
    fun `composite key allows same dateTime from different sources`() = runTest {
        dao.insertAll(
            listOf(
                TestData.hourly(dateTime = "2026-02-20T14:00", source = "NWS", temperature = 60f),
                TestData.hourly(dateTime = "2026-02-20T14:00", source = "OPEN_METEO", temperature = 62f),
            ),
        )

        val all = dao.getHourlyForecasts("2026-02-20T14:00", "2026-02-20T14:00", LAT, LON)
        assertEquals(2, all.size)
    }

    @Test
    fun `getHourlyForecastsBySource filters correctly`() = runTest {
        dao.insertAll(
            listOf(
                TestData.hourly(dateTime = "2026-02-20T14:00", source = "NWS"),
                TestData.hourly(dateTime = "2026-02-20T14:00", source = "OPEN_METEO"),
                TestData.hourly(dateTime = "2026-02-20T15:00", source = "NWS"),
            ),
        )

        val nws = dao.getHourlyForecastsBySource("2026-02-20T14:00", "2026-02-20T15:00", LAT, LON, "NWS")
        assertEquals(2, nws.size)
        assertTrue(nws.all { it.source == "NWS" })
    }

    @Test
    fun `REPLACE updates existing entry for same composite key`() = runTest {
        dao.insertAll(listOf(TestData.hourly(temperature = 60f, fetchedAt = 1000L)))
        dao.insertAll(listOf(TestData.hourly(temperature = 65f, fetchedAt = 2000L)))

        val result = dao.getHourlyForecasts("2026-02-20T14:00", "2026-02-20T14:00", LAT, LON)
        assertEquals(1, result.size)
        assertEquals(65f, result[0].temperature)
    }

    @Test
    fun `deleteOldForecasts removes entries before cutoff`() = runTest {
        dao.insertAll(
            listOf(
                TestData.hourly(dateTime = "2026-02-18T12:00", fetchedAt = 1000L),
                TestData.hourly(dateTime = "2026-02-20T12:00", fetchedAt = 5000L),
            ),
        )

        dao.deleteOldForecasts(3000L)

        val remaining = dao.getHourlyForecasts("2026-02-18T00:00", "2026-02-20T23:00", LAT, LON)
        assertEquals(1, remaining.size)
        assertEquals("2026-02-20T12:00", remaining[0].dateTime)
    }

    @Test
    fun `precipProbability is stored and retrieved`() = runTest {
        dao.insertAll(listOf(TestData.hourly(precipProbability = 80)))

        val result = dao.getHourlyForecasts("2026-02-20T14:00", "2026-02-20T14:00", LAT, LON)
        assertEquals(80, result[0].precipProbability)
    }
}
