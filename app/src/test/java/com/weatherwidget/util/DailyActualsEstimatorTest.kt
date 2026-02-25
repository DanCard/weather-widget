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
        highTemp = 65f,
        lowTemp = 45f,
        currentTemp = 60f,
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
        assertEquals(40f, valuesEarly.observedLow!!, 0.01f)
        // High should be the expected high from hourly data (68) because it's before 4 PM
        assertEquals(68f, valuesEarly.observedHigh!!, 0.01f)

        // Full-day prediction: low=45, high=65 (from fallbackWeather)
        assertEquals(45f, valuesEarly.forecastLow!!, 0.01f)
        assertEquals(65f, valuesEarly.forecastHigh!!, 0.01f)

        // Scenario 2: After 4:00 PM (e.g., 5:00 PM)
        val nowLate = LocalDateTime.of(2026, 2, 25, 17, 0)
        val valuesLate = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, nowLate, displaySource, fallbackWeather
        )

        // Observed so far: low=40
        assertEquals(40f, valuesLate.observedLow!!, 0.01f)
        // High should now be the actual peak observed today (68)
        assertEquals(68f, valuesLate.observedHigh!!, 0.01f)

        // Example with lower actual peak observed after 4 PM
        val hourlyLowerPeak = listOf(
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 40f, "Cloudy", "OPEN_METEO", 0, 0),
            HourlyForecastEntity("2026-02-25T14:00", 0.0, 0.0, 55f, "Cloudy", "OPEN_METEO", 0, 0), // Actual peak so far
            HourlyForecastEntity("2026-02-25T20:00", 0.0, 0.0, 45f, "Cloudy", "OPEN_METEO", 0, 0)  // Cooling down
        )
        // Manual forecast high for this test set is 65 (from fallbackWeather).
        // Before 4 PM: Should return expected high from hourly data (55)
        val valuesEarlyHigher = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyLowerPeak, today, nowEarly, displaySource, fallbackWeather
        )
        assertEquals(55f, valuesEarlyHigher.observedHigh!!, 0.01f)

        // After 4 PM: Should return actual high so far (55)
        val valuesLateHigher = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyLowerPeak, today, nowLate, displaySource, fallbackWeather
        )
        assertEquals(55f, valuesLateHigher.observedHigh!!, 0.01f)
    }

    @Test
    fun calculateTodayTripleLineValues_emptyHourly_usesFallback() {
        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            emptyList(), today, now, displaySource, fallbackWeather
        )

        assertEquals(45f, values.observedLow!!, 0.01f)
        assertEquals(65f, values.observedHigh!!, 0.01f)
        assertEquals(45f, values.forecastLow!!, 0.01f)
        assertEquals(65f, values.forecastHigh!!, 0.01f)
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

        // To test filtering logic without daily fallback interference, use empty daily values
        val emptyFallback = fallbackWeather.copy(highTemp = null, lowTemp = null)

        // Test NWS filtering
        val valuesNWS = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, now, WeatherSource.NWS, emptyFallback
        )
        // Should use NWS (40) and Generic (45). Min is 40.
        assertEquals(40f, valuesNWS.observedLow!!, 0.01f)

        // Test WeatherAPI filtering
        val valuesWAPI = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, now, WeatherSource.WEATHER_API, emptyFallback
        )
        // Should use WeatherAPI (42) and Generic (45). Min is 42.
        assertEquals(42f, valuesWAPI.observedLow!!, 0.01f)
    }

    @Test
    fun calculateTodayTripleLineValues_preservesPrecision() {
        val hourly = listOf(
            // High of 61.7, Low of 58.2
            HourlyForecastEntity("2026-02-25T14:00", 0.0, 0.0, 61.7f, "Sunny", "OPEN_METEO", 0, 0),
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 58.2f, "Cloudy", "OPEN_METEO", 0, 0)
        )

        // Use empty daily values to trigger hourly fallback
        val emptyFallback = fallbackWeather.copy(highTemp = null, lowTemp = null)

        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, now, WeatherSource.OPEN_METEO, emptyFallback
        )

        // 61.7 is preserved
        assertEquals(61.7f, values.forecastHigh!!, 0.01f)
        // 58.2 is preserved
        assertEquals(58.2f, values.forecastLow!!, 0.01f)
    }
}
