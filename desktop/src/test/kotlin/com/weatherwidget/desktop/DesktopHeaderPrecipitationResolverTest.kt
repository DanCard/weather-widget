package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.test.category.ShortDuration
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopHeaderPrecipitationResolverTest {
    private val zone = ZoneId.systemDefault()

    @Test
    fun `daily header applies shared night scale`() {
        val now = LocalDateTime.of(2026, 6, 1, 22, 0)
        val result = DesktopHeaderPrecipitationResolver.resolve(
            hourlyForecasts = listOf(hourly(now, 90), hourly(now.plusHours(1), 90)),
            displaySource = WeatherSource.NWS,
            fallbackDailyProbability = null,
            referenceTime = now,
            latitude = 37.422,
            longitude = -122.084,
            isDailyView = true,
        )

        assertEquals(90, result.probability)
        assertEquals(DailyRainLabels.NIGHT_SCALE, result.fontScale!!, 1e-6f)
    }

    @Test
    fun `hourly header does not apply night scale`() {
        val now = LocalDateTime.of(2026, 6, 1, 22, 0)
        val result = DesktopHeaderPrecipitationResolver.resolve(
            hourlyForecasts = listOf(hourly(now, 90), hourly(now.plusHours(1), 90)),
            displaySource = WeatherSource.NWS,
            fallbackDailyProbability = null,
            referenceTime = now,
            latitude = 37.422,
            longitude = -122.084,
            isDailyView = false,
        )

        assertEquals(1f, result.fontScale!!, 1e-6f)
    }

    @Test
    fun `zero probability is hidden`() {
        val now = LocalDateTime.of(2026, 6, 1, 12, 0)
        val result = DesktopHeaderPrecipitationResolver.resolve(
            hourlyForecasts = emptyList(),
            displaySource = WeatherSource.NWS,
            fallbackDailyProbability = 0,
            referenceTime = now,
            latitude = 37.422,
            longitude = -122.084,
            isDailyView = true,
        )

        assertNull(result.probability)
        assertNull(result.fontScale)
    }

    private fun hourly(time: LocalDateTime, probability: Int) = HourlyForecast(
        dateTime = time.atZone(zone).toInstant().toEpochMilli(),
        temperature = 60f,
        condition = "Rain",
        precipProbability = probability,
        source = WeatherSource.NWS.id,
    )
}
