package com.weatherwidget.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.RetentionPolicy
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.DailyActualsStore
import com.weatherwidget.data.repository.PersonalStationWeightProvider
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.shared.months.MonthsDayValue
import com.weatherwidget.shared.months.MonthsLine
import com.weatherwidget.shared.months.MonthsSeriesBuilder
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.widget.handlers.DailyViewLogic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Integration (Room DAO + DailyActualsStore + shared MonthsSeriesBuilder + MonthsScreenLogic +
 * DailyViewLogic): the months screen reads the whole `daily_history` record for the active site, the
 * line breaks at gaps and at a move, and a tap months back opens a column view drawn from the frozen
 * columns alone (no `forecasts` rows survive that long).
 */
@Category(LongDuration::class)
class MonthsViewDataIntegrationTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var store: DailyActualsStore
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val source = WeatherSource.NWS
    private val today: LocalDate = LocalDate.now()
    private val lat = 37.4220
    private val lon = -122.0841

    // Another city: outside the LocationMatch box, so its rows are not this site's history.
    private val otherLat = 38.5816
    private val otherLon = -121.4944

    private val gapDay = 60L
    private val frozenDay = 45L

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

    private fun row(back: Long, rowLat: Double = lat, rowLon: Double = lon) = DailyHistoryEntity(
        date = today.minusDays(back).toEpochDay() * 86_400_000L,
        source = source.id,
        locationLat = rowLat,
        locationLon = rowLon,
        computedHighTemp = 60f + back % 10,
        computedLowTemp = 40f + back % 6,
        condition = "Clear",
        updatedAt = 1L,
        forecastHighTemp = 70f + back % 5,
        forecastLowTemp = 50f + back % 3,
        priorForecastHighTemp = 71f + back % 5,
        priorForecastLowTemp = 51f + back % 3,
        lastWriter = DailyHistoryWriter.BLEND_RECOMPUTE.storedValue,
    )

    /** 1..89 days back here (one gap); 90..120 back in another city. */
    private suspend fun seed() {
        db.dailyHistoryDao().insertAll(
            (1L..89L).filter { it != gapDay }.map { row(it) } + (90L..120L).map { row(it, otherLat, otherLon) },
        )
    }

    private suspend fun actuals(lookback: Long) =
        store.getDailyActualsWithLiveToday(lat, lon, emptyList(), listOf(source.id), lookback)[source.id].orEmpty()

    @Test
    fun monthsLoadReadsTheWholeRecordForThisSiteOnly() = runTest {
        seed()
        val widget = actuals(DailyActualsStore.DEFAULT_LOOKBACK_DAYS)
        val months = actuals(RetentionPolicy.DAILY_HISTORY_DAYS)

        assertTrue("widget load stops at 30 days", widget.keys.all { !it.isBefore(today.minusDays(30)) })
        assertEquals("88 local days; the other city's 31 are not this site's", 88, months.size)
        assertFalse(months.containsKey(today.minusDays(100)))
        // The widget's days are untouched by the longer read.
        assertEquals(widget, months.filterKeys { it in widget.keys })
    }

    @Test
    fun lineBreaksAtTheGapAndTheMove() = runTest {
        seed()
        val values = MonthsSeriesBuilder.values(actuals(RetentionPolicy.DAILY_HISTORY_DAYS), emptyMap(), today)
        assertTrue(values.all { it.kind == MonthsDayValue.Kind.ACTUAL })
        val highs = MonthsSeriesBuilder.segments(values, MonthsLine.HIGH)
        assertEquals(
            listOf(today.minusDays(89) to today.minusDays(gapDay + 1), today.minusDays(gapDay - 1) to today.minusDays(1)),
            highs.map { it.points.first().first to it.points.last().first },
        )
    }

    @Test
    fun tapMonthsBackOpensAColumnViewFromTheFrozenColumns() = runTest {
        seed()
        val actuals = actuals(RetentionPolicy.DAILY_HISTORY_DAYS)
        val values = MonthsSeriesBuilder.values(actuals, emptyMap(), today)
        val ctx = MonthsScreenContext(today, baseColumns = 6, graphWidthDp = 400f, bounds = MonthsSeriesBuilder.bounds(values))
        val tapped = today.minusDays(frozenDay)

        val state = MonthsScreenLogic.tapMonths(MonthsScreenState(), ctx, tapped)
        val skipYesterday = MonthsScreenLogic.skipYesterday(state.dailyColumns)
        val days = DailyViewLogic.prepareGraphDayInputs(
            now = LocalDateTime.now(),
            centerDate = NavigationUtils.getDisplayCenterDate(today, state.dailyOffset, skipYesterday),
            today = today,
            weatherByDate = emptyMap(),
            forecastSnapshots = emptyMap(),
            numColumns = state.dailyColumns,
            displaySource = source,
            skipYesterday = skipYesterday,
            skipHistory = NavigationUtils.shouldSkipHistory(skipYesterday, state.dailyOffset),
            hourlyForecasts = emptyList(),
            dailyActuals = actuals,
            todayLabel = "Today",
            centerLat = lat,
            centerLon = lon,
        ).map(DailyViewLogic.PreparedGraphDay::renderDay)

        assertEquals(state.dailyColumns, days.size)
        val day = days.single { it.date == tapped }
        val expected = row(frozenDay)
        // Centre bar: the actual. Right bar: the frozen settled forecast. Left bar: the frozen prior forecast.
        assertEquals(expected.computedHighTemp!!, day.solidLineHigh!!, 0.01f)
        assertEquals(expected.forecastHighTemp!!, day.dashedLineHigh!!, 0.01f)
        assertNotNull("prior forecast drawn from the frozen column", day.snapshotHigh)
        assertEquals(expected.priorForecastHighTemp!!, day.snapshotHigh!!, 0.01f)
    }

    @Test
    fun deeperSourceIsSuggested_thePixelCase() = runTest {
        // NWS for 100 days here, Google only the last 12 (it was added later), and an old-site
        // NWS stretch elsewhere that must not count.
        db.dailyHistoryDao().insertAll(
            (1L..100L).map { row(it) } +
                (1L..12L).map { row(it).copy(source = WeatherSource.GOOGLE_WEATHER.id) } +
                (101L..300L).map { row(it, otherLat, otherLon) },
        )
        val depths = MonthsViewActivity.readHistoryDepths(db.dailyHistoryDao(), lat, lon, today)
        val visible = listOf(WeatherSource.GOOGLE_WEATHER.id, WeatherSource.NWS.id)

        val onGoogle = com.weatherwidget.shared.months.MonthsSourceDepth.suggestion(WeatherSource.GOOGLE_WEATHER.id, depths, visible)
        assertEquals(WeatherSource.NWS.id, onGoogle?.sourceId)
        assertEquals("only this site's rows count", today.minusDays(100), onGoogle?.first)
        assertEquals(100, onGoogle?.days)
        assertEquals(null, com.weatherwidget.shared.months.MonthsSourceDepth.suggestion(WeatherSource.NWS.id, depths, visible))
    }
}
