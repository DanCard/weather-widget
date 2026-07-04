package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.testutil.TestData
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.util.DailyActualsEstimator
import com.weatherwidget.shared.graph.TemperatureExtrema
import com.weatherwidget.shared.graph.HourData
import com.weatherwidget.widget.ObservationResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

/**
 * Regression test for the temperature discrepancy observed on Samsung devices.
 * 
 * Scenario:
 * - Station A (Near, 1km): 73.1°F
 * - Station B (Far, 10km): 73.5°F
 * 
 * Discrepancy:
 * - OLD Daily View logic: Raw max of all stations today = 73.5°F.
 * - Hourly Graph logic: IDW-blended max (heavily weighted to near station) = 73.1°F.
 * 
 * Result: Widget displays two different high temperatures for the same day.
 * 
 * Fix: Unify Daily View to use the same IDW-blended series as the Hourly Graph.
 */
@Category(ShortDuration::class)
class TemperatureUnificationRegressionTest {

    @Test
    fun `reproduce samsung discrepancy - daily 73_5 vs hourly 73_1`() {
        val now = LocalDateTime.of(2026, 5, 16, 19, 0)
        val today = now.toLocalDate()
        val zone = ZoneId.systemDefault()
        
        // Scenario from Samsung device:
        // Current temp is 65.3
        // Station A (Near): 73.1
        // Station B (Far): 73.5
        val currentTemp = 65.3f
        
        val observations = listOf(
            TestData.observation(stationId = "KNEAR", temperature = 73.1f, distanceKm = 1f, timestamp = TestData.toEpoch("2026-05-16T15:00")),
            TestData.observation(stationId = "KFAR",  temperature = 73.5f, distanceKm = 10f, timestamp = TestData.toEpoch("2026-05-16T15:00"))
        )

        val forecasts = listOf(
            TestData.hourly(dateTime = "2026-05-16T14:00", temperature = 70f),
            TestData.hourly(dateTime = "2026-05-16T15:00", temperature = 72f),
            TestData.hourly(dateTime = "2026-05-16T16:00", temperature = 71f)
        )

        // Calculate the Blended Series (Source of Truth for both views now)
        val blendedResult = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = observations.map { it.toReading() },
            hourlyForecasts = forecasts.map { it.toHourlyForecast() },
            displaySourceId = WeatherSource.NWS.id,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            startMs = today.atStartOfDay(zone).toInstant().toEpochMilli(),
            endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            onBlendDebug = null,
        )

        // --- 1. Calculate Daily View High (The way it is AFTER fix) ---
        // We now use the blended result as the source of truth for the Daily Actual
        val unifiedHigh = blendedResult.observations.maxOf { it.temperature }
        val dailyActuals = mapOf(
            today to DailyHistory(
                date = today.toEpochDay() * 86_400_000L,
                source = WeatherSource.NWS.id,
                locationLat = TestData.LAT,
                locationLon = TestData.LON,
                highTemp = unifiedHigh,
                lowTemp = 50f,
                condition = "Clear",
                updatedAt = System.currentTimeMillis()
            )
        )
        
        val tripleLine = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts = forecasts,
            today = today,
            now = now,
            displaySource = WeatherSource.NWS,
            fallbackWeather = null,
            dailyActuals = dailyActuals,
            currentTemp = currentTemp
        )
        val dailyViewDisplayedHigh = listOfNotNull(tripleLine.solidLineHigh, tripleLine.dashedLineHigh, tripleLine.ghostLineHigh).maxOrNull() ?: -1f

        // --- 2. Calculate Hourly Graph Peak ---
        // (already computed unifiedHigh above as 73.10396)
        val hourData = blendedResult.observations.map { obs ->
            HourData(
                dateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(obs.timestamp), zone),
                temperature = 72f, 
                label = "3p",
                actualTemperature = obs.temperature,
                isActual = true,
                iconRes = 0
            )
        }
        val extrema = TemperatureExtrema.compute(hourData, 100f, hourData.lastIndex, now, 2.5f)
        val hourlyGraphPeak = if (extrema.actualHighIndex >= 0) extrema.actualLabelTemps[extrema.actualHighIndex] else -1f

        println("Daily View High: $dailyViewDisplayedHigh")
        println("Hourly Graph Peak: $hourlyGraphPeak")

        // Discrepancy Check:
        // After fix: dailyViewDisplayedHigh should be 73.1 while hourlyGraphPeak is also 73.1.
        assertEquals("Daily high must match Hourly Graph peak", hourlyGraphPeak, dailyViewDisplayedHigh, 0.01f)
        assertEquals("Both must reflect the IDW-blended value (~73.1)", 73.1f, dailyViewDisplayedHigh, 0.05f)
    }
}
