package com.weatherwidget.data.local

import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Android (Room) half of the [LocationMatch] contract. The desktop (JDBC) half lives in
 * `DesktopWeatherDaoTest` in `:shared`. Both run the same [LocationMatchContract.CASES] so the two
 * hand-maintained persistence layers can't drift on how they match a stored row to the current
 * location — the divergence that once left the desktop cloud-cover graph flat.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class LocationMatchContractTest {
    private lateinit var db: WeatherDatabase
    private lateinit var dao: HourlyForecastDao

    @Before
    fun setup() {
        db = TestDatabase.create()
        dao = db.hourlyForecastDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `room DAO location matching satisfies the shared LocationMatch contract`() = runTest {
        val dateTime = "2026-02-20T14:00"
        val windowStart = TestData.toEpoch("2026-02-20T00:00")
        val windowEnd = TestData.toEpoch("2026-02-21T00:00")

        for (case in LocationMatchContract.CASES) {
            // Isolate each case so a prior row can't satisfy a later "should not match" query.
            dao.deleteOldForecasts(System.currentTimeMillis() + 1_000_000_000L)
            dao.insertAll(
                listOf(TestData.hourly(dateTime = dateTime, lat = case.storedLat, lon = case.storedLon)),
            )
            val rows = dao.getHourlyForecasts(windowStart, windowEnd, case.queryLat, case.queryLon)
            assertEquals(
                "${case.name}: stored(${case.storedLat},${case.storedLon}) " +
                    "query(${case.queryLat},${case.queryLon})",
                case.shouldMatch,
                rows.isNotEmpty(),
            )
        }
    }
}
