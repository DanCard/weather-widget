package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.local.*
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.widget.handlers.DailyViewLogic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class NwsHistoryIntegrationTest : IsolatedIntegrationTest("nws_history_integration") {

    private fun LocalDateTime.toMs() = this.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val today = LocalDate.now()
    private val yesterday = today.minusDays(1)
    private val yesterdayStr = yesterday.toString()

    @Test
    fun nws_history_bar_uses_source_specific_actuals() = runBlocking {
        // Scenario: source-specific actuals exist for yesterday.
        
        // 1. Setup partial NWS forecast snapshot
        val partialForecast = ForecastEntity(
            targetDate = yesterdayStr,
            forecastDate = yesterdayStr,
            highTemp = 77f,
            lowTemp = null, // Missing low from midday fetch
            condition = "Sunny",
            source = WeatherSource.NWS.id,
            locationLat = 37.42,
            locationLon = -122.08
        )

        val dailyActuals = mapOf(
            yesterdayStr to ObservationResolver.DailyActual(
                date = yesterdayStr,
                highTemp = 78f,
                lowTemp = 52f,
                condition = "Sunny",
            )
        )

        // 4. Run Logic to prepare graph days
        val days = DailyViewLogic.prepareGraphDays(
            now = LocalDateTime.now(),
            centerDate = today,
            today = today,
            weatherByDate = mapOf(yesterdayStr to partialForecast),
            forecastSnapshots = mapOf(yesterdayStr to listOf(partialForecast)),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals
        )

        val result = days.find { it.date == yesterdayStr }
        assertNotNull("Yesterday should be present in the graph data", result)
        assertEquals("Should use source-specific actual high for primary bar high", 78f, result!!.high)
        assertEquals("Should use source-specific actual low for primary bar low", 52f, result.low)
        assertEquals("Should still show the forecast high in the comparison overlay", 77f, result.forecastHigh)
        assertNull("Forecast low should remain null as provided in the snapshot", result.forecastLow)
    }

    @Test
    fun history_comparison_prefers_complete_snapshots_over_latest_partial() = runBlocking {
        // Scenario: We have an older complete forecast and a newer partial forecast.
        // We should use the complete one for the blue forecast overlay.
        
        val completeForecast = ForecastEntity(
            targetDate = yesterdayStr,
            forecastDate = yesterday.minusDays(1).toString(),
            highTemp = 75f,
            lowTemp = 50f,
            condition = "Cloudy",
            source = WeatherSource.NWS.id,
            locationLat = 37.42,
            locationLon = -122.08,
            fetchedAt = 1000L // Older
        )
        
        val partialForecast = ForecastEntity(
            targetDate = yesterdayStr,
            forecastDate = yesterdayStr,
            highTemp = 77f,
            lowTemp = null,
            condition = "Sunny",
            source = WeatherSource.NWS.id,
            locationLat = 37.42,
            locationLon = -122.08,
            fetchedAt = 2000L // Newer
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = LocalDateTime.now(),
            centerDate = today,
            today = today,
            weatherByDate = mapOf(yesterdayStr to partialForecast),
            forecastSnapshots = mapOf(yesterdayStr to listOf(completeForecast, partialForecast)),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = emptyMap()
        )

        val result = days.find { it.date == yesterdayStr }
        assertNotNull(result)
        assertEquals("Comparison should pick the snapshot with BOTH high and low", 75f, result!!.forecastHigh)
        assertEquals("Comparison should pick the snapshot with BOTH high and low", 50f, result.forecastLow)
    }

    @Test
    fun today_triple_line_uses_source_specific_actuals_only() = runBlocking {
        
        val todayStr = today.toString()
        val middayWeather = ForecastEntity(
            targetDate = todayStr,
            forecastDate = todayStr,
            highTemp = 66f,
            lowTemp = null, // Missing!
            condition = "Sunny",
            source = WeatherSource.NWS.id,
            locationLat = 37.42,
            locationLon = -122.08
        )

        // Only afternoon hours
        val hourly = listOf(
            HourlyForecastEntity(today.atTime(12, 0).toMs(), 37.42, -122.08, 62f, "Sunny", WeatherSource.NWS.id, fetchedAt = 1000L),
            HourlyForecastEntity(today.atTime(14, 0).toMs(), 37.42, -122.08, 66f, "Sunny", WeatherSource.NWS.id, fetchedAt = 1000L)
        )

        val dailyActuals = mapOf(
            todayStr to ObservationResolver.DailyActual(
                date = todayStr,
                highTemp = 52f,
                lowTemp = 52f,
                condition = "Clear",
            )
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = today.atTime(13, 0),
            centerDate = today,
            today = today,
            weatherByDate = mapOf(todayStr to middayWeather),
            forecastSnapshots = mapOf(todayStr to listOf(middayWeather)),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = hourly,
            dailyActuals = dailyActuals
        )

        val result = days.find { it.date == todayStr }
        assertNotNull(result)
        assertEquals("Observed low should be 52", 52f, result!!.low)
        assertEquals("Forecast low falls back to the selected provider's hourly forecast low", 62f, result.forecastLow)
    }

    private fun LocalDateTime.formatISO() = this.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
}
