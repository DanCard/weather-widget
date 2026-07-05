package com.weatherwidget.data.repository

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates the storage mechanisms behind the forecast-history cadence cap:
 *  - hourly_forecast_history coalesces within a snapshotBucket (REPLACE) and keeps separate buckets;
 *  - ForecastDao.deleteForecastsInBucket removes only in-bucket rows for the given targets/source.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ForecastHistoryStorageTest {

    private lateinit var db: WeatherDatabase

    private val lat = 37.42
    private val lon = -122.08

    @Before
    fun setup() {
        db = TestDatabase.create()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun historyRow(dateTime: Long, bucket: Long, temp: Float, source: String = "NWS") =
        HourlyForecastHistoryEntity(
            dateTime = dateTime,
            locationLat = lat,
            locationLon = lon,
            temperature = temp,
            condition = "Sunny",
            source = source,
            timestampToGroupPredictions = bucket,
            precipProbability = null,
            cloudCover = 40,
            precipAmountMm = null,
            fetchedAt = bucket + 1000,
        )

    @Test
    fun `same bucket coalesces and keeps latest values - new bucket adds a snapshot`() = runBlocking {
        val dao = db.hourlyForecastHistoryDao()
        val hour = 1_000_000_000_000L
        val bucketA = 4L * 60 * 60 * 1000 * 100

        // Two writes in the SAME bucket for the same hour -> one row, latest temp wins.
        dao.insertAll(listOf(historyRow(hour, bucketA, temp = 50f)))
        dao.insertAll(listOf(historyRow(hour, bucketA, temp = 55f)))
        val afterSameBucket = dao.getHistoryForBucket(hour, hour, lat, lon, "NWS", bucketA)
        assertEquals(1, afterSameBucket.size)
        assertEquals(55f, afterSameBucket.first().temperature, 0.001f)

        // A new bucket for the same hour -> a separate snapshot row.
        val bucketB = bucketA + 4L * 60 * 60 * 1000
        dao.insertAll(listOf(historyRow(hour, bucketB, temp = 60f)))
        assertEquals(1, dao.getHistoryForBucket(hour, hour, lat, lon, "NWS", bucketA).size)
        assertEquals(1, dao.getHistoryForBucket(hour, hour, lat, lon, "NWS", bucketB).size)
        assertEquals(60f, dao.getHistoryForBucket(hour, hour, lat, lon, "NWS", bucketB).first().temperature, 0.001f)
    }

    @Test
    fun `deleteForecastsInBucket removes only in-window rows for the given targets and source`() = runBlocking {
        val dao = db.forecastDao()
        val target = 20_000L
        val otherTarget = 20_001L
        val bucketStart = 1_000_000L
        val bucketEnd = bucketStart + 4L * 60 * 60 * 1000

        fun fc(targetDate: Long, fetchedAt: Long, source: String = "NWS") = ForecastEntity(
            targetDate = targetDate,
            dateOfPrediction = 0L,
            locationLat = lat,
            locationLon = lon,
            highTemp = 70f,
            lowTemp = 50f,
            condition = "Sunny",
            source = source,
            batchFetchedAt = fetchedAt,
            fetchedAt = fetchedAt,
        )

        dao.insertAll(
            listOf(
                fc(target, bucketStart + 1000),                 // in-window, target -> deleted
                fc(target, bucketStart + 2000),                 // in-window, target -> deleted
                fc(target, bucketEnd + 5000),                   // after window -> kept
                fc(otherTarget, bucketStart + 1500),            // in-window but different target -> kept
                fc(target, bucketStart + 1200, source = "OPEN_METEO"), // in-window but other source -> kept
            ),
        )

        dao.deleteForecastsInBucket("NWS", lat, lon, listOf(target), bucketStart, bucketEnd)

        val remaining = dao.getAllForecastsInRange(0L, 30_000L, lat, lon)
        // Kept: after-window NWS target, other-target NWS, other-source target = 3.
        assertEquals(3, remaining.size)
        // No NWS rows for `target` remain inside the bucket window.
        assertEquals(
            0,
            remaining.count { it.targetDate == target && it.source == "NWS" && it.fetchedAt in bucketStart until bucketEnd },
        )
    }
}
