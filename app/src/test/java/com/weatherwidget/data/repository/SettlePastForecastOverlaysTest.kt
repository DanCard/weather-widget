package com.weatherwidget.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Android end to end through Room: [DailyHistorySnapshotter.settlePastForecastOverlays] reads the
 * stored forecast fetches and the past `daily_history` row's extreme times, settles the overlay to
 * the last forecast fetched before each extreme, and writes only the overlay columns.
 * See plans/261004-forecast-overlay-frozen-at-extreme-time.md.
 */
@Category(LongDuration::class)
class SettlePastForecastOverlaysTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var snapshotter: DailyHistorySnapshotter
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val lat = 37.4166
    private val lon = -122.0889
    private val zone = ZoneId.systemDefault()
    private val yesterday: LocalDate = LocalDate.now(zone).minusDays(1)
    private val dateMs = yesterday.toEpochDay() * 86_400_000L

    private fun at(hour: Int, minute: Int = 0) =
        yesterday.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        snapshotter = DailyHistorySnapshotter(
            context,
            db.forecastDao(),
            db.hourlyForecastDao(),
            db.hourlyForecastHistoryDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun fetch(fetchedAt: Long, high: Float, low: Float) = ForecastEntity(
        targetDate = dateMs,
        dateOfPrediction = dateMs,
        locationLat = lat,
        locationLon = lon,
        highTemp = high,
        lowTemp = low,
        condition = "Clear",
        source = WeatherSource.OPEN_METEO.id,
        fetchedAt = fetchedAt,
        batchFetchedAt = fetchedAt,
    )

    @Test
    fun `past day settles to the fetches before its extremes and keeps updatedAt`() = runTest {
        db.forecastDao().insertAll(
            listOf(
                fetch(at(4, 48), 90.0f, 58.3f),
                fetch(at(10, 35), 91.3f, 58.1f),
                fetch(at(15, 47), 88.9f, 58.1f),
                fetch(at(22, 25), 89.0f, 58.0f), // after both extremes: a hindcast
            ),
        )
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = dateMs,
                    source = WeatherSource.OPEN_METEO.id,
                    locationLat = lat,
                    locationLon = lon,
                    computedHighTemp = 91.6f,
                    computedLowTemp = 59.0f,
                    condition = "Clear",
                    updatedAt = 12345L,
                    forecastHighTemp = 89.0f,
                    forecastLowTemp = 58.0f,
                    computedHighAt = at(16, 15),
                    computedLowAt = at(5, 15),
                ),
            ),
        )

        snapshotter.settlePastForecastOverlays(lat, lon)

        val row = db.dailyHistoryDao().getExtremesInRange(dateMs, dateMs, lat, lon).single()
        assertEquals(88.9f, row.forecastHighTemp)
        assertEquals(58.3f, row.forecastLowTemp)
        assertEquals("settle must not trip the blend's optimistic check", 12345L, row.updatedAt)
        assertEquals(DailyHistoryWriter.FORECAST_FREEZE.storedValue, row.lastWriter)
        assertEquals(1, db.appLogDao().getLogsByTag("FORECAST_OVERLAY_SETTLED", 10).size)
    }

    /** Epoch ms at [hour] local, [daysBefore] days before yesterday. */
    private fun before(daysBefore: Long, hour: Int, minute: Int = 0) =
        yesterday.minusDays(daysBefore).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun fetchFor(date: LocalDate, fetchedAt: Long, high: Float?, low: Float?) =
        fetch(fetchedAt, high ?: 0f, low ?: 0f).copy(
            targetDate = date.toEpochDay() * 86_400_000L,
            dateOfPrediction = date.toEpochDay() * 86_400_000L,
            highTemp = high,
            lowTemp = low,
        )

    private fun historyRow(date: LocalDate) = DailyHistoryEntity(
        date = date.toEpochDay() * 86_400_000L,
        source = WeatherSource.OPEN_METEO.id,
        locationLat = lat,
        locationLon = lon,
        computedHighTemp = null, // a forecast-only row: no extreme times, still gets a prior forecast
        computedLowTemp = null,
        condition = "Clear",
        updatedAt = 777L,
        forecastHighTemp = 70f,
        forecastLowTemp = 50f,
    )

    @Test
    fun `freezes yesterday's forecast at the 06 00 and 16 00 anchors for past days and today`() = runTest {
        val today = yesterday.plusDays(1)
        db.forecastDao().insertAll(
            listOf(
                // For yesterday: anchors are the day before yesterday at 06:00 / 16:00.
                fetchFor(yesterday, before(1, 5, 30), 80f, 51f), // low anchor
                fetchFor(yesterday, before(1, 15, 30), 82f, 53f), // high anchor
                fetchFor(yesterday, before(1, 20), 85f, 55f), // after both
                // For today: anchors are yesterday at 06:00 / 16:00.
                fetchFor(today, before(0, 5), 75f, 49f),
                fetchFor(today, before(0, 12), 77f, null),
            ),
        )
        db.dailyHistoryDao().insertAll(listOf(historyRow(yesterday), historyRow(today)))

        snapshotter.settlePastForecastOverlays(lat, lon)

        val y = db.dailyHistoryDao().getExtremesInRange(dateMs, dateMs, lat, lon).single()
        assertEquals(82f, y.priorForecastHighTemp)
        assertEquals(51f, y.priorForecastLowTemp)
        assertEquals("other columns untouched", 70f, y.forecastHighTemp)
        assertEquals("field-limited write keeps updatedAt", 777L, y.updatedAt)

        val todayMs = today.toEpochDay() * 86_400_000L
        val t = db.dailyHistoryDao().getExtremesInRange(todayMs, todayMs, lat, lon).single()
        assertEquals(77f, t.priorForecastHighTemp)
        assertEquals(49f, t.priorForecastLowTemp)
        assertEquals(2, db.appLogDao().getLogsByTag("PRIOR_FORECAST_FREEZE", 10).size)
    }

    @Test
    fun `prior-forecast candidates are only the fetches around each day's anchors`() = runTest {
        db.forecastDao().insertAll(
            listOf(
                fetchFor(yesterday, before(1, 5, 30), 80f, 51f), // inside the window
                fetchFor(yesterday, before(5, 12), 70f, 45f), // 5 days early: outside
                fetchFor(yesterday, at(22), 85f, 55f), // the evening of the day: outside
            ),
        )
        val rows = db.forecastDao().getPriorForecastCandidates(dateMs, dateMs, lat, lon)
        assertEquals(listOf(80f), rows.map { it.highTemp })
    }
}
