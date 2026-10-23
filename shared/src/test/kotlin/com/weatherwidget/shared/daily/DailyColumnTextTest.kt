package com.weatherwidget.shared.daily

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

/** The high / low a text-mode column prints — Android's 1-row widget and desktop's text popup. */
@Category(ShortDuration::class)
class DailyColumnTextTest {

    private fun column(
        isToday: Boolean = false,
        isPast: Boolean = false,
        solidHigh: Float? = null,
        solidLow: Float? = null,
        forecastHigh: Float? = null,
        forecastLow: Float? = null,
        ghostHigh: Float? = null,
        snapshotHigh: Float? = null,
        snapshotLow: Float? = null,
    ) = DailyColumn(
        date = LocalDate.of(2026, 10, 23), label = "x", forecast = null, actual = null, snapshot = null,
        solidHigh = solidHigh, solidLow = solidLow, forecastHigh = forecastHigh, forecastLow = forecastLow,
        barTopHigh = solidHigh ?: forecastHigh, ghostHigh = ghostHigh, snapshotHigh = snapshotHigh,
        snapshotLow = snapshotLow, iconCondition = null, iconName = "ic_weather_clear", isToday = isToday,
        isPast = isPast, solidIsForecastFallback = false, cloudCoverRatio = null, dailyRainLabelText = null,
        nightRainLabelText = null, dayPrecipProbability = null, nightPrecipProbability = null,
        daysFromToday = 0, isClimateNormal = false,
    )

    @Test
    fun todayPrintsTheHighestItShowsButNeverYesterdaysForecast() {
        val c = column(isToday = true, solidHigh = 61f, forecastHigh = 67f, ghostHigh = 63f, snapshotHigh = 75f, snapshotLow = 40f, solidLow = 52f)
        assertEquals(67f, DailyColumnBuilder.textHigh(c))
        assertEquals(52f, DailyColumnBuilder.textLow(c))
    }

    @Test
    fun todayWithoutATrackedLowPrintsTheForecastLow() {
        assertEquals(49f, DailyColumnBuilder.textLow(column(isToday = true, forecastLow = 49f)))
    }

    @Test
    fun pastAndFuturePrintTheirOwnValues() {
        val past = column(isPast = true, solidHigh = 66f, solidLow = 51f, forecastHigh = 70f, snapshotHigh = 72f, snapshotLow = 45f)
        assertEquals(66f, DailyColumnBuilder.textHigh(past))
        assertEquals(51f, DailyColumnBuilder.textLow(past))
        val future = column(solidHigh = 71f, solidLow = 48f)
        assertEquals(71f, DailyColumnBuilder.textHigh(future))
        assertEquals(48f, DailyColumnBuilder.textLow(future))
    }
}
