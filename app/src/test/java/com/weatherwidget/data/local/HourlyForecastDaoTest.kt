package com.weatherwidget.data.local

import com.weatherwidget.shared.graph.PriorDayCloudForecast
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
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
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
            TestData.hourly(dateTime = String.format("2026-02-20T%02d:00", hour), temperature = 50f + hour)
        }
        dao.insertAll(forecasts)

        val result = dao.getHourlyForecasts(TestData.toEpoch("2026-02-20T12:00"), TestData.toEpoch("2026-02-20T14:00"), LAT, LON)
        assertEquals(3, result.size)
        assertEquals(TestData.toEpoch("2026-02-20T12:00"), result[0].dateTime)
        assertEquals(TestData.toEpoch("2026-02-20T14:00"), result[2].dateTime)
    }

    @Test
    fun `composite key allows same dateTime from different sources`() = runTest {
        dao.insertAll(
            listOf(
                TestData.hourly(dateTime = "2026-02-20T14:00", source = "NWS", temperature = 60f),
                TestData.hourly(dateTime = "2026-02-20T14:00", source = "OPEN_METEO", temperature = 62f),
            ),
        )

        val all = dao.getHourlyForecasts(TestData.toEpoch("2026-02-20T14:00"), TestData.toEpoch("2026-02-20T14:00"), LAT, LON)
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

        val nws = dao.getHourlyForecastsBySource(TestData.toEpoch("2026-02-20T14:00"), TestData.toEpoch("2026-02-20T15:00"), LAT, LON, "NWS")
        assertEquals(2, nws.size)
        assertTrue(nws.all { it.source == "NWS" })
    }

    @Test
    fun `REPLACE updates existing entry for same composite key`() = runTest {
        dao.insertAll(listOf(TestData.hourly(temperature = 60f, fetchedAt = 1000L)))
        dao.insertAll(listOf(TestData.hourly(temperature = 65f, fetchedAt = 2000L)))

        val result = dao.getHourlyForecasts(TestData.toEpoch("2026-02-20T14:00"), TestData.toEpoch("2026-02-20T14:00"), LAT, LON)
        assertEquals(1, result.size)
        assertEquals(65f, result[0].temperature)
    }

    /** The prior-day cloud series shares the table; an all-sources read must not take it for a forecast. */
    @Test
    fun `an all-sources read leaves out the synthetic prior-day source`() = runTest {
        dao.insertAll(
            listOf(
                TestData.hourly(dateTime = "2026-02-20T12:00", source = "NWS"),
                TestData.hourly(dateTime = "2026-02-20T12:00", source = PriorDayCloudForecast.SOURCE_ID),
            ),
        )

        val start = TestData.toEpoch("2026-02-20T00:00")
        val end = TestData.toEpoch("2026-02-21T00:00")
        assertEquals(listOf("NWS"), dao.getHourlyForecasts(start, end, TestData.LAT, TestData.LON).map { it.source })
    }

    /** The one table: an older fetch's row stays stored, and the query returns the newest per hour. */
    @Test
    fun `a later fetch adds a row and reads back as the newest`() = runTest {
        dao.insertAll(listOf(TestData.hourly(dateTime = "2026-02-20T12:00", temperature = 60f, fetchedAt = 1000L)))
        dao.insertAll(listOf(TestData.hourly(dateTime = "2026-02-20T12:00", temperature = 70f, fetchedAt = 5000L)))

        val start = TestData.toEpoch("2026-02-20T00:00")
        val end = TestData.toEpoch("2026-02-21T00:00")
        assertEquals(listOf(70f), dao.getHourlyForecasts(start, end, TestData.LAT, TestData.LON).map { it.temperature })
    }

    @Test
    fun `precipProbability is stored and retrieved`() = runTest {
        dao.insertAll(listOf(TestData.hourly(precipProbability = 80)))

        val result = dao.getHourlyForecasts(TestData.toEpoch("2026-02-20T14:00"), TestData.toEpoch("2026-02-20T14:00"), LAT, LON)
        assertEquals(80, result[0].precipProbability)
    }

    /** plans/261113-nws-day-amount-null-elapsed-hour.md: the desktop twin is `HourlyForecastSqlTest`. */
    @Test
    fun `a newest row with no rain amount reads the newest stored amount on every newest-per-hour read`() = runTest {
        val t = TestData.toEpoch("2026-02-20T14:00")
        fun fetch(at: Long, amount: Float?, temp: Float) =
            TestData.hourly(dateTime = "2026-02-20T14:00", temperature = temp, fetchedAt = at)
                .copy(precipAmountMm = amount, timestampToGroupPredictions = at)
        dao.insertAll(listOf(fetch(1_000L, 0.5f, 60f), fetch(2_000L, 0.08f, 61f), fetch(3_000L, null, 66f)))

        val reads = listOf(
            dao.getHourlyForecasts(t, t, LAT, LON),
            dao.getHourlyForecastsBySource(t, t, LAT, LON, "NWS"),
            dao.getHourlyForecastsForSources(t, t, LAT, LON, listOf("NWS")),
        )
        for (rows in reads) {
            val row = rows.single()
            assertEquals(66f, row.temperature, 0f) // still the newest fetch's row
            assertEquals(0.08f, row.precipAmountMm!!, 0f)
        }
    }

    @Test
    fun `a rain amount of zero is real and an hour never given one stays null`() = runTest {
        val t = TestData.toEpoch("2026-02-20T14:00")
        dao.insertAll(
            listOf(
                TestData.hourly(dateTime = "2026-02-20T14:00", fetchedAt = 1_000L)
                    .copy(precipAmountMm = 0.5f, timestampToGroupPredictions = 1_000L),
                TestData.hourly(dateTime = "2026-02-20T14:00", fetchedAt = 2_000L)
                    .copy(precipAmountMm = 0f, timestampToGroupPredictions = 2_000L),
                TestData.hourly(dateTime = "2026-02-20T15:00", fetchedAt = 2_000L),
            ),
        )

        val rows = dao.getHourlyForecastsBySource(t, t + 3_600_000L, LAT, LON, "NWS")
        assertEquals(0f, rows[0].precipAmountMm!!, 0f)
        assertNull(rows[1].precipAmountMm)
    }
}
