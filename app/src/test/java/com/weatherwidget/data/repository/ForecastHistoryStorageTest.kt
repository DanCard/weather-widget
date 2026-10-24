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
 *  - hourly_forecast_history coalesces within one snapshot label (REPLACE) and keeps separate labels.
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
}
