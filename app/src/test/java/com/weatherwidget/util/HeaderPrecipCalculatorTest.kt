package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class HeaderPrecipCalculatorTest {
    private val now = LocalDateTime.of(2026, 2, 22, 10, 0)

    @Test
    fun `returns null when forward-looking precip is zero`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T09:00", "NWS", 70),
                hourly("2026-02-22T10:00", "NWS", 0),
                hourly("2026-02-22T11:00", "NWS", 0),
            )

        val result =
            HeaderPrecipCalculator.getForwardLookingTodayPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 4,
                now = now,
            )

        assertNull(result)
    }

    @Test
    fun `uses max forward-looking value for selected source`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T09:00", "NWS", 80),
                hourly("2026-02-22T10:00", "NWS", 12),
                hourly("2026-02-22T11:00", "NWS", 28),
                hourly("2026-02-22T12:00", "NWS", 5),
            )

        val result =
            HeaderPrecipCalculator.getForwardLookingTodayPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 1,
                now = now,
            )

        assertEquals(28, result)
    }

    @Test
    fun `falls back to generic gap when selected source is unavailable`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T10:00", WeatherSource.GENERIC_GAP.id, 17),
                hourly("2026-02-22T11:00", WeatherSource.GENERIC_GAP.id, 9),
            )

        val result =
            HeaderPrecipCalculator.getForwardLookingTodayPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.OPEN_METEO,
                fallbackDailyProbability = null,
                now = now,
            )

        assertEquals(17, result)
    }

    @Test
    fun `uses fallback daily probability when no forward-looking hourly data exists`() {
        val forecasts =
            listOf(
                hourly("2026-02-21T23:00", "NWS", 50),
                hourly("2026-02-23T00:00", "NWS", 60),
            )

        val result =
            HeaderPrecipCalculator.getForwardLookingTodayPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 22,
                now = now,
            )

        assertEquals(22, result)
    }

    @Test
    fun `returns null when fallback is zero and no forward-looking hourly data exists`() {
        val result =
            HeaderPrecipCalculator.getForwardLookingTodayPrecipProbability(
                hourlyForecasts = emptyList(),
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 0,
                now = now,
            )

        assertNull(result)
    }

    private fun hourly(
        dateTime: String,
        source: String,
        precipProbability: Int?,
    ): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = dateTime,
            locationLat = 37.422,
            locationLon = -122.084,
            temperature = 55f,
            condition = "Clear",
            source = source,
            precipProbability = precipProbability,
            fetchedAt = 0L,
        )
    }
}
