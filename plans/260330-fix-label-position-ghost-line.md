# Fix: Temperature Label Text/Position Mismatch on Hourly Graph

## Context
On the hourly temperature graph, a label showing "78.6°" appears **below** a label showing "77°". The delta-adjusted ghost line (which shifts forecast temps to align with observed actuals) is influencing label Y-positions, but labels should only be positioned based on the temperature value they display — never the ghost line.

## Root Cause
In `TemperatureGraphRenderer.kt`, label Y-positions come from curve point lookups (`originalPoints` or `forecastPoints`), but `originalPoints` includes a delta correction for forecast hours (line 424). When a label's Y-position is pulled from `originalPoints`, it reflects the delta-adjusted temperature, while the label *text* (line 570) shows the non-adjusted value. This creates a visual mismatch.

## Fix
**File**: `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

**In `placeTemperatureLabels` (~lines 623-627)**: Compute label Y-position directly from the label's temperature value and the Y-axis scaling, instead of looking it up from `originalPoints`/`forecastPoints`. This completely decouples label positioning from the ghost line.

**Current code:**
```kotlin
val isFuture = candidate.forceForecastSeries || ctx.originalPoints[idx].first > (ctx.transitionX ?: -1f)
val points = if (isFuture) ctx.forecastPoints else ctx.originalPoints
val (sx, sy) = if (candidate.role == "LOW" || candidate.role == "HIGH" || candidate.role == "FORECAST_HIGH") {
    centerOfRun(idx, temps, candidate.forceForecastSeries, ctx.originalPoints, ctx.forecastPoints, ctx.transitionX)
} else points[idx].first to points[idx].second
```

**New code:**
```kotlin
val isFuture = candidate.forceForecastSeries || ctx.originalPoints[idx].first > (ctx.transitionX ?: -1f)
val points = if (isFuture) ctx.forecastPoints else ctx.originalPoints
val sx = if (candidate.role == "LOW" || candidate.role == "HIGH" || candidate.role == "FORECAST_HIGH") {
    centerOfRun(idx, temps, candidate.forceForecastSeries, ctx.originalPoints, ctx.forecastPoints, ctx.transitionX).first
} else points[idx].first
val sy = ctx.graphTop + ctx.graphHeight * (1 - (temps[idx] - ctx.minTemp) / ctx.tempRange)
```

Key changes:
- **X position**: unchanged — still from curve points / `centerOfRun` (X is identical across all point sets)
- **Y position**: computed directly from `temps[idx]` (the label's display temperature) using the Y-axis scaling formula, guaranteeing the label sits at the correct height for the value it shows

No changes needed to `labelTemps` (line 570), `forecastLabelTemps` (line 571), `centerOfRun`, or `computeScaling`.

## Verification
1. `./gradlew installDebug`
2. Place a 2x3+ widget on the emulator
3. Verify labels are vertically consistent with their displayed values (higher temps = higher on graph)
4. `./gradlew testDebugUnitTest`
