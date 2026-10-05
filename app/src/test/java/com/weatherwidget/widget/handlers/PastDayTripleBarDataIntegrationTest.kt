package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.DailyHistoryMaintenance
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * The left bar of the triple bar, end to end: the shared freeze
 * ([DailyHistoryMaintenance.planPriorForecasts]) writes a past day's "yesterday's forecast", and
 * [DailyViewLogic.prepareGraphDays] carries it to that column's [DayData.snapshotHigh]/[DayData.snapshotLow];
 * today's left bar uses the same 06:00 / 16:00 anchors through [DailyTodayResolver].
 * See plans/261005-past-days-triple-bar-prior-forecast-at-cutoffs.md.
 */
@Category(LongDuration::class)
class PastDayTripleBarDataIntegrationTest : RobolectricTest() {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val yesterday = today.minusDays(1)
    private val now = today.atTime(18, 0)
    private val lat = 37.422
    private val lon = -122.084
    private val source = WeatherSource.OPEN_METEO

    private fun ms(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun dayMs(date: LocalDate) = date.toEpochDay() * 86_400_000L

    private fun entity(target: LocalDate, fetchedAt: Long, high: Float, low: Float) = ForecastEntity(
        targetDate = dayMs(target),
        dateOfPrediction = dayMs(target),
        locationLat = lat,
        locationLon = lon,
        highTemp = high,
        lowTemp = low,
        condition = "Clear",
        source = source.id,
        fetchedAt = fetchedAt,
        batchFetchedAt = fetchedAt,
    )

    private fun row(e: ForecastEntity) = DailyHistoryMaintenance.ForecastHistoryRow(
        dateMs = e.targetDate, source = e.source, locationLat = e.locationLat, locationLon = e.locationLon,
        highTemp = e.highTemp, lowTemp = e.lowTemp, precipAmountMm = null, condition = e.condition,
        fetchedAt = e.fetchedAt, isClimateNormal = false,
    )

    @Test
    fun `frozen prior reaches the past column and today's left bar uses the anchors`() {
        val yesterdayFetches = listOf(
            entity(yesterday, ms(yesterday.minusDays(1), 5), 80f, 51f), // low anchor
            entity(yesterday, ms(yesterday.minusDays(1), 15), 82f, 53f), // high anchor
            entity(yesterday, ms(yesterday.minusDays(1), 21), 85f, 55f),
        )
        val todayFetches = listOf(
            entity(today, ms(yesterday, 5), 70f, 45f), // low anchor
            entity(today, ms(yesterday, 15), 72f, 47f), // high anchor
            entity(today, ms(today, 9), 74f, 48f), // after both: the live forecast, not the left bar
        )
        val history = DailyHistory(
            date = dayMs(yesterday), source = source.id, locationLat = lat, locationLon = lon,
            computedHighTemp = 83f, computedLowTemp = 52f, condition = "Clear", updatedAt = 0L,
            forecastHighTemp = 82f, forecastLowTemp = 51f,
        )
        val frozen = DailyHistoryMaintenance.planPriorForecasts(
            yesterdayFetches.map(::row), listOf(history), dayMs(today), zone,
        ).rows.single()

        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = mapOf(today to todayFetches.last()),
            forecastSnapshots = mapOf(yesterday to yesterdayFetches, today to todayFetches),
            numColumns = 5,
            displaySource = source,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = mapOf(yesterday to frozen),
            todayLabel = "Today",
        )

        val past = days.single { it.date == yesterday }
        assertEquals(82f, past.snapshotHigh)
        assertEquals(51f, past.snapshotLow)

        val todayDay = days.single { it.date == today }
        assertEquals(72f, todayDay.snapshotHigh)
        assertEquals(45f, todayDay.snapshotLow)
    }

    @Test
    fun `a past day without a frozen pair has no left bar`() {
        val history = DailyHistory(
            date = dayMs(yesterday), source = source.id, locationLat = lat, locationLon = lon,
            computedHighTemp = 83f, computedLowTemp = 52f, condition = "Clear", updatedAt = 0L,
            priorForecastHighTemp = 82f, priorForecastLowTemp = null,
        )
        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = emptyMap(),
            forecastSnapshots = emptyMap(),
            numColumns = 5,
            displaySource = source,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = mapOf(yesterday to history),
            todayLabel = "Today",
        )
        assertNull(days.single { it.date == yesterday }.snapshotHigh)
    }

    @Test
    fun `before the freeze has run, a past day's left bar is picked live from loaded rows`() {
        // The state right after an upgrade: daily_history has no priorForecast* yet, but the loaders
        // append the anchor-window rows (ForecastDao.getPriorForecastCandidates) after the newest row.
        val threeDaysAgo = today.minusDays(3)
        val rows = listOf(
            entity(threeDaysAgo, ms(threeDaysAgo, 12), 90f, 60f), // newest (first, as loaded)
            entity(threeDaysAgo, ms(threeDaysAgo.minusDays(1), 5), 84f, 55f), // low anchor
            entity(threeDaysAgo, ms(threeDaysAgo.minusDays(1), 15), 86f, 57f), // high anchor
        )
        val history = DailyHistory(
            date = dayMs(threeDaysAgo), source = source.id, locationLat = lat, locationLon = lon,
            computedHighTemp = 88f, computedLowTemp = 56f, condition = "Clear", updatedAt = 0L,
        )
        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = emptyMap(),
            forecastSnapshots = mapOf(threeDaysAgo to rows),
            numColumns = 5,
            displaySource = source,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = mapOf(threeDaysAgo to history),
            todayLabel = "Today",
        )
        val past = days.single { it.date == threeDaysAgo }
        assertEquals(86f, past.snapshotHigh)
        assertEquals(55f, past.snapshotLow)
        // The right (settled) bar still comes from the newest row, not the appended older ones.
        assertEquals(90f, past.dashedLineHigh)
    }
}
