package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class CurrentTempUnificationIntegrationTest {

    @Test
    fun `Daily View and Temperature View resolve exact same current temperature`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appWidgetId = 100
        val now = LocalDateTime.of(2026, 6, 8, 12, 15) // 12:15 PM
        val zoneId = ZoneId.systemDefault()
        
        // Setup large 32-hour window (Daily View base data)
        val largeWindow = mutableListOf<HourlyForecastEntity>()
        for (i in -8..24) {
            val dt = now.plusHours(i.toLong()).withMinute(0).withSecond(0).withNano(0)
            largeWindow.add(
                HourlyForecastEntity(
                    dateTime = dt.atZone(zoneId).toInstant().toEpochMilli(),
                    source = WeatherSource.OPEN_METEO.id,
                    locationLat = 0.0,
                    locationLon = 0.0,
                    temperature = 70f + (i * 0.5f), // Gradual curve
                    condition = "Sunny",
                    fetchedAt = System.currentTimeMillis()
                )
            )
        }

        // Setup narrow 3-hour window (Current Temp Resolution data)
        val narrowWindow = largeWindow.filter { 
            val time = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.dateTime), zoneId)
            time == now.withMinute(0).withSecond(0).withNano(0) || 
            time == now.plusHours(1).withMinute(0).withSecond(0).withNano(0) ||
            time == now.plusHours(2).withMinute(0).withSecond(0).withNano(0)
        }

        val stateManager = mockk<WidgetStateManager>(relaxed = true)
        every { stateManager.getCurrentTempDeltaState(appWidgetId, any()) } returns null
        every { stateManager.getZoomLevel(appWidgetId) } returns com.weatherwidget.widget.ZoomLevel.WIDE
        every { stateManager.getHourlyOffset(appWidgetId) } returns 0
        every { stateManager.getSingleDayDate(any()) } returns null

        val appLogDao = mockk<AppLogDao>(relaxed = true)
        
        // 1. Resolve Temperature View
        val tempViewResult = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = largeWindow,
            currentTempHourlyForecasts = narrowWindow,
            centerTime = now,
            displaySource = WeatherSource.OPEN_METEO,
            precipProbability = null,
            lastObservedTemp = null,
            observedAt = null,
            dimensions = WidgetDimensions(cols = 4, rows = 2, widthDp = 300, heightDp = 200, isIconWidth = false),
            stateManager = stateManager,
            repository = null,
            deferCurrentTempResolution = false,
            appLogDao = appLogDao,
            now = now,
        )

        // 2. Resolve Daily View
        // Simulate WidgetIntentRouter logic
        val smoothedForecasts = computeSmoothedForecasts(narrowWindow, WeatherSource.OPEN_METEO)
        val (dailyViewResolution, _) = CurrentTempResolutionHelper.resolveAndPersistDelta(
            now = now,
            displaySource = WeatherSource.OPEN_METEO,
            hourlyForecasts = narrowWindow, // The fix: Daily view gets the narrow window here
            lastObservedTemp = null,
            observedAt = null,
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            lat = 0.0,
            lon = 0.0,
            smoothedForecasts = smoothedForecasts
        )

        // Ensure both view paths yield identically interpolated display temperatures
        assertEquals(
            "Temperature View and Daily View must resolve the exact same current temperature",
            tempViewResult.currentTempResolution.displayTemp,
            dailyViewResolution.displayTemp
        )
    }

    @Test
    fun `Daily View and Temperature View resolve exact same current temperature when using repository path`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appWidgetId = 101
        val now = LocalDateTime.of(2026, 6, 8, 12, 15) // 12:15 PM
        val zoneId = ZoneId.systemDefault()
        val nowMs = now.atZone(zoneId).toInstant().toEpochMilli()

        // Setup large 32-hour window
        val largeWindow = mutableListOf<HourlyForecastEntity>()
        for (i in -8..24) {
            val dt = now.plusHours(i.toLong()).withMinute(0).withSecond(0).withNano(0)
            largeWindow.add(
                HourlyForecastEntity(
                    dateTime = dt.atZone(zoneId).toInstant().toEpochMilli(),
                    source = WeatherSource.OPEN_METEO.id,
                    locationLat = 0.0,
                    locationLon = 0.0,
                    temperature = 75f - (i * 0.3f), // Cooling curve
                    condition = "Cloudy",
                    fetchedAt = System.currentTimeMillis()
                )
            )
        }

        val narrowWindow = largeWindow.filter {
            val time = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.dateTime), zoneId)
            time == now.withMinute(0).withSecond(0).withNano(0) ||
            time == now.plusHours(1).withMinute(0).withSecond(0).withNano(0) ||
            time == now.plusHours(2).withMinute(0).withSecond(0).withNano(0)
        }

        // Setup observations for GraphStyle resolution
        val observations = listOf(
            com.weatherwidget.data.local.ObservationEntity(
                stationId = "STATION1",
                stationName = "Station 1",
                timestamp = nowMs - 600_000, // 10 min ago
                locationLat = 0.0,
                locationLon = 0.0,
                temperature = 74.2f,
                condition = "Cloudy",
                api = WeatherSource.OPEN_METEO.id,
                fetchedAt = System.currentTimeMillis()
            )
        )

        val stateManager = mockk<WidgetStateManager>(relaxed = true)
        every { stateManager.getCurrentTempDeltaState(appWidgetId, any()) } returns null
        every { stateManager.getZoomLevel(appWidgetId) } returns com.weatherwidget.widget.ZoomLevel.WIDE
        every { stateManager.getSingleDayDate(any()) } returns null
        
        val appLogDao = mockk<AppLogDao>(relaxed = true)

        // 1. Resolve GraphStyle Observation (simulating WidgetIntentRouter)
        val graphStyleObs = CurrentTempResolver.resolveGraphStyleCurrentTempFromInputs(
            observations = observations,
            hourlyForecasts = narrowWindow,
            displaySource = WeatherSource.OPEN_METEO,
            lat = 0.0,
            lon = 0.0,
            now = now
        )

        // 2. Resolve Temperature View
        val tempViewResult = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = largeWindow,
            currentTempHourlyForecasts = narrowWindow,
            centerTime = now,
            displaySource = WeatherSource.OPEN_METEO,
            precipProbability = null,
            lastObservedTemp = graphStyleObs?.temperature,
            observedAt = graphStyleObs?.observedAt,
            dimensions = WidgetDimensions(cols = 4, rows = 2, widthDp = 300, heightDp = 200, isIconWidth = false),
            stateManager = stateManager,
            repository = null,
            deferCurrentTempResolution = false,
            appLogDao = appLogDao,
            now = now,
        )

        // 3. Resolve Daily View
        val smoothedForecasts = computeSmoothedForecasts(narrowWindow, WeatherSource.OPEN_METEO)
        val (dailyViewResolution, _) = CurrentTempResolutionHelper.resolveAndPersistDelta(
            now = now,
            displaySource = WeatherSource.OPEN_METEO,
            hourlyForecasts = narrowWindow,
            lastObservedTemp = graphStyleObs?.temperature,
            observedAt = graphStyleObs?.observedAt,
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            lat = 0.0,
            lon = 0.0,
            smoothedForecasts = smoothedForecasts
        )

        assertEquals(
            "Temperature View and Daily View must resolve the exact same current temperature with graph-style observations",
            tempViewResult.currentTempResolution.displayTemp,
            dailyViewResolution.displayTemp
        )
    }
}
