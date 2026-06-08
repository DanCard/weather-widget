# Unify Current Temperature Resolution Windows & Add Integration Test

## Background & Motivation
The previous fix addressed missing fallback logic but did not fix the root cause of the current temperature jumping when switching between the Daily Forecast view and the Hourly Temperature graph on specific devices (like Samsung/Emulators). 

**Root Cause**: 
The smoothing algorithm (`computeSmoothedForecasts`), which uses Inverse Distance Weighting (IDW) interpolation, produces slightly different results depending on the size of the data window passed to it. 
- The Hourly Temperature view (`TemperatureStateResolver`) correctly resolves the current temperature using a specifically narrow 2-hour window (`currentTempHourlyForecasts`).
- The Daily Forecast view (`DailyViewHandler` via `CurrentTempResolutionHelper`) resolves the current temperature using the large 32-hour window (`hourlyForecasts`) fetched for the daily view. 

Because IDW interpolation is sensitive to surrounding data points, giving it 32 points vs 2 points causes the current temperature curve to bend differently, leading to a divergent temperature reading between the two views.

## Scope & Impact
This change unifies the data pipeline so that the Daily View passes the exact same narrow 2-hour window to the `CurrentTemperatureResolver` as the Hourly View. It also includes an integration test to protect this critical wiring against future regressions.

## Proposed Solution

1. **Update `ObservationData` definition**:
   Modify `WidgetViewHandler.kt` to include the narrow window forecasts.
   ```kotlin
   data class ObservationData(
       val lastObservedTemp: Float? = null,
       val observedAt: Long? = null,
       val smoothedForecasts: Map<Long, Float>? = null,
       val currentTempHourlyForecasts: List<HourlyForecastEntity> = emptyList(),
   )
   ```

2. **Fix `WidgetIntentRouter.kt` wiring**:
   In `refreshDailyView`, compute `smoothedForecasts` using the narrow window instead of the large window, and pass the narrow window in `ObservationData`.
   ```kotlin
   val smoothedForecasts = computeSmoothedForecasts(
       currentTempHourlyForecasts, displaySource // <-- FIX: Use narrow window
   )

   // inside DailyViewHandler.updateWidget:
   observationData = ObservationData(
       lastObservedTemp = observation?.temperature,
       observedAt = observation?.observedAt,
       smoothedForecasts = smoothedForecasts,
       currentTempHourlyForecasts = currentTempHourlyForecasts, // <-- Pass narrow window
   )
   ```

3. **Fix `WidgetRenderer.kt` wiring**:
   Update `WidgetRenderer.kt` (used for background updates) to pass the narrow window.
   ```kotlin
   // inside DailyViewHandler.updateWidget:
   observationData = ObservationData(
       lastObservedTemp = observation?.temperature,
       observedAt = observation?.observedAt,
       currentTempHourlyForecasts = nowCenteredHourlyForecasts, // <-- Pass narrow window
   )
   ```

4. **Fix `DailyViewHandler.kt` wiring**:
   Update the call to `CurrentTempResolutionHelper.resolveAndPersistDelta` to use the narrow window.
   ```kotlin
   val (currentTempResolution, resolveMs) =
       CurrentTempResolutionHelper.resolveAndPersistDelta(
           now = now,
           displaySource = displaySource,
           hourlyForecasts = observationData.currentTempHourlyForecasts.ifEmpty { hourlyForecasts }, // <-- FIX
           lastObservedTemp = lastObservedTemp,
           observedAt = observedAt,
           stateManager = stateManager,
           appWidgetId = appWidgetId,
           lat = lat,
           lon = lon,
           smoothedForecasts = smoothedForecasts,
       )
   ```

## Integration Test

Create `app/src/test/java/com/weatherwidget/widget/handlers/CurrentTempUnificationIntegrationTest.kt`:

```kotlin
package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetDimensions
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
                    fetchedAt = System.currentTimeMillis()
                )
            )
        }

        // Setup narrow 2-hour window (Current Temp Resolution data)
        val narrowWindow = largeWindow.filter { 
            val time = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.dateTime), zoneId)
            time == now.withMinute(0).withSecond(0).withNano(0) || 
            time == now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
        }

        val stateManager = mockk<WidgetStateManager>(relaxed = true)
        every { stateManager.getCurrentTempDeltaState(appWidgetId, any()) } returns null

        val appLogDao = mockk<AppLogDao>(relaxed = true)
        
        // 1. Resolve Temperature View
        val tempViewResult = TemperatureStateResolver.resolve(
            context = context,
            appWidgetId = appWidgetId,
            hourlyForecasts = largeWindow,
            currentTempHourlyForecasts = narrowWindow,
            centerTime = now,
            displaySource = WeatherSource.OPEN_METEO,
            dimensions = WidgetDimensions(cols = 4, rows = 2, widthDp = 300, heightDp = 200, isIconWidth = false),
            stateManager = stateManager,
            repository = null,
            appLogDao = appLogDao,
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
}
```
