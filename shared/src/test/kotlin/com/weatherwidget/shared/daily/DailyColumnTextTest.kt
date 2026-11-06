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
        isClimateNormal: Boolean = false,
        forecastSource: String? = null,
    ) = DailyColumn(
        date = LocalDate.of(2026, 10, 23), label = "x",
        forecast = forecastSource?.let { com.weatherwidget.data.model.DailyForecast("2026-10-23", 72.4f, 50.6f, "Clear", source = it) }, actual = null, snapshot = null,
        solidHigh = solidHigh, solidLow = solidLow, forecastHigh = forecastHigh, forecastLow = forecastLow,
        barTopHigh = solidHigh ?: forecastHigh, ghostHigh = ghostHigh, snapshotHigh = snapshotHigh,
        snapshotLow = snapshotLow, iconCondition = null, iconName = "ic_weather_clear", isToday = isToday,
        isPast = isPast, solidIsForecastFallback = false, cloudCoverRatio = null, dailyRainLabelText = null,
        nightRainLabelText = null, dayPrecipProbability = null, nightPrecipProbability = null,
        daysFromToday = 0, isClimateNormal = isClimateNormal,
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

    /** Stored at full precision; only a future provider forecast prints whole degrees (user, 2026-11-05). */
    @Test
    fun onlyAFutureProviderForecastPrintsWholeDegrees() {
        assertEquals(true, column(forecastSource = "NWS").printsWholeDegrees)
        assertEquals(false, column(isToday = true, forecastSource = "NWS").printsWholeDegrees)
        assertEquals(false, column(isPast = true, forecastSource = "NWS").printsWholeDegrees)
        // Climate normals keep their tenth (Friday's 64.9° on the emulator, 2026-11-05).
        assertEquals(false, column(forecastSource = "NWS", isClimateNormal = true).printsWholeDegrees)
        assertEquals(false, column(forecastSource = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id).printsWholeDegrees)
    }
}
