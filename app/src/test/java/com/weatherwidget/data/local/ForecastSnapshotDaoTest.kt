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
class ForecastSnapshotDaoTest {
    private lateinit var db: WeatherDatabase
    private lateinit var dao: ForecastSnapshotDao

    @Before
    fun setup() {
        db = TestDatabase.create()
        dao = db.forecastSnapshotDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `composite key allows multiple snapshots per target date with different fetchedAt`() = runTest {
        val base = TestData.forecastSnapshot(targetDate = "2026-02-21", forecastDate = "2026-02-20")
        dao.insertSnapshot(base.copy(fetchedAt = 1000L, highTemp = 65f))
        dao.insertSnapshot(base.copy(fetchedAt = 2000L, highTemp = 68f))

        assertEquals(2, dao.getCount())
    }

    @Test
    fun `getForecastForDate returns most recent forecast date first`() = runTest {
        // Forecast made on Feb 19 (2-day ahead)
        dao.insertSnapshot(TestData.forecastSnapshot(targetDate = "2026-02-21", forecastDate = "2026-02-19", fetchedAt = 1000L))
        // Forecast made on Feb 20 (1-day ahead) — should win
        dao.insertSnapshot(TestData.forecastSnapshot(targetDate = "2026-02-21", forecastDate = "2026-02-20", fetchedAt = 2000L, highTemp = 70f))

        val result = dao.getForecastForDate("2026-02-21", LAT, LON)
        assertNotNull(result)
        assertEquals("2026-02-20", result!!.forecastDate)
        assertEquals(70f, result.highTemp)
    }

    @Test
    fun `getForecastsInRange returns all snapshots within window`() = runTest {
        dao.insertSnapshot(TestData.forecastSnapshot(targetDate = "2026-02-19"))
        dao.insertSnapshot(TestData.forecastSnapshot(targetDate = "2026-02-20"))
        dao.insertSnapshot(TestData.forecastSnapshot(targetDate = "2026-02-21"))
        dao.insertSnapshot(TestData.forecastSnapshot(targetDate = "2026-02-22"))

        val range = dao.getForecastsInRange("2026-02-20", "2026-02-21", LAT, LON)
        assertEquals(2, range.size)
        assertTrue(range.all { it.targetDate in listOf("2026-02-20", "2026-02-21") })
    }

    @Test
    fun `getForecastsInRange excludes dates outside window`() = runTest {
        dao.insertSnapshot(TestData.forecastSnapshot(targetDate = "2026-02-18"))
        dao.insertSnapshot(TestData.forecastSnapshot(targetDate = "2026-02-20"))

        val range = dao.getForecastsInRange("2026-02-19", "2026-02-19", LAT, LON)
        assertEquals(0, range.size)
    }

    @Test
    fun `getForecastForDateBySource filters by source`() = runTest {
        dao.insertSnapshot(TestData.forecastSnapshot(source = "NWS", highTemp = 65f, fetchedAt = 1000L))
        dao.insertSnapshot(TestData.forecastSnapshot(source = "OPEN_METEO", highTemp = 67f, fetchedAt = 1000L))

        val nws = dao.getForecastForDateBySource("2026-02-21", "2026-02-20", LAT, LON, "NWS")
        assertEquals(65f, nws!!.highTemp)

        val meteo = dao.getForecastForDateBySource("2026-02-21", "2026-02-20", LAT, LON, "OPEN_METEO")
        assertEquals(67f, meteo!!.highTemp)
    }

    @Test
    fun `getForecastEvolution returns chronological order`() = runTest {
        dao.insertSnapshot(TestData.forecastSnapshot(forecastDate = "2026-02-19", fetchedAt = 1000L))
        dao.insertSnapshot(TestData.forecastSnapshot(forecastDate = "2026-02-18", fetchedAt = 500L))
        dao.insertSnapshot(TestData.forecastSnapshot(forecastDate = "2026-02-20", fetchedAt = 2000L))

        val evolution = dao.getForecastEvolution("2026-02-21", LAT, LON)
        assertEquals(3, evolution.size)
        assertEquals("2026-02-18", evolution[0].forecastDate)
        assertEquals("2026-02-20", evolution[2].forecastDate)
    }
}
