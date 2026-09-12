package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * plans/260911-backfill-elapsed-hour-forecast-history-on-fresh-site.md test #6: the store +
 * history DAO together file a payload's elapsed hours as history only where the site has none,
 * never touch the live table, and are a no-op on a second pass.
 */
@Category(LongDuration::class)
class HourlyForecastStoreElapsedBackfillTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var store: HourlyForecastStore

    private val h = 3_600_000L
    private val lat = 37.4168
    private val lon = -122.0890
    private val source = WeatherSource.OPEN_METEO.id

    // Hour-aligned "now" so the payload's hours line up like a real feed.
    private val now = (System.currentTimeMillis() / h) * h + 20 * 60_000L

    @Before
    fun setUp() {
        db = TestDatabase.create()
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = HourlyForecastStore(
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            observationDao = db.observationDao(),
            widgetStateManager = WidgetStateManager(context),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun hour(t: Long, temp: Float) =
        HourlyForecast(dateTime = t, temperature = temp, condition = "Clear", cloudCover = 10, source = source)

    /** A past_days-style payload: six elapsed hours, the in-progress hour, and two future hours. */
    private fun payload() = (-6..2).map { hour(now - now % h + it * h, 60f + it) }

    private suspend fun historyRows() = db.hourlyForecastHistoryDao().getHistoryInRangeForBucketWindow(
        startDateTime = now - 24 * h,
        endDateTime = now + 24 * h,
        bucketStart = Long.MIN_VALUE,
        bucketEnd = Long.MAX_VALUE,
        lat = lat,
        lon = lon,
        source = source,
    )

    private suspend fun liveRows() = db.hourlyForecastDao().getHourlyForecastsBySource(
        now - 24 * h, now + 24 * h, LocationMatch.quantize(lat), LocationMatch.quantize(lon), source,
    )

    @Test
    fun `fresh site - elapsed hours are filed as history and the live table is untouched`() = runTest {
        val summary = store.backfillElapsedHistory(payload(), lat, lon, source, nowMs = now)

        val elapsed = payload().filter { it.dateTime < now - ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS }
        assertEquals(elapsed.size, summary.offered)
        assertEquals(0, summary.covered)
        assertEquals(elapsed.size, summary.stored)
        assertEquals(elapsed.map { it.dateTime }, historyRows().map { it.dateTime })
        assertEquals(elapsed.map { it.temperature }, historyRows().map { it.temperature })
        assertTrue("backfill must never write the live table", liveRows().isEmpty())
        // Written at the quantized site, like every other hourly write.
        assertTrue(historyRows().all { it.locationLat == LocationMatch.quantize(lat) })
    }

    @Test
    fun `second pass stores nothing`() = runTest {
        store.backfillElapsedHistory(payload(), lat, lon, source, nowMs = now)
        val before = historyRows()

        val again = store.backfillElapsedHistory(payload().map { it.copy(temperature = 0f) }, lat, lon, source, nowMs = now)

        assertEquals(0, again.stored)
        assertEquals(before.size, again.covered)
        assertEquals(before, historyRows())
    }

    @Test
    fun `an hour with a genuine snapshot in another bucket is left alone`() = runTest {
        val snapshotted = now - now % h - 3 * h
        val genuine = HourlyForecastHistoryEntity(
            dateTime = snapshotted,
            locationLat = LocationMatch.quantize(lat),
            locationLon = LocationMatch.quantize(lon),
            temperature = 99f,
            condition = "Sunny",
            source = source,
            timestampToGroupPredictions = snapshotted - 24 * h,
            fetchedAt = snapshotted - 24 * h,
        )
        db.hourlyForecastHistoryDao().insertAll(listOf(genuine))

        val summary = store.backfillElapsedHistory(payload(), lat, lon, source, nowMs = now)

        assertEquals(1, summary.covered)
        val rowsForHour = historyRows().filter { it.dateTime == snapshotted }
        assertEquals(listOf(99f), rowsForHour.map { it.temperature })
        assertEquals(listOf(snapshotted - 24 * h), rowsForHour.map { it.timestampToGroupPredictions })
    }

    @Test
    fun `a same-site jitter fragment counts as covered - a different site does not`() = runTest {
        val t = now - now % h - 2 * h
        fun rowAt(rowLat: Double, rowLon: Double) = HourlyForecastHistoryEntity(
            dateTime = t, locationLat = rowLat, locationLon = rowLon, temperature = 1f, condition = "x",
            source = source, timestampToGroupPredictions = 0L, fetchedAt = 0L,
        )
        // 0.001 deg away: same site. 0.05 deg away: inside the query box, a different site.
        db.hourlyForecastHistoryDao().insertAll(listOf(rowAt(LocationMatch.quantize(lat) + 0.001, LocationMatch.quantize(lon))))
        db.hourlyForecastHistoryDao().insertAll(listOf(rowAt(lat + 0.05, lon).copy(dateTime = t - h)))

        val summary = store.backfillElapsedHistory(payload(), lat, lon, source, nowMs = now)

        val stored = historyRows().filter { it.locationLat == LocationMatch.quantize(lat) }.map { it.dateTime }
        assertTrue("jitter fragment covers $t", t !in stored)
        assertTrue("other site does not cover ${t - h}", (t - h) in stored)
        assertEquals(1, summary.covered)
    }

    @Test
    fun `saveHourlyEntitiesFromShared still drops elapsed hours from the live table`() = runTest {
        store.saveHourlyEntitiesFromShared(payload(), lat, lon, source)
        val live = liveRows().map { it.dateTime }
        assertTrue(live.isNotEmpty())
        assertTrue(live.all { it >= System.currentTimeMillis() - ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS })
    }
}
