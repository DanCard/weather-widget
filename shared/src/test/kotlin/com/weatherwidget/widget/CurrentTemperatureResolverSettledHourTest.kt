package com.weatherwidget.widget

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The current hour has started, so it stands at its newest pre-hour forecast — the graph's rule
 * (SettledForecastPick). Android fed the resolver live rows, whose current hour was a post-hour
 * revision, while desktop fed stitched ones; the headers disagreed.
 * Plan: plans/261027-current-temp-reads-settled-hourly.md.
 */
@Category(ShortDuration::class)
class CurrentTemperatureResolverSettledHourTest {
    private val zone = ZoneId.systemDefault()
    private val day = LocalDateTime.of(2026, 10, 27, 0, 0)
    private fun ms(hour: Int, minute: Int = 0) = day.withHour(hour).withMinute(minute).atZone(zone).toInstant().toEpochMilli()

    private fun row(hour: Int, temp: Float, fetchedAt: Long) = HourlyForecast(
        dateTime = ms(hour), temperature = temp, condition = "Clear",
        source = WeatherSource.OPEN_METEO.id, fetchedAt = fetchedAt,
    )

    /** Live + history as the resolver may receive them: noon twice, before and after it began. */
    private val rows = listOf(
        row(12, 60f, fetchedAt = ms(11, 40)),
        row(12, 70f, fetchedAt = ms(12, 20)),
        row(13, 64f, fetchedAt = ms(12, 20)),
    )

    @Test
    fun `the started hour interpolates from its pre-hour forecast`() {
        val result = CurrentTemperatureResolver.resolve(
            now = day.withHour(12).withMinute(30),
            displaySource = WeatherSource.OPEN_METEO,
            hourlyForecasts = rows,
            lastObservedTemp = null,
            observedAt = null,
        )

        // 60 -> 64 at half past: 62. The post-hour 70 gave 67.
        assertEquals(62f, result.estimatedTemp!!, 0.01f)
    }

    @Test
    fun `smoothed forecasts follow the same pick`() {
        val smoothed = CurrentTemperatureResolver.computeSmoothedForecasts(
            rows, WeatherSource.OPEN_METEO.id, now = ms(12, 30),
        )

        assertEquals(60f, smoothed.getValue(ms(12)), 0.01f)
    }

    @Test
    fun `an hour not yet started keeps the newest fetch`() {
        val smoothed = CurrentTemperatureResolver.computeSmoothedForecasts(
            rows, WeatherSource.OPEN_METEO.id, now = ms(11, 50),
        )

        assertEquals(70f, smoothed.getValue(ms(12)), 0.01f)
    }
}
