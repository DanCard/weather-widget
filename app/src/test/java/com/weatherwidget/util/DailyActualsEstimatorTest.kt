package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DailyHistory
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
        highTemp = 68f,
        lowTemp = 38f,
        condition = "Cloudy",
        source = "OPEN_METEO",
        fetchedAt = System.currentTimeMillis()
    )

    private fun extreme(date: LocalDate, high: Float, low: Float) = DailyHistory(
        date = date.toEpochDay() * 86_400_000L,
        source = "OPEN_METEO",
        locationLat = 0.0,
        locationLon = 0.0,
        highTemp = high,
        lowTemp = low,
        condition = "Cloudy",
        updatedAt = System.currentTimeMillis()
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
            today to extreme(today, 60f, 40f)
        )

        val result = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = hourly,
            today = today,
            now = now,
            displaySource = displaySource,
            fallbackWeather = fallbackWeather,
            dailyActuals = sourceActuals,
            currentTemp = 62f
        )

        assertEquals(62f, result.solidLineHigh) // currentTemp wins over peak-so-far (60)
        assertEquals(40f, result.solidLineLow)      // min(actualLow 40, current 62)
        assertEquals(60f, result.ghostLineHigh) // peak-so-far
        assertEquals(68f, result.dashedLineHigh) // full-day peak
        assertEquals(38f, result.dashedLineLow)  // full-day low
    }

    @Test
    fun calculateTodayTripleLineValues_noActuals_usesForecasts() {
        val hourly = listOf(
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T05:00"), 0.0, 0.0, 40f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L),
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T14:00"), 0.0, 0.0, 60f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L)
        )

        val result = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = hourly,
            today = today,
            now = now,
            displaySource = displaySource,
            fallbackWeather = fallbackWeather,
            dailyActuals = emptyMap()
        )

        assertEquals(null, result.solidLineHigh)
        assertEquals(null, result.solidLineLow)
        assertEquals(68f, result.dashedLineHigh) // fallbackWeather high
        assertEquals(38f, result.dashedLineLow)  // fallbackWeather low
    }

    @Test
    fun calculateTodayTripleLineValues_prefersOfficialDailyExtremesForDashedLine() {
        val hourly = listOf(
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T05:00"), 0.0, 0.0, 40f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L),
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T14:00"), 0.0, 0.0, 60f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L)
        )
        val officialWeather = fallbackWeather.copy(highTemp = 75f, lowTemp = 35f)

        val result = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = hourly,
            today = today,
            now = now,
            displaySource = displaySource,
            fallbackWeather = officialWeather,
            dailyActuals = emptyMap()
        )

        assertEquals(75f, result.dashedLineHigh)
        assertEquals(35f, result.dashedLineLow)
    }

    @Test
    fun estimateTodayActualsFromHourly_returnsMaxMin() {
        val hourly = listOf(
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T05:00"), 0.0, 0.0, 40f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L),
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T14:00"), 0.0, 0.0, 70f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L),
            HourlyForecastEntity(TestData.toEpoch("2026-02-25T23:00"), 0.0, 0.0, 35f, "Cloudy", "OPEN_METEO", 0, 0, null, 1L)
        )

        val result = DailyActualsEstimator.estimateTodayActualsFromHourly(
            hourlyForecasts = hourly,
            today = today,
            displaySource = displaySource,
            fallbackWeather = fallbackWeather
        )

        assertEquals(70f, result.first)
        assertEquals(35f, result.second)
    }

    @Test
    fun calculateTodayTripleLineValues_currentTempBelowActualLow_updatesSolidLow() {
        val sourceActuals = mapOf(
            today to extreme(today, 60f, 50f)
        )

        val result = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = emptyList(),
            today = today,
            now = now,
            displaySource = displaySource,
            fallbackWeather = fallbackWeather,
            dailyActuals = sourceActuals,
            currentTemp = 45f
        )

        assertEquals(45f, result.solidLineHigh) // mercury level drops to current
        assertEquals(45f, result.solidLineLow) // current (45) is lower than low-so-far (50)
    }

    @Test
    fun calculateTodayTripleLineValues_currentTempAboveActualHigh_updatesSolidHigh() {
        val sourceActuals = mapOf(
            today to extreme(today, 60f, 50f)
        )

        val result = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = emptyList(),
            today = today,
            now = now,
            displaySource = displaySource,
            fallbackWeather = fallbackWeather,
            dailyActuals = sourceActuals,
            currentTemp = 65f
        )

        assertEquals(65f, result.solidLineHigh) // current (65) is higher than high-so-far (60)
        assertEquals(50f, result.solidLineLow)
    }
}
