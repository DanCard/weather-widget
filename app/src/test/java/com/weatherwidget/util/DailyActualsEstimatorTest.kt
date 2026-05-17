package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.dateEpoch
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class DailyActualsEstimatorTest {

    private val today = LocalDate.of(2026, 2, 25)
    private val now = LocalDateTime.of(2026, 2, 25, 14, 0) // 2:00 PM
    private val displaySource = WeatherSource.OPEN_METEO
    private val fallbackWeather = ForecastEntity(
        targetDate = dateEpoch("2026-02-25"),
        forecastDate = dateEpoch("2026-02-25"),
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
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T05:00"), 0.0, 0.0, 40f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L),
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T14:00"), 0.0, 0.0, 60f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L),
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T16:00"), 0.0, 0.0, 68f, "Sunny", "OPEN_METEO", 0, 0, null, 1L),
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T23:00"), 0.0, 0.0, 38f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L)
        )
        val sourceActuals = mapOf(
            today to com.weatherwidget.widget.ObservationResolver.DailyActual(
                date = today,
                highTemp = 60f,
                lowTemp = 40f,
                condition = "Cloudy",
            )
        )

        val nowEarly = LocalDateTime.of(2026, 2, 25, 14, 0)
        val valuesEarly = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, nowEarly, displaySource, fallbackWeather, sourceActuals
        )

        assertEquals(40f, valuesEarly.solidLineLow!!, 0.01f)
        assertEquals(60f, valuesEarly.solidLineHigh!!, 0.01f)
        assertEquals(38f, valuesEarly.dashedLineLow!!, 0.01f)
        assertEquals(65f, valuesEarly.dashedLineHigh!!, 0.01f)

        val nowLate = LocalDateTime.of(2026, 2, 25, 17, 0)
        val valuesLate = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, nowLate, displaySource, fallbackWeather, sourceActuals
        )

        assertEquals(40f, valuesLate.solidLineLow!!, 0.01f)
        assertEquals(60f, valuesLate.solidLineHigh!!, 0.01f)
    }

    @Test
    fun calculateTodayTripleLineValues_withoutSourceActuals_leavesObservedBlank() {
        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            emptyList(), today, now, displaySource, fallbackWeather
        )

        org.junit.Assert.assertNull(values.solidLineLow)
        org.junit.Assert.assertNull(values.solidLineHigh)
        assertEquals(45f, values.dashedLineLow!!, 0.01f)
        assertEquals(65f, values.dashedLineHigh!!, 0.01f)
    }

    @Test
    fun calculateTodayTripleLineValues_filtersBySource() {
        val hourly = listOf(
            // NWS data: low of 40
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T05:00"), 0.0, 0.0, 40f, "Cloudy", "NWS", 0, 0, null, 1L),
            // WeatherAPI data: low of 42
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T05:00"), 0.0, 0.0, 42f, "Cloudy", "WEATHER_API", 0, 0, null, 1L),
            // Generic GAP data: low of 45 (should be included as fallback/average)
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T05:00"), 0.0, 0.0, 45f, "Cloudy", "Generic", 0, 0, null, 1L)
        )

        // To test filtering logic without daily fallback interference, use empty daily values
        val emptyFallback = fallbackWeather.copy(highTemp = null, lowTemp = null)

        // Test NWS filtering
        val valuesNWS = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, now, WeatherSource.NWS, emptyFallback
        )
        org.junit.Assert.assertNull(valuesNWS.solidLineLow)

        // Test WeatherAPI filtering
        val valuesWAPI = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, now, WeatherSource.WEATHER_API, emptyFallback
        )
        org.junit.Assert.assertNull(valuesWAPI.solidLineLow)
    }

    @Test
    fun calculateTodayTripleLineValues_nwsTodayLow_doesNotRiseAboveForecastLow() {
        val nwsFallback = fallbackWeather.copy(
            highTemp = 78f,
            lowTemp = 49f,
            source = WeatherSource.NWS.id,
        )
        val hourly = listOf(
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T12:00"), 0.0, 0.0, 72f, "Sunny", WeatherSource.NWS.id, 0, 0, null, 1L),
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T14:00"), 0.0, 0.0, 71.4f, "Sunny", WeatherSource.NWS.id, 0, 0, null, 1L),
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T16:00"), 0.0, 0.0, 78f, "Sunny", WeatherSource.NWS.id, 0, 0, null, 1L),
        )

        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly,
            today,
            now,
            WeatherSource.NWS,
            nwsFallback,
        )

        assertEquals(49f, values.dashedLineLow!!, 0.01f)
        org.junit.Assert.assertNull(values.solidLineLow)
    }

    // --- solidLineHigh selection: currentTemp vs actual.highTemp ---

    @Test
    fun calculateTodayTripleLineValues_currentTempHigherThanActual_currentTempWinsAsObservedHigh() {
        // actual.highTemp=75°, currentTemp=82° → solidLineHigh should be 82° (thermometer top)
        // trueActualHigh should be 75° (for ghost bar logic, though ghost bar won't show if trueActualHigh <= solidLineHigh)
        val actuals = mapOf(
            today to com.weatherwidget.widget.ObservationResolver.DailyActual(
                date = today, highTemp = 75f, lowTemp = 50f, condition = "Clear"
            )
        )
        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = emptyList(),
            today = today,
            now = now,
            displaySource = displaySource,
            fallbackWeather = fallbackWeather,
            dailyActuals = actuals,
            currentTemp = 82f,
        )

        assertEquals(82f, values.solidLineHigh!!, 0.01f)
        assertEquals(75f, values.ghostLineHigh!!, 0.01f)
    }

    @Test
    fun calculateTodayTripleLineValues_actualHigherThanCurrentTemp_solidLineHighDropsToCurrentTemp() {
        // actual.high=85°, currentTemp=81°
        // solidLineHigh should now be 81° (the mercury level),
        // with trueActualHigh=85° preserving the ghost bar peak.
        val actuals = mapOf(
            today to com.weatherwidget.widget.ObservationResolver.DailyActual(
                date = today, highTemp = 85f, lowTemp = 55f, condition = "Sunny"
            )
        )
        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = emptyList(),
            today = today,
            now = now,
            displaySource = displaySource,
            fallbackWeather = fallbackWeather,
            dailyActuals = actuals,
            currentTemp = 81f,
        )

        assertEquals(81f, values.solidLineHigh!!, 0.01f) // NOW 81, WAS 85
        assertEquals(85f, values.ghostLineHigh!!, 0.01f)
        assertEquals(55f, values.solidLineLow!!, 0.01f)
    }

    // --- finalHigh fallback chain when no observations ---

    @Test
    fun calculateTodayTripleLineValues_noObservationsNorCurrentTemp_solidLineHighIsNull() {
        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = emptyList(),
            today = today,
            now = now,
            displaySource = displaySource,
            fallbackWeather = fallbackWeather,
            dailyActuals = emptyMap(),
            currentTemp = null,
        )

        org.junit.Assert.assertNull(values.solidLineHigh)
        // dashedLineHigh should still be populated from fallbackWeather
        assertEquals(65f, values.dashedLineHigh!!, 0.01f)
    }

    @Test
    fun calculateTodayTripleLineValues_noObservationsNoFallbackWeather_dashedLineHighFromHourlyMax() {
        val hourly = listOf(
            com.weatherwidget.data.local.HourlyForecastEntity(
                TestData.toEpoch("2026-02-25T15:00"), 0.0, 0.0, 71f, "Clear", displaySource.id, 0, 0, null, 1L
            )
        )
        val emptyFallback = fallbackWeather.copy(highTemp = null, lowTemp = null)

        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = hourly,
            today = today,
            now = now,
            displaySource = displaySource,
            fallbackWeather = emptyFallback,
            dailyActuals = emptyMap(),
            currentTemp = null,
        )

        org.junit.Assert.assertNull(values.solidLineHigh)
        assertEquals(71f, values.dashedLineHigh!!, 0.01f)
    }

    @Test
    fun calculateTodayTripleLineValues_preservesPrecision() {
        val hourly = listOf(
            // High of 61.7, Low of 58.2
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T14:00"), 0.0, 0.0, 61.7f, "Sunny", "OPEN_METEO", 0, 0, null, 1L),
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T05:00"), 0.0, 0.0, 58.2f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L)
        )

        // Use empty daily values to trigger hourly fallback
        val emptyFallback = fallbackWeather.copy(highTemp = null, lowTemp = null)

        val values = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourly, today, now, WeatherSource.OPEN_METEO, emptyFallback
        )

        // 61.7 is preserved
        assertEquals(61.7f, values.dashedLineHigh!!, 0.01f)
        // 58.2 is preserved
        assertEquals(58.2f, values.dashedLineLow!!, 0.01f)
    }
}
