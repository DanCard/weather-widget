package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
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
    fun `smooths value through the hour instead of dropping at hour boundary`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T10:00", "NWS", 80),
                hourly("2026-02-22T11:00", "NWS", 20),
                hourly("2026-02-22T12:00", "NWS", 20),
            )

        val onTheHour =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = null,
                referenceTime = LocalDateTime.of(2026, 2, 22, 10, 0),
            )
        val halfPast =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = null,
                referenceTime = LocalDateTime.of(2026, 2, 22, 10, 30),
            )

        assertEquals(80, onTheHour)
        assertEquals(50, halfPast)
    }

    @Test
    fun `later peak still dominates when it remains inside the rolling window`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T10:00", "NWS", 80),
                hourly("2026-02-22T11:00", "NWS", 20),
                hourly("2026-02-22T13:00", "NWS", 90),
                hourly("2026-02-22T14:00", "NWS", 90),
            )

        val result =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = null,
                referenceTime = LocalDateTime.of(2026, 2, 22, 10, 30),
            )

        assertEquals(90, result)
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
    fun `smoothly includes rise approaching the end of the rolling window`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T10:00", "NWS", 10),
                hourly("2026-02-22T17:00", "NWS", 20), // within 8h window
                hourly("2026-02-22T18:00", "NWS", 99), // enters via interpolation before the edge
                hourly("2026-02-22T21:00", "NWS", 80),
            )

        val result =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 5,
                referenceTime = now,
            )

        assertEquals(98, result)
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
    fun `uses single available hourly point when interpolation neighbors are missing`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T14:00", "NWS", 37),
            )

        val result =
            HeaderPrecipCalculator.getNext8HourPrecipProbability(
                hourlyForecasts = forecasts,
                displaySource = WeatherSource.NWS,
                fallbackDailyProbability = 5,
                referenceTime = LocalDateTime.of(2026, 2, 22, 13, 10),
            )

        assertEquals(37, result)
    }

    @Test
    fun `uses fallback daily probability when hourly values are all null`() {
        val forecasts =
            listOf(
                hourly("2026-02-22T10:00", "NWS", null),
                hourly("2026-02-22T11:00", "NWS", null),
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
    fun `getPrecipScaleFactor returns correct multipliers based on probability`() {
        // Powers-of-two thresholds: 0.3x … 1.0x
        assertEquals(0.3f, HeaderPrecipCalculator.getPrecipScaleFactor(0),  0.01f)
        assertEquals(0.3f, HeaderPrecipCalculator.getPrecipScaleFactor(1),  0.01f)
        assertEquals(0.4f, HeaderPrecipCalculator.getPrecipScaleFactor(2),  0.01f)
        assertEquals(0.5f, HeaderPrecipCalculator.getPrecipScaleFactor(3),  0.01f)
        assertEquals(0.5f, HeaderPrecipCalculator.getPrecipScaleFactor(4),  0.01f)
        assertEquals(0.6f, HeaderPrecipCalculator.getPrecipScaleFactor(5),  0.01f)
        assertEquals(0.6f, HeaderPrecipCalculator.getPrecipScaleFactor(8),  0.01f)
        assertEquals(0.7f, HeaderPrecipCalculator.getPrecipScaleFactor(9),  0.01f)
        assertEquals(0.7f, HeaderPrecipCalculator.getPrecipScaleFactor(15), 0.01f)
        assertEquals(0.7f, HeaderPrecipCalculator.getPrecipScaleFactor(16), 0.01f)
        assertEquals(0.8f, HeaderPrecipCalculator.getPrecipScaleFactor(17), 0.01f)
        assertEquals(0.8f, HeaderPrecipCalculator.getPrecipScaleFactor(32), 0.01f)
        assertEquals(0.9f, HeaderPrecipCalculator.getPrecipScaleFactor(33), 0.01f)
        assertEquals(0.9f, HeaderPrecipCalculator.getPrecipScaleFactor(64), 0.01f)
        assertEquals(1.0f, HeaderPrecipCalculator.getPrecipScaleFactor(65), 0.01f)
        assertEquals(1.0f, HeaderPrecipCalculator.getPrecipScaleFactor(100), 0.01f)
    }

    @Test
    fun `getPrecipTextSize returns correct sizes based on probability`() {
        // Base size is 18f; thresholds are powers of two
        // <= 1%  -> 18 * 0.3 = 5.4
        assertEquals(5.4f, HeaderPrecipCalculator.getPrecipTextSize(0), 0.01f)
        assertEquals(5.4f, HeaderPrecipCalculator.getPrecipTextSize(1), 0.01f)

        // <= 2%  -> 18 * 0.4 = 7.2
        assertEquals(7.2f, HeaderPrecipCalculator.getPrecipTextSize(2), 0.01f)

        // <= 4%  -> 18 * 0.5 = 9.0
        assertEquals(9.0f, HeaderPrecipCalculator.getPrecipTextSize(3), 0.01f)
        assertEquals(9.0f, HeaderPrecipCalculator.getPrecipTextSize(4), 0.01f)

        // <= 8%  -> 18 * 0.6 = 10.8
        assertEquals(10.8f, HeaderPrecipCalculator.getPrecipTextSize(5), 0.01f)
        assertEquals(10.8f, HeaderPrecipCalculator.getPrecipTextSize(8), 0.01f)

        // <= 16% -> 18 * 0.7 = 12.6
        assertEquals(12.6f, HeaderPrecipCalculator.getPrecipTextSize(9),  0.01f)
        assertEquals(12.6f, HeaderPrecipCalculator.getPrecipTextSize(15), 0.01f)
        assertEquals(12.6f, HeaderPrecipCalculator.getPrecipTextSize(16), 0.01f)

        // <= 32% -> 18 * 0.8 = 14.4
        assertEquals(14.4f, HeaderPrecipCalculator.getPrecipTextSize(17), 0.01f)
        assertEquals(14.4f, HeaderPrecipCalculator.getPrecipTextSize(25), 0.01f)
        assertEquals(14.4f, HeaderPrecipCalculator.getPrecipTextSize(32), 0.01f)

        // <= 64% -> 18 * 0.9 = 16.2
        assertEquals(16.2f, HeaderPrecipCalculator.getPrecipTextSize(33), 0.01f)
        assertEquals(16.2f, HeaderPrecipCalculator.getPrecipTextSize(50), 0.01f)
        assertEquals(16.2f, HeaderPrecipCalculator.getPrecipTextSize(64), 0.01f)

        // > 64% -> 18.0
        assertEquals(18.0f, HeaderPrecipCalculator.getPrecipTextSize(65),  0.01f)
        assertEquals(18.0f, HeaderPrecipCalculator.getPrecipTextSize(100), 0.01f)
    }

    @Test
    fun `NIGHT_SCALE matches the shared DailyRainLabels constant`() {
        assertEquals(
            com.weatherwidget.shared.util.DailyRainLabels.NIGHT_SCALE,
            HeaderPrecipCalculator.NIGHT_SCALE,
            0.0f,
        )
    }

    // ── Night detection ──────────────────────────────────────────────────────

    @Test
    fun `isNext8HourPrecipPredominantlyNight returns false when peak rain is during the day`() {
        // referenceTime = 08:00; all rain is 10:00–14:00 (daytime, sunrise=6, sunset=20)
        val forecasts = listOf(
            hourly("2026-02-22T10:00", "NWS", 80),
            hourly("2026-02-22T12:00", "NWS", 60),
            hourly("2026-02-22T14:00", "NWS", 40),
        )
        val result = HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            referenceTime = LocalDateTime.of(2026, 2, 22, 8, 0),
            sunriseHour = 6.0,
            sunsetHour = 20.0,
        )
        assertEquals(false, result)
    }

    @Test
    fun `isNext8HourPrecipPredominantlyNight returns true when peak rain is at night`() {
        // referenceTime = 20:00; all rain is 21:00–23:00 (nighttime, sunset=20)
        val forecasts = listOf(
            hourly("2026-02-22T21:00", "NWS", 70),
            hourly("2026-02-22T22:00", "NWS", 80),
            hourly("2026-02-22T23:00", "NWS", 50),
        )
        val result = HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            referenceTime = LocalDateTime.of(2026, 2, 22, 20, 0),
            sunriseHour = 6.0,
            sunsetHour = 20.0,
        )
        assertEquals(true, result)
    }

    @Test
    fun `isNext8HourPrecipPredominantlyNight returns true when night probability mass exceeds day`() {
        // Split window: moderate rain before sunset, heavier rain after
        // referenceTime = 17:00, sunset = 19:00
        // 17:00–19:00 = 2h daytime at 40%, 19:00–01:00 = 6h nighttime at 60%
        val forecasts = listOf(
            hourly("2026-02-22T17:00", "NWS", 40),
            hourly("2026-02-22T19:00", "NWS", 60),
            hourly("2026-02-22T21:00", "NWS", 60),
        )
        val result = HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            referenceTime = LocalDateTime.of(2026, 2, 22, 17, 0),
            sunriseHour = 6.0,
            sunsetHour = 19.0,
        )
        assertEquals(true, result)
    }

    @Test
    fun `isNext8HourPrecipPredominantlyNight returns false when forecasts are empty`() {
        val result = HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(
            hourlyForecasts = emptyList(),
            displaySource = WeatherSource.NWS,
            referenceTime = LocalDateTime.of(2026, 2, 22, 22, 0),
            sunriseHour = 6.0,
            sunsetHour = 20.0,
        )
        assertEquals(false, result)
    }

    @Test
    fun `isNext8HourPrecipPredominantlyNight returns false for midnight sun`() {
        val forecasts = listOf(hourly("2026-02-22T22:00", "NWS", 90))
        val result = HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            referenceTime = LocalDateTime.of(2026, 2, 22, 21, 0),
            sunriseHour = 0.0,
            sunsetHour = 24.0,  // sun never sets
        )
        assertEquals(false, result)
    }

    @Test
    fun `isNext8HourPrecipPredominantlyNight returns true for polar night`() {
        val forecasts = listOf(hourly("2026-02-22T14:00", "NWS", 50))
        val result = HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            referenceTime = LocalDateTime.of(2026, 2, 22, 13, 0),
            sunriseHour = 0.0,  // sun never rises
            sunsetHour = 18.0,
        )
        assertEquals(true, result)
    }

    private fun hourly(
        dateTime: String,
        source: String,
        precipProbability: Int?,
    ): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = TestData.toEpoch(dateTime),
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
