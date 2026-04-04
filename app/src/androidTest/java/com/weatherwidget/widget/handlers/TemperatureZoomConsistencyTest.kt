package com.weatherwidget.widget.handlers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.AndroidTestData
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Instrumented test to verify that the observation blending context remains consistent
 * across different zoom levels in the hourly temperature graph on the real Android runtime.
 * 
 * This verifies the fix for the issue where "latest observed temperature" varied between 
 * zoom levels due to inconsistent data window filtering.
 */
@RunWith(AndroidJUnit4::class)
class TemperatureZoomConsistencyTest : IsolatedIntegrationTest("zoom_consistency") {

    private val center = LocalDateTime.of(2026, 3, 24, 12, 0)
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

    @Before
    override fun setup() {
        super.setup()
    }

    private fun wideForecasts(): List<com.weatherwidget.data.local.HourlyForecastEntity> {
        val start = center.minusHours(24)
        val end = center.plusHours(48)
        val result = mutableListOf<com.weatherwidget.data.local.HourlyForecastEntity>()
        var cur = start
        while (!cur.isAfter(end)) {
            result.add(AndroidTestData.createHourly(dateTime = cur.format(fmt), temperature = 60f + cur.hour))
            cur = cur.plusHours(1)
        }
        return result
    }

    @Test
    fun buildHourDataList_isConsistentAcrossZoomLevels_onAndroidRuntime() = runBlocking {
        val forecasts: List<com.weatherwidget.data.local.HourlyForecastEntity> = wideForecasts()
        
        // S1 at T-4h (Outside NARROW 2h window, inside WIDE 8h window)
        // S2 at T-1h (Inside both)
        val tMinus4h = center.minusHours(4)
        val tMinus1h = center.minusHours(1)
        
        val actuals = listOf(
            AndroidTestData.createObservation(stationId = "S1", timestamp = tMinus4h.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 60f, distanceKm = 2f),
            AndroidTestData.createObservation(stationId = "S2", timestamp = tMinus1h.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 70f, distanceKm = 10f)
        )

        // 1. Wide zoom: 11:00 point should be a blend of S1 (extrapolated) and S2
        val wideHours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals
        )
        
        // 2. Narrow zoom: 11:00 point should be IDENTICAL to wide zoom 
        // because we removed the startMs filter in blendObservationSeries.
        val narrowHours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.NARROW,
            actuals = actuals
        )

        val widePointAt11 = wideHours.find { it.dateTime == tMinus1h }
        val narrowPointAt11 = narrowHours.find { it.dateTime == tMinus1h }

        assertNotNull("Wide result should have point at 11:00", widePointAt11)
        assertNotNull("Narrow result should have point at 11:00", narrowPointAt11)
        
        assertEquals("Temperature at 11:00 must be consistent across zoom levels on Android runtime", 
            widePointAt11!!.actualTemperature!!, 
            narrowPointAt11!!.actualTemperature!!, 
            0.01f
        )
        
        // Sanity check: verify it's a blend (not just 70.0)
        assertTrue("Temperature should be a blend (not exactly 70.0)", 
            Math.abs(narrowPointAt11.actualTemperature!! - 70.0f) > 0.1f
        )
    }

    @Test
    fun observationWindowConsistency_withFutureObservation() = runBlocking {
        val forecasts: List<com.weatherwidget.data.local.HourlyForecastEntity> = wideForecasts()
        
        val tMinus4h = center.minusHours(4)
        val tMinus1h = center.minusHours(1)
        val tPlus4h = center.plusHours(4)
        
        val baseActuals = listOf(
            AndroidTestData.createObservation(stationId = "S1", timestamp = tMinus4h.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 60f, distanceKm = 2f),
            AndroidTestData.createObservation(stationId = "S2", timestamp = tMinus1h.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 70f, distanceKm = 10f)
        )
        
        val disturber = AndroidTestData.createObservation(stationId = "S1", timestamp = tPlus4h.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 65f, distanceKm = 2f)
        val fullActuals = baseActuals + disturber

        // 1. Wide zoom: Sees the future observation (T+4h) because its query window is T+12h
        val wideHours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = fullActuals
        )
        
        // 2. Narrow zoom (CURRENT BUGGY BEHAVIOR): Excludes T+4h because its query window is T+2h
        // We simulate the bug by passing only baseActuals to NARROW zoom
        val narrowHoursBuggy = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.NARROW,
            actuals = baseActuals
        )

        val widePointAt11 = wideHours.find { it.dateTime == tMinus1h }
        val narrowPointAt11Buggy = narrowHoursBuggy.find { it.dateTime == tMinus1h }

        // The values SHOULD be different here if the bug exists (S1 at 11:00 is interpolated vs extrapolated)
        assertNotNull(widePointAt11)
        assertNotNull(narrowPointAt11Buggy)
        
        // This is where we REPRODUCE the inconsistency. 
        // Note: In a passing test suite, we'd expect them to be equal, 
        // but here we are documenting that different inputs lead to different results.
        val diff = Math.abs(widePointAt11!!.actualTemperature!! - narrowPointAt11Buggy!!.actualTemperature!!)
        assertTrue("BUG REPRODUCTION: Temperature at T-1h should differ when future context is missing (diff=$diff)", diff > 0.01f)

        // 3. Narrow zoom (FIXED BEHAVIOR): Should receive fullActuals despite visual window
        val narrowHoursFixed = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.NARROW,
            actuals = fullActuals
        )
        
        val narrowPointAt11Fixed = narrowHoursFixed.find { it.dateTime == tMinus1h }
        assertEquals("FIXED: Temperature at 11:00 must be consistent when same data context is provided", 
            widePointAt11.actualTemperature!!, 
            narrowPointAt11Fixed!!.actualTemperature!!, 
            0.01f
        )
    }

    @Test
    fun observationContextIsConsistentAcrossZoomLevels() = runBlocking {
        // Seed observations
        val tMinus4h = center.minusHours(4)
        val tMinus1h = center.minusHours(1)
        
        val obs1 = AndroidTestData.createObservation(stationId = "S1", timestamp = tMinus4h.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 60f, distanceKm = 2f)
        val obs2 = AndroidTestData.createObservation(stationId = "S2", timestamp = tMinus1h.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 70f, distanceKm = 10f)
        
        db.observationDao().insertAll(listOf(obs1, obs2))
        
        // Ensure we have some forecasts so the graph renders
        val forecasts = wideForecasts()
        db.hourlyForecastDao().insertAll(forecasts)

        val testWidgetId = 99995
        val stateManager = com.weatherwidget.widget.WidgetStateManager(context)
        stateManager.setViewMode(testWidgetId, com.weatherwidget.widget.ViewMode.TEMPERATURE)
        stateManager.setZoomLevel(testWidgetId, com.weatherwidget.widget.ZoomLevel.WIDE)

        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
        val repo = com.weatherwidget.data.repository.WeatherRepository(
            context,
            dagger.hilt.android.EntryPointAccessors.fromApplication(context, com.weatherwidget.di.RepositoryEntryPoint::class.java).forecastRepository(),
            dagger.hilt.android.EntryPointAccessors.fromApplication(context, com.weatherwidget.di.RepositoryEntryPoint::class.java).currentTempRepository(),
            db.forecastDao(),
            db.appLogDao(),
            dagger.hilt.android.EntryPointAccessors.fromApplication(context, com.weatherwidget.di.RepositoryEntryPoint::class.java).observationRepository()
        )

        // 1. Trigger update in WIDE zoom
        db.appLogDao().clearAllLogs()
        com.weatherwidget.widget.WidgetRenderer.updateWidgetWithData(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = testWidgetId,
            weatherList = emptyList(), // Not needed for header resolution in this test
            hourlyForecasts = forecasts,
            currentTemps = listOf(obs1, obs2),
            repository = repo
        )
        
        val wideLogs = db.appLogDao().getLogsByTag("TemperatureViewHandler", 100)
        val wideHeader = wideLogs.find { it.message.contains("headerState") }?.message
        assertNotNull("Should have WIDE header state log", wideHeader)
        val wideObserved = wideHeader!!.split("observedTemp=")[1].split(" ")[0]

        // 2. Trigger update in NARROW zoom
        db.appLogDao().clearAllLogs()
        stateManager.setZoomLevel(testWidgetId, com.weatherwidget.widget.ZoomLevel.NARROW)
        com.weatherwidget.widget.WidgetRenderer.updateWidgetWithData(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = testWidgetId,
            weatherList = emptyList(),
            hourlyForecasts = forecasts,
            currentTemps = listOf(obs1, obs2),
            repository = repo
        )
        
        val narrowLogs = db.appLogDao().getLogsByTag("TemperatureViewHandler", 100)
        val narrowHeader = narrowLogs.find { it.message.contains("headerState") }?.message
        assertNotNull("Should have NARROW header state log", narrowHeader)
        val narrowObserved = narrowHeader!!.split("observedTemp=")[1].split(" ")[0]
        
        assertEquals("Observed temperature in header must be identical across zoom levels", wideObserved, narrowObserved)
    }
}
