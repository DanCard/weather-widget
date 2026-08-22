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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

/**
 * `DailyHistorySnapshotter.ensureForecastOnlyHistoryRows`: the writer that makes daily history
 * self-sufficient for sources/days with no actuals (see plans/260822-daily-history-forecast-only-rows.md).
 */
@Category(LongDuration::class)
class ForecastOnlyHistoryRowsTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var snapshotter: DailyHistorySnapshotter
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val lat = 37.42
    private val lon = -122.08
    private val today: LocalDate = LocalDate.now()
    private val yesterday: LocalDate = today.minusDays(1)

    private fun epochDay(date: LocalDate) = date.toEpochDay() * 86_400_000L

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

    private fun forecast(
        date: LocalDate,
        source: String,
        high: Float?,
        low: Float?,
        fetchedAt: Long = 1L,
        isClimateNormal: Boolean = false,
    ) = ForecastEntity(
        targetDate = epochDay(date),
        dateOfPrediction = epochDay(date),
        locationLat = lat,
        locationLon = lon,
        highTemp = high,
        lowTemp = low,
        condition = "Clear",
        source = source,
        isClimateNormal = isClimateNormal,
        fetchedAt = fetchedAt,
        batchFetchedAt = fetchedAt,
    )

    private fun historyRow(
        date: LocalDate,
        source: String,
        high: Float? = 70f,
        low: Float? = 55f,
    ) = DailyHistoryEntity(
        date = epochDay(date),
        source = source,
        locationLat = lat,
        locationLon = lon,
        computedHighTemp = high,
        computedLowTemp = low,
        condition = "Clear",
        updatedAt = 1L,
    )

    @Test
    fun `creates a forecast-only row for a past day with snapshots but no history row`() = runTest {
        // The exact post-4826fad2 Samsung state: OPEN_METEO snapshots for yesterday, no row.
        db.forecastDao().insertAll(
            listOf(
                forecast(yesterday, WeatherSource.OPEN_METEO.id, 72f, 57f, fetchedAt = 1000L),
                forecast(yesterday, WeatherSource.OPEN_METEO.id, 73.6f, 58.3f, fetchedAt = 2000L),
            ),
        )

        snapshotter.ensureForecastOnlyHistoryRows(lat, lon)

        val rows = db.dailyHistoryDao().getExtremesInRange(epochDay(yesterday), epochDay(yesterday), lat, lon)
        val row = rows.single { it.source == WeatherSource.OPEN_METEO.id }
        assertNull("no actual may be fabricated", row.computedHighTemp)
        assertNull(row.computedLowTemp)
        assertEquals("latest complete batch is frozen", 73.6f, row.forecastHighTemp)
        assertEquals(58.3f, row.forecastLowTemp)
        assertEquals(DailyHistoryWriter.FORECAST_ONLY_ROW.storedValue, row.lastWriter)
    }

    @Test
    fun `is idempotent — no duplicates on a second run, and existing rows are untouched`() = runTest {
        db.forecastDao().insertAll(
            listOf(
                forecast(yesterday, WeatherSource.OPEN_METEO.id, 73f, 58f),
                forecast(yesterday, WeatherSource.NWS.id, 71f, 56f),
            ),
        )
        db.dailyHistoryDao().insertAll(listOf(historyRow(yesterday, WeatherSource.NWS.id)))

        snapshotter.ensureForecastOnlyHistoryRows(lat, lon)
        snapshotter.ensureForecastOnlyHistoryRows(lat, lon)

        val rows = db.dailyHistoryDao().getExtremesInRange(epochDay(yesterday), epochDay(yesterday), lat, lon)
        assertEquals("one NWS row + one Meteo row, no duplicates", 2, rows.size)
        val nws = rows.single { it.source == WeatherSource.NWS.id }
        assertEquals("the NWS actuals row is not rewritten as forecast-only", 70f, nws.computedHighTemp)
        assertNull("the NWS row's original lastWriter survives", nws.lastWriter)
    }

    @Test
    fun `skips today, incomplete batches, climate-normal and generic-gap rows`() = runTest {
        db.forecastDao().insertAll(
            listOf(
                forecast(today, WeatherSource.OPEN_METEO.id, 73f, 58f),
                forecast(yesterday, WeatherSource.OPEN_METEO.id, 73f, null),
                forecast(yesterday, WeatherSource.OPEN_METEO.id, 73f, 58f, isClimateNormal = true),
                forecast(yesterday, WeatherSource.GENERIC_GAP.id, 60f, 50f),
            ),
        )

        snapshotter.ensureForecastOnlyHistoryRows(lat, lon)

        val rows = db.dailyHistoryDao().getExtremesInRange(epochDay(yesterday.minusDays(1)), epochDay(today), lat, lon)
        assertEquals(emptyList<DailyHistoryEntity>(), rows)
    }

    @Test
    fun `open-meteo legacy cleanup preserves forecast-only rows`() = runTest {
        // Ordering guard: if the one-time cleanup ever runs after the writer, the display rows
        // survive — the delete only targets legacy rows that carry (model) computed values.
        db.forecastDao().insertAll(
            listOf(forecast(yesterday, WeatherSource.OPEN_METEO.id, 73f, 58f)),
        )
        snapshotter.ensureForecastOnlyHistoryRows(lat, lon)
        // A legacy-style row with fabricated computed values still gets deleted.
        db.dailyHistoryDao().insertAll(
            listOf(historyRow(yesterday.minusDays(2), WeatherSource.OPEN_METEO.id)),
        )

        com.weatherwidget.util.SharedPreferencesUtil
            .getPrefs(context, "weather_prefs").edit().clear().commit()
        OpenMeteoLegacyActualsCleanup.runIfNeeded(
            context,
            db.observationDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
        )

        val rows = db.dailyHistoryDao().getExtremesInRange(epochDay(yesterday.minusDays(2)), epochDay(yesterday), lat, lon)
        val survivors = rows.filter { it.source == WeatherSource.OPEN_METEO.id }
        assertEquals(1, survivors.size)
        assertNull(survivors[0].computedHighTemp)
        assertEquals(73f, survivors[0].forecastHighTemp)
        assertNotNull(survivors[0].forecastLowTemp)
    }
}
