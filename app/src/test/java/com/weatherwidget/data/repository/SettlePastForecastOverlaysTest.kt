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
}
