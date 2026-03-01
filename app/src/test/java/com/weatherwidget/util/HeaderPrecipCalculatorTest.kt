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
    fun `returns zero when next 8 hour precip is zero`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T09:00", "NWS", 70),
                hourly("2026-02-22T10:00", "NWS", 0),
                hourly("2026-02-22T11:00", "NWS", 0),
            )

        val result =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 4,
                referenceTime = now,
            )

        assertEquals(0, result)
    }

    @Test
    fun `uses max next 8 hour value for selected source`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T09:00", "NWS", 80),
                hourly("2026-02-22T10:00", "NWS", 12),
                hourly("2026-02-22T11:00", "NWS", 28),
                hourly("2026-02-22T12:00", "NWS", 5),
            )

        val result =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 1,
                referenceTime = now,
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
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.OPEN_METEO,
                fallbackDailyProbability = null,
                referenceTime = now,
            )

        assertEquals(17, result)
    }

    @Test
    fun `uses fallback daily probability when no next 8 hour data exists`() {
        val forecasts =
            listOf(
                hourly("2026-02-21T23:00", "NWS", 50),
                hourly("2026-02-23T00:00", "NWS", 60),
            )

        val result =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 22,
                referenceTime = now,
            )

        assertEquals(22, result)
    }

    @Test
    fun `ignores values beyond next 8 hours`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T10:00", "NWS", 10),
                hourly("2026-02-22T17:00", "NWS", 20), // within 8h window
                hourly("2026-02-22T18:00", "NWS", 99), // outside 8h window (end-exclusive)
                hourly("2026-02-22T21:00", "NWS", 80),
            )

        val result =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 5,
                referenceTime = now,
            )

        assertEquals(20, result)
    }

    @Test
    fun `includes next-day hours when they are within next 8 hours`() {
        val eveningNow = LocalDateTime.of(2026, 2, 22, 18, 0)
        val forecasts =
            listOf(
                hourly("2026-02-22T21:00", "NWS", 25),
                hourly("2026-02-23T00:00", "NWS", 40), // +6h (included)
                hourly("2026-02-23T02:00", "NWS", 80), // +8h (excluded)
            )

        val result =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 5,
                referenceTime = eveningNow,
            )

        assertEquals(40, result)
    }

    @Test
    fun `returns zero when fallback is zero and no next 8 hour data exists`() {
        val result =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = emptyList(),
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 0,
                referenceTime = now,
            )

        assertEquals(0, result)
    }

    @Test
    fun `getPrecipTextSize returns correct sizes based on probability`() {
        // Base size is 26f
        // <= 8% -> 26 * 0.7 = 18.2
        assertEquals(18.2f, HeaderPrecipCalculator.getPrecipTextSize(0), 0.01f)
        assertEquals(18.2f, HeaderPrecipCalculator.getPrecipTextSize(5), 0.01f)
        assertEquals(18.2f, HeaderPrecipCalculator.getPrecipTextSize(8), 0.01f)

        // <= 15% -> 26 * 0.8 = 20.8
        assertEquals(20.8f, HeaderPrecipCalculator.getPrecipTextSize(9), 0.01f)
        assertEquals(20.8f, HeaderPrecipCalculator.getPrecipTextSize(12), 0.01f)
        assertEquals(20.8f, HeaderPrecipCalculator.getPrecipTextSize(15), 0.01f)

        // <= 25% -> 26 * 0.9 = 23.4
        assertEquals(23.4f, HeaderPrecipCalculator.getPrecipTextSize(16), 0.01f)
        assertEquals(23.4f, HeaderPrecipCalculator.getPrecipTextSize(20), 0.01f)
        assertEquals(23.4f, HeaderPrecipCalculator.getPrecipTextSize(25), 0.01f)

        // > 25% -> 26
        assertEquals(26.0f, HeaderPrecipCalculator.getPrecipTextSize(26), 0.01f)
        assertEquals(26.0f, HeaderPrecipCalculator.getPrecipTextSize(50), 0.01f)
        assertEquals(26.0f, HeaderPrecipCalculator.getPrecipTextSize(100), 0.01f)
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
