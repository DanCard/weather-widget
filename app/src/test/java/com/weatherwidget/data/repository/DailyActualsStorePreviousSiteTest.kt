package com.weatherwidget.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

/**
 * Integration (Room DAO + DailyActualsStore + shared PreviousSiteHistory): after a move, yesterday's
 * measured history from the previous site fills the new site's forecast-only yesterday — and only
 * yesterday. Rows mirror the Pixel 7 Pro trip of 2026-09-24/25 (Kyiv → Lviv); bug report
 * "no history when on the move".
 */
@Category(LongDuration::class)
class DailyActualsStorePreviousSiteTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var store: DailyActualsStore
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val source = WeatherSource.SILURIAN.id
    private val today: LocalDate = LocalDate.now()
    private val lvivLat = 49.8328
    private val lvivLon = 24.0338
    private val kyivLat = 50.45
    private val kyivLon = 30.49

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = DailyActualsStore(
            db.observationDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
            db.hourlyForecastDao(),
            PersonalStationWeightProvider { 1.0 },
        )
    }

    @After
    fun teardown() = db.close()

    private fun row(
        date: LocalDate,
        lat: Double,
        lon: Double,
        high: Float? = null,
        low: Float? = null,
        fHigh: Float? = null,
        fLow: Float? = null,
    ) = DailyHistoryEntity(
        date = date.toEpochDay() * 86_400_000L,
        source = source,
        locationLat = lat,
        locationLon = lon,
        computedHighTemp = high,
        computedLowTemp = low,
        condition = "Rain",
        updatedAt = 1L,
        forecastHighTemp = fHigh,
        forecastLowTemp = fLow,
        lastWriter = DailyHistoryWriter.BLEND_RECOMPUTE.storedValue,
    )

    @Test
    fun yesterdayFromPreviousSite_fillsForecastOnlyRow_olderDaysDoNot() = runTest {
        val yesterday = today.minusDays(1)
        val twoDaysAgo = today.minusDays(2)
        db.dailyHistoryDao().insertAll(
            listOf(
                row(yesterday, kyivLat, kyivLon, high = 61.9f, low = 44.9f),
                row(twoDaysAgo, kyivLat, kyivLon, high = 59.6f, low = 49.8f),
                row(yesterday, lvivLat, lvivLon, fHigh = 57f, fLow = 48f),
                row(twoDaysAgo, lvivLat, lvivLon, fHigh = 56f, fLow = 49f),
            ),
        )

        val actuals = store.getDailyActualsWithLiveToday(lvivLat, lvivLon, emptyList(), listOf(source))[source]!!

        val y = actuals[yesterday]!!
        assertEquals(61.9f, y.computedHighTemp!!, 0.01f)
        assertEquals(44.9f, y.computedLowTemp!!, 0.01f)
        assertEquals("forecast overlay stays Lviv's", 57f, y.forecastHighTemp!!, 0.01f)
        assertTrue(y.isActualsBorrowed)

        val older = actuals[twoDaysAgo]!!
        assertNull("only yesterday is borrowed", older.computedHighTemp)
        assertFalse(older.isActualsBorrowed)
    }

    @Test
    fun measuredLocalYesterday_isNotReplaced() = runTest {
        val yesterday = today.minusDays(1)
        db.dailyHistoryDao().insertAll(
            listOf(
                row(yesterday, kyivLat, kyivLon, high = 61.9f, low = 44.9f),
                row(yesterday, lvivLat, lvivLon, high = 59.5f, low = 47.8f),
            ),
        )
        val y = store.getDailyActualsWithLiveToday(lvivLat, lvivLon, emptyList(), listOf(source))[source]!![yesterday]!!
        assertEquals(59.5f, y.computedHighTemp!!, 0.01f)
        assertFalse(y.isActualsBorrowed)
    }
}
