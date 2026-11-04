package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.ForecastPersistRun
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
 * plans/260911-backfill-elapsed-hour-forecast-history-on-fresh-site.md test #6: the `:shared`
 * [ForecastPersistRun] on the store's Room Host files a payload's elapsed hours as history only where
 * the site has none, and is a no-op on a second pass.
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
            appLogDao = db.appLogDao(),
            dailyHistoryDao = db.dailyHistoryDao(),
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

    /** The payload as a provider's separately sent elapsed hours: the backfill alone, no live rows. */
    private suspend fun backfill(hours: List<HourlyForecast>, on: HourlyForecastStore = store) =
        ForecastPersistRun(on.forecastPersistHost(db.forecastDao(), db.appLogDao(), source, lat, lon))
            .persist(ForecastPersistRun.Fetch(hourly = emptyList(), elapsedHourly = hours, daily = null, keep = null), now)
            .elapsed

    private suspend fun liveRows() = db.hourlyForecastDao().getHourlyForecastsBySource(
        now - 24 * h, now + 24 * h, LocationMatch.quantize(lat), LocationMatch.quantize(lon), source,
    )

    @Test
    fun `fresh site - elapsed hours are filed as history`() = runTest {
        val summary = backfill(payload())!!

        val elapsed = payload().filter { it.dateTime < now - ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS }
        assertEquals(elapsed.size, summary.offered)
        assertEquals(elapsed.size, summary.stored)
        assertEquals(elapsed.map { it.dateTime }, historyRows().map { it.dateTime })
        assertEquals(elapsed.map { it.temperature }, historyRows().map { it.temperature })
        // Written at the quantized site, like every other hourly write.
        assertTrue(historyRows().all { it.locationLat == LocationMatch.quantize(lat) })
    }

    /** Every fetch files its elapsed hours (plans/261104-file-every-elapsed-hour.md). */
    @Test
    fun `second pass files again, stamped with its fetch time`() = runTest {
        val first = backfill(payload())!!

        val again = backfill(payload().map { it.copy(temperature = 0f) })!!

        assertEquals(first.stored, again.stored)
        assertTrue(historyRows().filter { it.temperature == 0f }.all { it.fetchedAt == now })
    }

    @Test
    fun `an hour with an older snapshot gets this fetch's row beside it`() = runTest {
        val snapshotted = now - now % h - 3 * h
        val genuine = HourlyForecastEntity(
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

        backfill(payload())

        val rowsForHour = historyRows().filter { it.dateTime == snapshotted }
        val newest = rowsForHour.maxBy { it.fetchedAt }
        println("rows for hour=${rowsForHour.map { it.temperature to it.fetchedAt }}")
        assertTrue(99f in rowsForHour.map { it.temperature })
        assertEquals(now, newest.fetchedAt)
        assertTrue(newest.temperature != 99f)
    }

    @Test
    fun `saveHourlyEntitiesFromShared still drops elapsed hours from the live table`() = runTest {
        store.saveHourlyEntitiesFromShared(payload(), lat, lon, source)
        val live = liveRows().map { it.dateTime }
        assertTrue(live.isNotEmpty())
        assertTrue(live.all { it >= System.currentTimeMillis() - ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS })
    }
}
