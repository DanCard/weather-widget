package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class GraphDataLoaderCloudCoverStitchTest {
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

    private fun epochMs(time: LocalDateTime): Long =
        time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `loadGraphWindowHourlyForecasts fills missing cloud cover from history`() = runTest {
        val now = LocalDateTime.of(2026, 6, 8, 8, 0)
        val centerTime = now
        val currentHour = now.minusHours(1)

        db.hourlyForecastDao().insertAll(
            listOf(
                HourlyForecastEntity(
                    dateTime = epochMs(currentHour),
                    locationLat = lat,
                    locationLon = lon,
                    temperature = 70f,
                    condition = "Clear",
                    source = WeatherSource.NWS.id,
                    cloudCover = null,
                    fetchedAt = epochMs(now),
                ),
            ),
        )
        db.hourlyForecastHistoryDao().insertAll(
            listOf(
                com.weatherwidget.data.local.HourlyForecastHistoryEntity(
                    dateTime = epochMs(currentHour),
                    locationLat = lat,
                    locationLon = lon,
                    temperature = 70f,
                    condition = "Clear",
                    source = WeatherSource.NWS.id,
                    snapshotBucket = epochMs(now.minusHours(4)),
                    cloudCover = 77,
                    fetchedAt = epochMs(now.minusHours(4)),
                ),
            ),
        )

        val historyWindow = db.hourlyForecastHistoryDao().getHistoryInRangeForBucketWindow(
            startDateTime = epochMs(now.minusHours(12)),
            endDateTime = epochMs(now.plusHours(12)),
            bucketStart = Long.MIN_VALUE,
            bucketEnd = Long.MAX_VALUE,
            lat = lat,
            lon = lon,
            source = WeatherSource.NWS.id,
        )
        assertEquals(1, historyWindow.size)
        assertEquals(77, historyWindow[0].cloudCover)

        val result = GraphDataLoader.loadGraphWindowHourlyForecasts(
            hourlyDao = db.hourlyForecastDao(),
            hourlyHistoryDao = db.hourlyForecastHistoryDao(),
            lat = lat,
            lon = lon,
            centerTime = centerTime,
            zoom = ZoomLevel.WIDE,
            now = now,
            source = WeatherSource.NWS,
        )

        val repaired = result.associateBy { it.dateTime }[epochMs(currentHour)]
        assertEquals(77, repaired?.cloudCover)
        assertEquals(70f, repaired!!.temperature, 0.0f)
        assertEquals("Clear", repaired.condition)
    }

    @Test
    fun `loadGraphWindowHourlyForecasts fills missing cloud cover from history when window does not overlap now`() = runTest {
        val now = LocalDateTime.of(2026, 6, 8, 8, 0)
        val centerTime = now.minusDays(1)
        val currentHour = centerTime.minusHours(1)

        db.hourlyForecastDao().insertAll(
            listOf(
                HourlyForecastEntity(
                    dateTime = epochMs(currentHour),
                    locationLat = lat,
                    locationLon = lon,
                    temperature = 68f,
                    condition = "Mostly Clear",
                    source = WeatherSource.NWS.id,
                    cloudCover = null,
                    fetchedAt = epochMs(now),
                ),
            ),
        )
        db.hourlyForecastHistoryDao().insertAll(
            listOf(
                com.weatherwidget.data.local.HourlyForecastHistoryEntity(
                    dateTime = epochMs(currentHour),
                    locationLat = lat,
                    locationLon = lon,
                    temperature = 68f,
                    condition = "Mostly Clear",
                    source = WeatherSource.NWS.id,
                    snapshotBucket = epochMs(now.minusHours(8)),
                    cloudCover = 61,
                    fetchedAt = epochMs(now.minusHours(8)),
                ),
            ),
        )

        val result = GraphDataLoader.loadGraphWindowHourlyForecasts(
            hourlyDao = db.hourlyForecastDao(),
            hourlyHistoryDao = db.hourlyForecastHistoryDao(),
            lat = lat,
            lon = lon,
            centerTime = centerTime,
            zoom = ZoomLevel.WIDE,
            now = now,
            source = WeatherSource.NWS,
        )

        val repaired = result.associateBy { it.dateTime }[epochMs(currentHour)]
        assertEquals(61, repaired?.cloudCover)
        assertEquals(68f, repaired!!.temperature, 0.0f)
        assertEquals("Mostly Clear", repaired.condition)
    }
}
