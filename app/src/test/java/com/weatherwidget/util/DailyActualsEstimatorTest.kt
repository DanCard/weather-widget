package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DailyActualsEstimatorTest {

    private val today = LocalDate.of(2026, 2, 25)
    private val now = LocalDateTime.of(2026, 2, 25, 14, 0) // 2:00 PM
    private val displaySource = WeatherSource.OPEN_METEO
    private val fallbackWeather = ForecastEntity(
        targetDate = "2026-02-25",
        forecastDate = "2026-02-25",
        locationLat = 0.0,
        locationLon = 0.0,
        locationName = "Test",
        highTemp = 65f,
        lowTemp = 45f,
        condition = "Cloudy",
        source = "OPEN_METEO",
        fetchedAt = System.currentTimeMillis()
    )

    @Test
    fun calculateTodayTripleLineValues_withSourceActuals_correctlySeparatesObservedAndForecast() {
        val hourly = listOf(
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 40f, "Cloudy", "OPEN_METEO", 0, 0, 1L),
            HourlyForecastEntity("2026-02-25T14:00", 0.0, 0.0, 60f, "Cloudy", "OPEN_METEO", 0, 0, 1L),
            HourlyForecastEntity("2026-02-25T16:00", 0.0, 0.0, 68f, "Sunny", "OPEN_METEO", 0, 0, 1L),
            HourlyForecastEntity("2026-02-25T23:00", 0.0, 0.0, 38f, "Cloudy", "OPEN_METEO", 0, 0, 1L)
        )
        val sourceActuals = mapOf(
            today.toString() to com.weatherwidget.widget.ObservationResolver.DailyActual(
                date = today.toString(),
                highTemp = 60f,
                lowTemp = 40f,
                condition = "Cloudy",
            )
        )

        val nowEarly = LocalDateTime.of(2026, 2, 25, 14, 0)
        val valuesEarly = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, nowEarly, displaySource, fallbackWeather, sourceActuals
        )

        assertEquals(40f, valuesEarly.observedLow!!, 0.01f)
        assertEquals(60f, valuesEarly.observedHigh!!, 0.01f)
        assertEquals(38f, valuesEarly.forecastLow!!, 0.01f)
        assertEquals(65f, valuesEarly.forecastHigh!!, 0.01f)

        val nowLate = LocalDateTime.of(2026, 2, 25, 17, 0)
        val valuesLate = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, nowLate, displaySource, fallbackWeather, sourceActuals
        )

        assertEquals(40f, valuesLate.observedLow!!, 0.01f)
        assertEquals(60f, valuesLate.observedHigh!!, 0.01f)
    }

    @Test
    fun calculateTodayTripleLineValues_withoutSourceActuals_leavesObservedBlank() {
        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            emptyList(), today, now, displaySource, fallbackWeather
        )

        org.junit.Assert.assertNull(values.observedLow)
        org.junit.Assert.assertNull(values.observedHigh)
        assertEquals(45f, values.forecastLow!!, 0.01f)
        assertEquals(65f, values.forecastHigh!!, 0.01f)
    }

    @Test
    fun calculateTodayTripleLineValues_filtersBySource() {
        val hourly = listOf(
            // NWS data: low of 40
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 40f, "Cloudy", "NWS", 0, 0, 1L),
            // WeatherAPI data: low of 42
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 42f, "Cloudy", "WEATHER_API", 0, 0, 1L),
            // Generic GAP data: low of 45 (should be included as fallback/average)
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 45f, "Cloudy", "Generic", 0, 0, 1L)
        )

        // To test filtering logic without daily fallback interference, use empty daily values
        val emptyFallback = fallbackWeather.copy(highTemp = null, lowTemp = null)

        // Test NWS filtering
        val valuesNWS = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, now, WeatherSource.NWS, emptyFallback
        )
        org.junit.Assert.assertNull(valuesNWS.observedLow)

        // Test WeatherAPI filtering
        val valuesWAPI = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, now, WeatherSource.WEATHER_API, emptyFallback
        )
        org.junit.Assert.assertNull(valuesWAPI.observedLow)
    }

    @Test
    fun calculateTodayTripleLineValues_nwsTodayLow_doesNotRiseAboveForecastLow() {
        val nwsFallback = fallbackWeather.copy(
            highTemp = 78f,
            lowTemp = 49f,
            source = WeatherSource.NWS.id,
        )
        val hourly = listOf(
            HourlyForecastEntity("2026-02-25T12:00", 0.0, 0.0, 72f, "Sunny", WeatherSource.NWS.id, 0, 0, 1L),
            HourlyForecastEntity("2026-02-25T14:00", 0.0, 0.0, 71.4f, "Sunny", WeatherSource.NWS.id, 0, 0, 1L),
            HourlyForecastEntity("2026-02-25T16:00", 0.0, 0.0, 78f, "Sunny", WeatherSource.NWS.id, 0, 0, 1L),
        )

        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly,
            today,
            now,
            WeatherSource.NWS,
            nwsFallback,
        )

        assertEquals(49f, values.forecastLow!!, 0.01f)
        org.junit.Assert.assertNull(values.observedLow)
    }

    @Test
    fun calculateTodayTripleLineValues_preservesPrecision() {
        val hourly = listOf(
            // High of 61.7, Low of 58.2
            HourlyForecastEntity("2026-02-25T14:00", 0.0, 0.0, 61.7f, "Sunny", "OPEN_METEO", 0, 0, 1L),
            HourlyForecastEntity("2026-02-25T05:00", 0.0, 0.0, 58.2f, "Cloudy", "OPEN_METEO", 0, 0, 1L)
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
