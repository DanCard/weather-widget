package com.weatherwidget.util

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.widget.WidgetConstants
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

import com.weatherwidget.data.model.DailyExtreme

@Category(ShortDuration::class)
class TripleLinePrecisionTest {

    private val today = LocalDate.now()
    private val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun extreme(date: LocalDate, high: Float, low: Float) = DailyExtreme(
        date = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        source = WeatherSource.OPEN_METEO.id,
        locationLat = 0.0,
        locationLon = 0.0,
        highTemp = high,
        lowTemp = low,
        condition = "Clear",
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun `Today triple line uses precise forecast high from DB snapshot`() {
        val now = LocalDateTime.now().withHour(12).withMinute(0)
        val displaySource = WeatherSource.OPEN_METEO
        
        // 1. Setup a "Snapshot" from the DB for Today with decimal precision
        // This simulates our new change where snapshots are no longer rounded for Today.
        val forecastFromSnapshot = ForecastEntity(
            targetDate = dateEpoch(todayStr),
            forecastDate = dateEpoch(todayStr),
            locationLat = 0.0,
            locationLon = 0.0,
            highTemp = 72.4f, // Precise decimal
            lowTemp = 50.6f,  // Precise decimal
            condition = "Clear",
            source = displaySource.id,
            fetchedAt = 1L
        )

        // 2. Setup hourly data (Used for the 'Observed' parts of the triple line)
        val hourlyData = listOf(
            HourlyForecastEntity(TestData.toEpoch("${todayStr}T10:00"), 0.0, 0.0, 70.0f, "Clear", displaySource.id, fetchedAt = 1000L),
            HourlyForecastEntity(TestData.toEpoch("${todayStr}T12:00"), 0.0, 0.0, 71.5f, "Clear", displaySource.id, fetchedAt = 1000L),
            HourlyForecastEntity(TestData.toEpoch("${todayStr}T14:00"), 0.0, 0.0, 73.1f, "Clear", displaySource.id, fetchedAt = 1000L) // Peak in hourly
        )

        // 3. Calculate triple line values
        val result = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = hourlyData,
            today = today,
            now = now,
            displaySource = displaySource,
            fallbackWeather = forecastFromSnapshot,
            dailyActuals = mapOf(
                today to extreme(today, 71.5f, 70.0f)
            ),
        )

        // VERIFICATION
        
        // Forecast line (the blue line in the graph) should match the snapshot EXACTLY
        assertEquals("Forecast high should retain decimal precision from snapshot", 
            72.4f, result.dashedLineHigh!!, 0.001f)
        assertEquals("Forecast low should retain decimal precision from snapshot", 
            50.6f, result.dashedLineLow!!, 0.001f)

        // Observed values come from source-specific actuals, not forecast-hourly peaks.
        assertEquals("Observed high should retain hourly precision", 
            71.5f, result.solidLineHigh!!, 0.001f)
            
        // Observed low (so far at 12pm, the lowest is 70.0)
        assertEquals("Observed low should retain hourly precision", 
            70.0f, result.solidLineLow!!, 0.001f)
    }
}
