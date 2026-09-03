package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.test.category.ShortDuration
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HeaderPrecipCalculatorTest {
    private val zone = ZoneId.systemDefault()

    @Test
    fun `adapter uses the shared six hour window`() {
        val now = LocalDateTime.of(2026, 2, 22, 10, 0)
        val result = HeaderPrecipCalculator.getNext6HourPrecipProbability(
            hourlyForecasts = listOf(
                hourly(now, WeatherSource.NWS.id, 20),
                hourly(now.plusHours(5), WeatherSource.NWS.id, 60),
                hourly(now.plusHours(7), WeatherSource.NWS.id, 95),
            ),
            displaySource = WeatherSource.NWS,
            fallbackDailyProbability = null,
            referenceTime = now,
        )

        assertEquals(60, result)
    }

    @Test
    fun `adapter maps generic fallback rows into the shared resolver`() {
        val now = LocalDateTime.of(2026, 2, 22, 10, 0)
        val result = HeaderPrecipCalculator.getNext6HourPrecipProbability(
            hourlyForecasts = listOf(hourly(now, WeatherSource.GENERIC_GAP.id, 17)),
            displaySource = WeatherSource.OPEN_METEO,
            fallbackDailyProbability = 5,
            referenceTime = now,
        )

        assertEquals(17, result)
    }

    @Test
    fun `combined resolver returns probability and night verdict from one adapter call`() {
        val now = LocalDateTime.of(2026, 2, 22, 20, 0)
        val result = HeaderPrecipCalculator.resolve(
            hourlyForecasts = listOf(
                hourly(now, WeatherSource.NWS.id, 70),
                hourly(now.plusHours(1), WeatherSource.NWS.id, 80),
            ),
            displaySource = WeatherSource.NWS,
            fallbackDailyProbability = null,
            referenceTime = now,
            sunriseHour = 6.0,
            sunsetHour = 20.0,
        )

        assertEquals(80, result.probability)
        assertEquals(true, result.isPredominantlyNight)
    }

    @Test
    fun `daily text size applies the shared night scale`() {
        val expected = 18f * DailyRainLabels.precipProbabilityScaleFactor(90) * DailyRainLabels.NIGHT_SCALE

        assertEquals(
            expected,
            HeaderPrecipCalculator.getPrecipTextSize(90, isDailyView = true, isNightPrecip = true),
            1e-6f,
        )
        assertEquals(
            18f,
            HeaderPrecipCalculator.getPrecipTextSize(90, isDailyView = false, isNightPrecip = true),
            1e-6f,
        )
    }

    private fun hourly(
        time: LocalDateTime,
        source: String,
        precipProbability: Int?,
    ) = HourlyForecastEntity(
        dateTime = time.atZone(zone).toInstant().toEpochMilli(),
        temperature = 60f,
        condition = "Rain",
        precipProbability = precipProbability,
        source = source,
        fetchedAt = 0L,
        locationLat = 37.422,
        locationLon = -122.084,
    )
}
