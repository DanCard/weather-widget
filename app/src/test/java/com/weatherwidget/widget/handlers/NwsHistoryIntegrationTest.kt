package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.widget.ObservationResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NwsHistoryIntegrationTest {

    private fun LocalDateTime.toMs() = this.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val today = LocalDate.now()
    private val yesterday = today.minusDays(1)
    private val yesterdayStr = yesterday.toString()

    @Test
    fun nws_history_bar_uses_source_specific_actuals() = runBlocking {
        val partialForecast = ForecastEntity(
            targetDate = dateEpoch(yesterdayStr),
            forecastDate = dateEpoch(yesterdayStr),
            highTemp = 77f,
            lowTemp = 50f,
            condition = "Sunny",
            source = WeatherSource.NWS.id,
            locationLat = 37.42,
            locationLon = -122.08
        )

        val dailyActuals = mapOf(
            yesterday to ObservationResolver.DailyActual(
                date = yesterday,
                highTemp = 78f,
                lowTemp = 52f,
                condition = "Sunny",
            )
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = LocalDateTime.now(),
            centerDate = today,
            today = today,
            weatherByDate = mapOf(yesterday to partialForecast),
            forecastSnapshots = mapOf(yesterday to listOf(partialForecast)),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals
        )

        val result = days.find { it.date == yesterday }
        assertNotNull("Yesterday should be present in the graph data", result)
        assertEquals("Should use source-specific actual high for primary bar high", 78f, result!!.solidLineHigh)
        assertEquals("Should use source-specific actual low for primary bar low", 52f, result.solidLineLow)
        assertEquals("Should still show the forecast high in the comparison overlay", 77f, result.dashedLineHigh)
        assertEquals("Should still show the forecast low in the comparison overlay", 50f, result.dashedLineLow)
    }

    @Test
    fun history_comparison_prefers_complete_snapshots_over_latest_partial() = runBlocking {
        val completeForecast = ForecastEntity(
            targetDate = dateEpoch(yesterdayStr),
            forecastDate = dateEpoch(yesterday.minusDays(1).toString()),
            highTemp = 75f,
            lowTemp = 50f,
            condition = "Cloudy",
            source = WeatherSource.NWS.id,
            locationLat = 37.42,
            locationLon = -122.08,
            fetchedAt = 1000L
        )

        val partialForecast = ForecastEntity(
            targetDate = dateEpoch(yesterdayStr),
            forecastDate = dateEpoch(yesterdayStr),
            highTemp = 77f,
            lowTemp = null,
            condition = "Sunny",
            source = WeatherSource.NWS.id,
            locationLat = 37.42,
            locationLon = -122.08,
            fetchedAt = 2000L
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = LocalDateTime.now(),
            centerDate = today,
            today = today,
            weatherByDate = mapOf(yesterday to partialForecast),
            forecastSnapshots = mapOf(yesterday to listOf(completeForecast, partialForecast)),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = emptyMap()
        )

        val result = days.find { it.date == yesterday }
        assertNotNull(result)
        assertEquals("Comparison should pick the snapshot with BOTH high and low", 75f, result!!.dashedLineHigh)
        assertEquals("Comparison should pick the snapshot with BOTH high and low", 50f, result.dashedLineLow)
    }

    @Test
    fun today_triple_line_uses_source_specific_actuals_only() = runBlocking {
        val todayStr = today.toString()
        val middayWeather = ForecastEntity(
            targetDate = dateEpoch(todayStr),
            forecastDate = dateEpoch(todayStr),
            highTemp = 66f,
            lowTemp = null,
            condition = "Sunny",
            source = WeatherSource.NWS.id,
            locationLat = 37.42,
            locationLon = -122.08
        )

        val hourly = listOf(
            HourlyForecastEntity(today.atTime(12, 0).toMs(), 37.42, -122.08, 62f, "Sunny", WeatherSource.NWS.id, fetchedAt = 1000L),
            HourlyForecastEntity(today.atTime(14, 0).toMs(), 37.42, -122.08, 66f, "Sunny", WeatherSource.NWS.id, fetchedAt = 1000L)
        )

        val dailyActuals = mapOf(
            today to ObservationResolver.DailyActual(
                date = today,
                highTemp = 52f,
                lowTemp = 52f,
                condition = "Clear",
            )
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = today.atTime(13, 0),
            centerDate = today,
            today = today,
            weatherByDate = mapOf(today to middayWeather),
            forecastSnapshots = mapOf(today to listOf(middayWeather)),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = hourly,
            dailyActuals = dailyActuals
        )

        val result = days.find { it.date == today }
        assertNotNull(result)
        assertEquals("Observed low should be 52", 52f, result!!.solidLineLow)
        assertEquals("Forecast low falls back to the selected provider's hourly forecast low", 62f, result.dashedLineLow)
    }
}