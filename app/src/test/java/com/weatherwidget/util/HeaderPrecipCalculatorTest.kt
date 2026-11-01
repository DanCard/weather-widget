package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
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
