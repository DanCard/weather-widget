package com.weatherwidget.data.local

import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetConstants
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.format.DateTimeFormatter



@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ForecastSnapshotDaoTest {
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
    fun `composite key allows multiple snapshots per target date with different fetchedAt`() = runTest {
        val base = TestData.forecast(targetDate = "2026-02-21", forecastDate = "2026-02-20")
        dao.insertForecast(base.copy(fetchedAt = 1000L, highTemp = 65f))
        dao.insertForecast(base.copy(fetchedAt = 2000L, highTemp = 68f))

        assertEquals(2, dao.getCount())
    }

    @Test
    fun `getForecastForDate returns most recent forecast date first`() = runTest {
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-21", forecastDate = "2026-02-19", fetchedAt = 1000L))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-21", forecastDate = "2026-02-20", fetchedAt = 2000L, highTemp = 70f))

        val result = dao.getForecastForDate(dateEpoch("2026-02-21"), LAT, LON)
        assertNotNull(result)
        assertEquals(dateEpoch("2026-02-20"), result!!.forecastDate)
        assertEquals(70f, result.highTemp)
    }

    @Test
    fun `getForecastsInRange returns all snapshots within window`() = runTest {
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-19"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-20"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-21"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-22"))

        val range = dao.getForecastsInRange(dateEpoch("2026-02-20"), dateEpoch("2026-02-21"), LAT, LON)
        assertEquals(2, range.size)
        assertTrue(range.all { it.targetDate in listOf(dateEpoch("2026-02-20"), dateEpoch("2026-02-21")) })
    }

    @Test
    fun `getForecastsInRange excludes dates outside window`() = runTest {
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-18"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-20"))

        val range = dao.getForecastsInRange(dateEpoch("2026-02-19"), dateEpoch("2026-02-19"), LAT, LON)
        assertEquals(0, range.size)
    }

    @Test
    fun `getForecastForDateBySource filters by source`() = runTest {
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-21", forecastDate = "2026-02-20", source = "NWS", highTemp = 65f, fetchedAt = 1000L))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-21", forecastDate = "2026-02-20", source = "OPEN_METEO", highTemp = 67f, fetchedAt = 1000L))

        val nws = dao.getForecastForDateBySource(dateEpoch("2026-02-21"), dateEpoch("2026-02-20"), LAT, LON, "NWS")
        assertEquals(65f, nws!!.highTemp)

        val meteo = dao.getForecastForDateBySource(dateEpoch("2026-02-21"), dateEpoch("2026-02-20"), LAT, LON, "OPEN_METEO")
        assertEquals(67f, meteo!!.highTemp)
    }

    @Test
    fun `getForecastEvolution returns chronological order`() = runTest {
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-21", forecastDate = "2026-02-19", fetchedAt = 1000L))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-21", forecastDate = "2026-02-18", fetchedAt = 500L))
        dao.insertForecast(TestData.forecast(targetDate = "2026-02-21", forecastDate = "2026-02-20", fetchedAt = 2000L))

        val evolution = dao.getForecastEvolution(dateEpoch("2026-02-21"), LAT, LON)
        assertEquals(3, evolution.size)
        assertEquals(dateEpoch("2026-02-18"), evolution[0].forecastDate)
        assertEquals(dateEpoch("2026-02-20"), evolution[2].forecastDate)
    }

    // --- Regression tests for past-day forecast bar bug ---
    // These guard the deduped query (used to bound CursorWindow row count) and the
    // production fetch pattern that replaced the over-narrow today-1..today+7 range.

    @Test
    fun `getLatestForecastsInRangeForSources returns latest batch per source per date`() = runTest {
        // Two batches for the same date+source — only the newer batchFetchedAt should survive
        dao.insertForecast(TestData.forecast(targetDate = "2026-05-06", source = "NWS",
            batchFetchedAt = 1000L, fetchedAt = 1000L, highTemp = 70f))
        dao.insertForecast(TestData.forecast(targetDate = "2026-05-06", source = "NWS",
            batchFetchedAt = 2000L, fetchedAt = 2000L, highTemp = 72f))
        // Different source on the same date — kept independently
        dao.insertForecast(TestData.forecast(targetDate = "2026-05-06", source = "OPEN_METEO",
            batchFetchedAt = 1500L, fetchedAt = 1500L, highTemp = 73f))

        val rows = dao.getLatestForecastsInRangeForSources(
            dateEpoch("2026-05-06"), dateEpoch("2026-05-06"),
            LAT, LON, listOf("NWS", "OPEN_METEO"))

        assertEquals(2, rows.size)
        assertEquals(72f, rows.first { it.source == "NWS" }.highTemp)
        assertEquals(73f, rows.first { it.source == "OPEN_METEO" }.highTemp)
    }

    @Test
    fun `getLatestForecastsInRangeForSources excludes sources not in list`() = runTest {
        dao.insertForecast(TestData.forecast(targetDate = "2026-05-06", source = "NWS"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-05-06", source = "TOMORROW_IO"))

        val rows = dao.getLatestForecastsInRangeForSources(
            dateEpoch("2026-05-06"), dateEpoch("2026-05-06"),
            LAT, LON, listOf("NWS"))

        assertEquals(1, rows.size)
        assertEquals("NWS", rows[0].source)
    }

    @Test
    fun `getLatestForecastsInRangeForSources respects date range bounds`() = runTest {
        dao.insertForecast(TestData.forecast(targetDate = "2026-05-05", source = "NWS"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-05-06", source = "NWS"))
        dao.insertForecast(TestData.forecast(targetDate = "2026-05-07", source = "NWS"))

        val rows = dao.getLatestForecastsInRangeForSources(
            dateEpoch("2026-05-06"), dateEpoch("2026-05-06"),
            LAT, LON, listOf("NWS"))

        assertEquals(1, rows.size)
        assertEquals(dateEpoch("2026-05-06"), rows[0].targetDate)
    }

    @Test
    fun `worker fetch pattern covers full 30-day past navigation window`() = runTest {
        // Mimics the production two-query merge in WeatherWidgetWorker.fetchForecastSnapshots:
        //   getLatestForecastsInRange(today-30 .. today-2)  +  getAllForecastsInRange(today-1 .. today+7)
        // and asserts every day in the navigation window is present in the merged map.
        // Regression guard: the original bug fetched only today-1..today+7, leaving past
        // dates absent and triggering the climate-normal fallback that drew bars too low.
        val today = LocalDate.of(2026, 5, 9)
        for (offset in -30L..7L) {
            val date = today.plusDays(offset)
            dao.insertForecast(TestData.forecast(
                targetDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                forecastDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                source = "NWS",
                batchFetchedAt = 1000L + offset,
                fetchedAt = 1000L + offset,
            ))
        }

        val pastStart = today.minusDays(30).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val pastEnd = today.minusDays(2).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val recentStart = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val recentEnd = today.plusDays(7).toEpochDay() * WidgetConstants.MS_IN_A_DAY

        val past = dao.getLatestForecastsInRange(pastStart, pastEnd, LAT, LON)
        val recent = dao.getAllForecastsInRange(recentStart, recentEnd, LAT, LON)
        val byDate = (past + recent).groupBy {
            LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY)
        }

        for (offset in -30L..7L) {
            val date = today.plusDays(offset)
            assertTrue(
                "Missing snapshot for $date — narrow fetch range regression",
                byDate.containsKey(date),
            )
        }
    }
}
