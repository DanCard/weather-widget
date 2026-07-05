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
    fun `loadGraphWindowHourlyForecasts returns todays forecast when stored coords are quantized but query centre is raw GPS`() = runTest {
        // Regression: writes are quantized to 3 dp (LocationMatch.quantize) so the stored rows sit at
        // 37.417/-122.089, while the widget queries with the raw configured/GPS coordinate
        // (37.4168014/-122.0888977) ~0.0002° away. The old exact `abs(diff) < 0.0001` filter dropped
        // every cached row → blank "no forecast line for today" until a network fetch landed.
        val now = LocalDateTime.of(2026, 6, 21, 16, 0)
        val storedLat = 37.417
        val storedLon = -122.089
        val rawQueryLat = 37.4168014
        val rawQueryLon = -122.0888977

        // A spread of today's hours: past (this morning) and future (this evening).
        val hours = listOf(now.minusHours(3), now.minusHours(1), now, now.plusHours(2))
        db.hourlyForecastDao().insertAll(
            hours.map { h ->
                HourlyForecastEntity(
                    dateTime = epochMs(h),
                    locationLat = storedLat,
                    locationLon = storedLon,
                    temperature = 72f,
                    condition = "Clear",
                    source = WeatherSource.NWS.id,
                    cloudCover = 10,
                    fetchedAt = epochMs(now),
                )
            },
        )

        val result = GraphDataLoader.loadGraphWindowHourlyForecasts(
            hourlyDao = db.hourlyForecastDao(),
            hourlyHistoryDao = db.hourlyForecastHistoryDao(),
            lat = rawQueryLat,
            lon = rawQueryLon,
            centerTime = now,
            zoom = ZoomLevel.WIDE,
            now = now,
            source = WeatherSource.NWS,
        )

        // Every inserted hour must survive the same-site match (was empty before the fix).
        val times = result.map { it.dateTime }.toSet()
        hours.forEach { h ->
            assert(epochMs(h) in times) { "missing forecast point for $h" }
        }
        assertEquals(72f, result.first().temperature, 0.0f)
    }

    @Test
    fun `loadGraphWindowHourlyForecasts excludes a genuinely different nearby marker`() = runTest {
        // The user's site and a distinct marker (~0.5 km away) both sit within the broad query box;
        // only the user's site should drive the line.
        val now = LocalDateTime.of(2026, 6, 21, 16, 0)
        val siteLat = 37.417
        val siteLon = -122.089
        val markerLat = 37.422
        val markerLon = -122.084

        db.hourlyForecastDao().insertAll(
            listOf(
                HourlyForecastEntity(
                    dateTime = epochMs(now), locationLat = siteLat, locationLon = siteLon,
                    temperature = 72f, condition = "Clear", source = WeatherSource.NWS.id,
                    cloudCover = 10, fetchedAt = epochMs(now),
                ),
                HourlyForecastEntity(
                    dateTime = epochMs(now), locationLat = markerLat, locationLon = markerLon,
                    temperature = 50f, condition = "Cold", source = WeatherSource.NWS.id,
                    cloudCover = 90, fetchedAt = epochMs(now),
                ),
            ),
        )

        val result = GraphDataLoader.loadGraphWindowHourlyForecasts(
            hourlyDao = db.hourlyForecastDao(),
            hourlyHistoryDao = db.hourlyForecastHistoryDao(),
            lat = siteLat,
            lon = siteLon,
            centerTime = now,
            zoom = ZoomLevel.WIDE,
            now = now,
            source = WeatherSource.NWS,
        )

        val atNow = result.filter { it.dateTime == epochMs(now) }
        assertEquals(1, atNow.size)
        assertEquals(72f, atNow.first().temperature, 0.0f) // the marker's 50f is excluded
    }

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
                    timestampToGroupPredictions = epochMs(now.minusHours(4)),
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
                    timestampToGroupPredictions = epochMs(now.minusHours(8)),
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
