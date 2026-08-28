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
        every { stateManager.getZoomStage(appWidgetId) } returns com.weatherwidget.widget.ZoomStage.WIDE
        every { stateManager.getZoomWindow(appWidgetId) } returns com.weatherwidget.widget.ZoomStage.WIDE.window()
        every { stateManager.getHourlyOffset(appWidgetId) } returns 0
        // The resolver centres its observation read here, so the test must say where the
        // widget is. A relaxed mock fabricates a Pair<Object, Object> for this and the
        // Double cast blows up; both paths below are at 0,0, so state it.
        every { stateManager.getWidgetLocation(appWidgetId) } returns (0.0 to 0.0)

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
        every { stateManager.getZoomStage(appWidgetId) } returns com.weatherwidget.widget.ZoomStage.WIDE
        every { stateManager.getZoomWindow(appWidgetId) } returns com.weatherwidget.widget.ZoomStage.WIDE.window()
        // The resolver centres its observation read here, so the test must say where the widget is.
        // A relaxed mock fabricates a Pair<Object, Object> for this and the Double cast blows up;
        // both paths below are at 0,0, so state it.
        every { stateManager.getWidgetLocation(appWidgetId) } returns (0.0 to 0.0)

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

    /**
     * The blend centre must be the location the user is configured at, not a coordinate carried by
     * whatever row happens to sit first in the hourly list.
     *
     * That centre is not merely drawn with: it is the middle of `ObservationSiteMerge`'s merge box,
     * so when the two disagree by more than that box the read returns none of the fresh rows. On
     * 2026-08-28 a 6 km move left the graph naming an 11:10 reading at 14:17 with the 13:35 one
     * sitting in the database. The rows below carry the site the device had LEFT, exactly as they
     * did then.
     */
    @Test
    fun `blend centre follows the configured location, not the coordinate on the rows`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appWidgetId = 345
        val now = LocalDateTime.of(2026, 8, 28, 14, 17)
        val zoneId = ZoneId.systemDefault()

        val configuredLat = 37.4064254
        val configuredLon = -122.0206146
        val abandonedLat = 37.417
        val abandonedLon = -122.089

        val rowsFromTheAbandonedSite = (-8..8).map { i ->
            val dt = now.plusHours(i.toLong()).withMinute(0).withSecond(0).withNano(0)
            HourlyForecastEntity(
                dateTime = dt.atZone(zoneId).toInstant().toEpochMilli(),
                source = WeatherSource.OPEN_METEO.id,
                locationLat = abandonedLat,
                locationLon = abandonedLon,
                temperature = 70f,
                condition = "Sunny",
                fetchedAt = System.currentTimeMillis(),
            )
        }

        val stateManager = mockk<WidgetStateManager>(relaxed = true)
        every { stateManager.getCurrentTempDeltaState(appWidgetId, any()) } returns null
        every { stateManager.getZoomStage(appWidgetId) } returns com.weatherwidget.widget.ZoomStage.WIDE
        every { stateManager.getZoomWindow(appWidgetId) } returns com.weatherwidget.widget.ZoomStage.WIDE.window()
        every { stateManager.getHourlyOffset(appWidgetId) } returns 0
        every { stateManager.getWidgetLocation(appWidgetId) } returns (configuredLat to configuredLon)

        val result = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = rowsFromTheAbandonedSite,
            currentTempHourlyForecasts = rowsFromTheAbandonedSite,
            centerTime = now,
            displaySource = WeatherSource.OPEN_METEO,
            precipProbability = null,
            lastObservedTemp = null,
            observedAt = null,
            dimensions = WidgetDimensions(cols = 4, rows = 2, widthDp = 300, heightDp = 200, isIconWidth = false),
            stateManager = stateManager,
            repository = null,
            deferCurrentTempResolution = false,
            appLogDao = mockk<AppLogDao>(relaxed = true),
            now = now,
        )

        assertEquals(
            "the blend must read where the user is, not where these rows were fetched",
            configuredLat,
            result.lat,
            0.0,
        )
        assertEquals(configuredLon, result.lon, 0.0)
    }

    /**
     * The fallback rung still works: an install with no configured location keeps deriving the
     * centre from its rows rather than degrading to no location at all.
     */
    @Test
    fun `blend centre falls back to the rows when no location is configured`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appWidgetId = 346
        val now = LocalDateTime.of(2026, 8, 28, 14, 17)
        val zoneId = ZoneId.systemDefault()

        val rows = (-8..8).map { i ->
            val dt = now.plusHours(i.toLong()).withMinute(0).withSecond(0).withNano(0)
            HourlyForecastEntity(
                dateTime = dt.atZone(zoneId).toInstant().toEpochMilli(),
                source = WeatherSource.OPEN_METEO.id,
                locationLat = 37.417,
                locationLon = -122.089,
                temperature = 70f,
                condition = "Sunny",
                fetchedAt = System.currentTimeMillis(),
            )
        }

        val stateManager = mockk<WidgetStateManager>(relaxed = true)
        every { stateManager.getCurrentTempDeltaState(appWidgetId, any()) } returns null
        every { stateManager.getZoomStage(appWidgetId) } returns com.weatherwidget.widget.ZoomStage.WIDE
        every { stateManager.getZoomWindow(appWidgetId) } returns com.weatherwidget.widget.ZoomStage.WIDE.window()
        every { stateManager.getHourlyOffset(appWidgetId) } returns 0
        every { stateManager.getWidgetLocation(appWidgetId) } returns null

        val result = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = rows,
            currentTempHourlyForecasts = rows,
            centerTime = now,
            displaySource = WeatherSource.OPEN_METEO,
            precipProbability = null,
            lastObservedTemp = null,
            observedAt = null,
            dimensions = WidgetDimensions(cols = 4, rows = 2, widthDp = 300, heightDp = 200, isIconWidth = false),
            stateManager = stateManager,
            repository = null,
            deferCurrentTempResolution = false,
            appLogDao = mockk<AppLogDao>(relaxed = true),
            now = now,
        )

        assertEquals(37.417, result.lat, 0.0)
        assertEquals(-122.089, result.lon, 0.0)
    }
}
