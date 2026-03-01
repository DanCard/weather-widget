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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class NwsHistoryIntegrationTest : IsolatedIntegrationTest("nws_history_integration") {

    private val today = LocalDate.now()
    private val yesterday = today.minusDays(1)
    private val yesterdayStr = yesterday.toString()

    @Test
    fun nws_history_bar_uses_observations_to_fill_midday_gaps() = runBlocking {
        // Scenario: Midday fetch for yesterday has a high but no low (dropped by NWS API)
        
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

        // 2. Setup raw station observations (The Actual Truth)
        val obs = listOf(
            ObservationEntity("KSJC", "San Jose", yesterday.atTime(6, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 52f, "Clear", 37.42, -122.08),
            ObservationEntity("KSJC", "San Jose", yesterday.atTime(14, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 78f, "Sunny", 37.42, -122.08)
        )

        // 3. Aggregate Actuals using the new shared logic
        val dailyActuals = ObservationResolver.aggregateObservationsToDaily(obs).associateBy { it.date }

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
        assertEquals("Should use MAX observation for primary bar high", 78f, result!!.high)
        assertEquals("Should use MIN observation for primary bar low", 52f, result.low)
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
    fun today_triple_line_uses_observations_to_fill_hourly_gaps() = runBlocking {
        // Scenario: NWS Hourly fetch at midday only has hours from 11am onwards.
        // The blue forecast line should still extend down to the morning low (from observations).
        
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
            HourlyForecastEntity(today.atTime(12, 0).formatISO(), 37.42, -122.08, 62f, "Sunny", WeatherSource.NWS.id, fetchedAt = 1000L),
            HourlyForecastEntity(today.atTime(14, 0).formatISO(), 37.42, -122.08, 66f, "Sunny", WeatherSource.NWS.id, fetchedAt = 1000L)
        )

        // But we have a morning observation!
        val obs = listOf(
            ObservationEntity("KSJC", "San Jose", today.atTime(6, 0).toEpochMs(), 52f, "Clear", 37.42, -122.08)
        )
        val dailyActuals = ObservationResolver.aggregateObservationsToDaily(obs).associateBy { it.date }

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
        assertEquals("Forecast low (blue bar) should also reach down to 52", 52f, result.forecastLow)
    }

    private fun LocalDateTime.formatISO() = this.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
    private fun LocalDateTime.toEpochMs() = this.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
