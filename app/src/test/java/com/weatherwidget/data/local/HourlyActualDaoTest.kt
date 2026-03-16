package com.weatherwidget.data.local

import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HourlyActualDaoTest {
    private lateinit var db: WeatherDatabase
    private lateinit var dao: HourlyActualDao

    @Before
    fun setup() {
        db = TestDatabase.create()
        dao = db.hourlyActualDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getActualsInRange returns only rows within time window`() = runTest {
        dao.insertAll(listOf(
            TestData.hourlyActual(dateTime = "2026-02-20T09:00", temperature = 50f),
            TestData.hourlyActual(dateTime = "2026-02-20T11:00", temperature = 55f),
            TestData.hourlyActual(dateTime = "2026-02-20T13:00", temperature = 60f),
        ))

        val result = dao.getActualsInRange("2026-02-20T10:00", "2026-02-20T12:00", "NWS", TestData.LAT, TestData.LON)

        assertEquals(1, result.size)
        assertEquals("2026-02-20T11:00", result[0].dateTime)
    }

    @Test
    fun `range bounds are inclusive`() = runTest {
        dao.insertAll(listOf(
            TestData.hourlyActual(dateTime = "2026-02-20T10:00"),
            TestData.hourlyActual(dateTime = "2026-02-20T12:00"),
        ))

        val result = dao.getActualsInRange("2026-02-20T10:00", "2026-02-20T12:00", "NWS", TestData.LAT, TestData.LON)

        assertEquals(2, result.size)
    }

    @Test
    fun `source filter returns only matching source`() = runTest {
        dao.insertAll(listOf(
            TestData.hourlyActual(dateTime = "2026-02-20T12:00", source = "NWS", temperature = 60f),
            TestData.hourlyActual(dateTime = "2026-02-20T12:00", source = "OPEN_METEO", temperature = 62f),
        ))

        val result = dao.getActualsInRange("2026-02-20T11:00", "2026-02-20T13:00", "NWS", TestData.LAT, TestData.LON)

        assertEquals(1, result.size)
        assertEquals("NWS", result[0].source)
        assertEquals(60f, result[0].temperature)
    }

    @Test
    fun `lat lon proximity within 0_1 degree returns row`() = runTest {
        dao.insertAll(listOf(TestData.hourlyActual(dateTime = "2026-02-20T12:00")))

        // Query from 0.09 degrees away — within ±0.1 tolerance
        val result = dao.getActualsInRange(
            "2026-02-20T11:00", "2026-02-20T13:00", "NWS",
            lat = TestData.LAT + 0.09,
            lon = TestData.LON + 0.09,
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `lat lon too far away returns empty`() = runTest {
        dao.insertAll(listOf(TestData.hourlyActual(dateTime = "2026-02-20T12:00")))

        // Query from 0.15 degrees away — outside ±0.1 tolerance
        val result = dao.getActualsInRange(
            "2026-02-20T11:00", "2026-02-20T13:00", "NWS",
            lat = TestData.LAT + 0.15,
            lon = TestData.LON,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `results are ordered by dateTime ascending`() = runTest {
        dao.insertAll(listOf(
            TestData.hourlyActual(dateTime = "2026-02-20T14:00"),
            TestData.hourlyActual(dateTime = "2026-02-20T10:00"),
            TestData.hourlyActual(dateTime = "2026-02-20T12:00"),
        ))

        val result = dao.getActualsInRange("2026-02-20T09:00", "2026-02-20T15:00", "NWS", TestData.LAT, TestData.LON)

        assertEquals(3, result.size)
        assertEquals("2026-02-20T10:00", result[0].dateTime)
        assertEquals("2026-02-20T12:00", result[1].dateTime)
        assertEquals("2026-02-20T14:00", result[2].dateTime)
    }

    @Test
    fun `upsert replaces row on composite key conflict`() = runTest {
        dao.insertAll(listOf(TestData.hourlyActual(dateTime = "2026-02-20T12:00", temperature = 68f)))
        dao.insertAll(listOf(TestData.hourlyActual(dateTime = "2026-02-20T12:00", temperature = 72f)))

        val result = dao.getActualsInRange("2026-02-20T11:00", "2026-02-20T13:00", "NWS", TestData.LAT, TestData.LON)

        assertEquals(1, result.size)
        assertEquals(72f, result[0].temperature)
    }

    @Test
    fun `deleteOldActuals removes rows with fetchedAt before cutoff`() = runTest {
        val oldTime = 1_000L
        val newTime = System.currentTimeMillis()
        dao.insertAll(listOf(
            TestData.hourlyActual(dateTime = "2026-02-19T12:00", fetchedAt = oldTime),
            TestData.hourlyActual(dateTime = "2026-02-20T12:00", fetchedAt = newTime),
        ))

        dao.deleteOldActuals(cutoffTime = newTime - 1)

        val remaining = dao.getActualsInRange("2000-01-01T00:00", "2099-12-31T23:00", "NWS", TestData.LAT, TestData.LON)
        assertEquals(1, remaining.size)
        assertEquals("2026-02-20T12:00", remaining[0].dateTime)
    }

    @Test
    fun `empty table range query returns empty list`() = runTest {
        val result = dao.getActualsInRange("2026-02-20T10:00", "2026-02-20T14:00", "NWS", TestData.LAT, TestData.LON)

        assertTrue(result.isEmpty())
    }
}
