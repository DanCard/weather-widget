package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DailyActualsEstimatorTest {

    private val today = LocalDate.of(2026, 2, 25)
    private val now = LocalDateTime.of(2026, 2, 25, 14, 0) // 2:00 PM
    private val displaySource = WeatherSource.OPEN_METEO
    private val fallbackWeather = WeatherEntity(
        date = "2026-02-25",
        locationLat = 0.0,
        locationLon = 0.0,
        locationName = "Test",
        highTemp = 65,
        lowTemp = 45,
        currentTemp = 60,
        condition = "Cloudy",
        isActual = false,
        source = "OPEN_METEO",
        fetchedAt = System.currentTimeMillis()
    )

    @Test
    fun calculateTodayTripleLineValues_withHistory_correctlyExtractsObservedLow() {
        val hourly = listOf(
            // History: low of 40 at 5 AM
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 40f, "Cloudy", "OPEN_METEO", 0, 0),
            // Current-ish: 60 at 2 PM
            HourlyForecastEntity("2026-02-25T14:00", 0.0, 0.0, 60f, "Cloudy", "OPEN_METEO", 0, 0),
            // Future: high of 68 at 4 PM
            HourlyForecastEntity("2026-02-25T16:00", 0.0, 0.0, 68f, "Sunny", "OPEN_METEO", 0, 0),
            // Full-day forecast says low is 38 (e.g., late tonight)
            HourlyForecastEntity("2026-02-25T23:00", 0.0, 0.0, 38f, "Cloudy", "OPEN_METEO", 0, 0)
        )

        // Scenario 1: Before 4:00 PM (e.g., 2:00 PM)
        val nowEarly = LocalDateTime.of(2026, 2, 25, 14, 0)
        val valuesEarly = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, nowEarly, displaySource, fallbackWeather
        )

        // Observed so far: low=40
        assertEquals(40, valuesEarly.observedLow)
        // High should still be the full-day forecast high (68) because it's before 4 PM
        assertEquals(68, valuesEarly.observedHigh)

        // Full-day prediction: low=38, high=68
        assertEquals(38, valuesEarly.forecastLow)
        assertEquals(68, valuesEarly.forecastHigh)

        // Scenario 2: After 4:00 PM (e.g., 5:00 PM)
        val nowLate = LocalDateTime.of(2026, 2, 25, 17, 0)
        val valuesLate = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, nowLate, displaySource, fallbackWeather
        )

        // Observed so far: low=40
        assertEquals(40, valuesLate.observedLow)
        // High should now be the actual peak observed today (68)
        assertEquals(68, valuesLate.observedHigh)

        // Example with lower actual peak observed after 4 PM
        val hourlyLowerPeak = listOf(
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 40f, "Cloudy", "OPEN_METEO", 0, 0),
            HourlyForecastEntity("2026-02-25T14:00", 0.0, 0.0, 55f, "Cloudy", "OPEN_METEO", 0, 0), // Peak so far
            HourlyForecastEntity("2026-02-25T20:00", 0.0, 0.0, 45f, "Cloudy", "OPEN_METEO", 0, 0)  // Cooling down
        )
        // Manual forecast high for this test set is 55.
        // Let's add a future forecast that is HIGHER to test the cutoff logic properly.
        val hourlyWithHigherFuture = listOf(
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 40f, "Cloudy", "OPEN_METEO", 0, 0),
            HourlyForecastEntity("2026-02-25T14:00", 0.0, 0.0, 55f, "Cloudy", "OPEN_METEO", 0, 0), // Actual peak so far
            HourlyForecastEntity("2026-02-25T20:00", 0.0, 0.0, 68f, "Sunny", "OPEN_METEO", 0, 0)  // Predicted later peak (unusual but good for testing)
        )

        // Before 4 PM: Should return forecast high (68)
        val valuesEarlyHigher = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyWithHigherFuture, today, nowEarly, displaySource, fallbackWeather
        )
        assertEquals(68, valuesEarlyHigher.observedHigh)

        // After 4 PM: Should return actual high so far (55)
        val valuesLateHigher = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyWithHigherFuture, today, nowLate, displaySource, fallbackWeather
        )
        assertEquals(55, valuesLateHigher.observedHigh)
    }

    @Test
    fun calculateTodayTripleLineValues_emptyHourly_usesFallback() {
        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            emptyList(), today, now, displaySource, fallbackWeather
        )

        assertEquals(45, values.observedLow)
        assertEquals(65, values.observedHigh)
        assertEquals(45, values.forecastLow)
        assertEquals(65, values.forecastHigh)
    }

    @Test
    fun calculateTodayTripleLineValues_filtersBySource() {
        val hourly = listOf(
            // NWS data: low of 40
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 40f, "Cloudy", "NWS", 0, 0),
            // WeatherAPI data: low of 42
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 42f, "Cloudy", "WEATHER_API", 0, 0),
            // Generic GAP data: low of 45 (should be included as fallback/average)
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 45f, "Cloudy", "Generic", 0, 0)
        )

        // Test NWS filtering
        val valuesNWS = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, now, WeatherSource.NWS, fallbackWeather
        )
        // Should use NWS (40) and Generic (45). Min is 40.
        assertEquals(40, valuesNWS.observedLow)

        // Test WeatherAPI filtering
        val valuesWAPI = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, now, WeatherSource.WEATHER_API, fallbackWeather
        )
        // Should use WeatherAPI (42) and Generic (45). Min is 42.
        assertEquals(42, valuesWAPI.observedLow)
    }
}
