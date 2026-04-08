# Plan: Make forecast line always dashed

## Context
The hourly temperature forecast line (orange/grey colored curve) appears solid in the past and dashed in the future. The user wants it dashed everywhere.

**Root cause:** `buildPerSegmentPaths()` creates a separate `Path` per hour, each starting with `moveTo`. `DashPathEffect` resets its phase to 0 on each new path. When a segment's arc length is shorter than the 8dp dash length, it renders as one solid dash — appearing solid. Past segments tend to be flatter (shorter arc length), so they look solid. Future segments have steep curves (longer arc), so dashes and gaps become visible.

## Change 1: Fix the dash phase in `drawFillAndCurves()`

**File:** `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — `drawFillAndCurves()` (~lines 617-625)

Compute cumulative path length across segments and set the `DashPathEffect` phase for each segment so the dash pattern continues seamlessly:

```kotlin
val dashOn = dpToPx(ctx.context, 8f)
val dashOff = dpToPx(ctx.context, 4f)
val dashPattern = floatArrayOf(dashOn, dashOff)
val segmentPaint = Paint(paints.forecastDashedPaint)
var cumulativeLength = 0f

for (i in ctx.forecastSegmentPaths.indices) {
    val hour = hours[i.coerceAtMost(hours.lastIndex)]
    segmentPaint.color = WeatherConditionColors.forecastColor(
        hour.isSunny, hour.isRainy, hour.isMixed, hour.isNight
    )
    segmentPaint.pathEffect = DashPathEffect(dashPattern, cumulativeLength)
    ctx.canvas.drawPath(ctx.forecastSegmentPaths[i], segmentPaint)

    val measure = android.graphics.PathMeasure(ctx.forecastSegmentPaths[i], false)
    cumulativeLength += measure.length
}
```

## Change 2: Robolectric regression test

**File:** `app/src/test/java/com/weatherwidget/widget/TemperatureGraphDashContinuityTest.kt` (new)

A Robolectric test (following the pattern in `TemperatureGraphLabelPlacementRobolectricTest.kt`) that:
1. Builds segment paths via `GraphRenderUtils.buildPerSegmentPaths()` using known flat points (simulating past conditions)
2. Measures each segment with `PathMeasure`
3. Asserts cumulative lengths are positive and monotonically increasing — confirming the phase-offset input is valid
4. Optionally: renders to a real `Canvas` and captures the `Paint.pathEffect` to confirm it's a `DashPathEffect`

This avoids mockk entirely — uses real Android classes via Robolectric.

## Files to modify
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — `drawFillAndCurves()`
- `app/src/test/java/com/weatherwidget/widget/TemperatureGraphDashContinuityTest.kt` — new Robolectric test

## Verification
1. `./gradlew testDebugUnitTest` — ensure no test regressions
2. `./gradlew installDebug` — deploy to emulator
3. Screenshot the widget and confirm the forecast line is dashed uniformly across past and future
