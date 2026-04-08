# Gradient Forecast Bars for Mixed-Condition Days

## Context
Daily forecast bars currently use a single solid color per condition: gold for sunny, gray for mixed/cloudy, blue for rainy. Days like "Fog then Sunny" or "Partly Cloudy" get the same flat gray as "Mostly Cloudy", which feels too dreary for days that are mostly sunny. The user wants mixed-condition bars to show a **smooth vertical gradient** — mostly gold with a gray portion at the bottom, where the gray proportion reflects how cloudy the day actually is.

The bar's vertical axis maps naturally: bottom = low temp (morning) → top = high temp (afternoon). So "fog then sunny" → gray at bottom fading to gold at top feels intuitive.

## Gradient Design

| Icon / Condition | Cloud Ratio | Visual |
|-----------------|-------------|--------|
| `ic_weather_fog_sunny` | 0.15 | 85% gold top, 15% gray-fade bottom |
| `ic_weather_partly_cloudy` | 0.35 | 65% gold top, 35% gray-fade bottom |
| `ic_weather_partly_cloudy_night` | 0.35 | 65% gold top, 35% gray-fade bottom |
| `ic_weather_partly_cloudy_chance_rain` | 0.40 | 60% gold top, 40% blue-fade bottom |
| `ic_weather_mostly_cloudy` | 0.70 | 30% gold top, 70% gray-fade bottom |
| `ic_weather_mostly_cloudy_night` | 0.70 | 30% gold top, 70% gray-fade bottom |
| `ic_weather_fog_cloudy` | 0.70 | 30% gold top, 70% gray-fade bottom |
| Non-mixed (sunny/rainy/etc.) | N/A | Solid color, no gradient (unchanged) |

The gradient uses a 3-stop `LinearGradient`:
```
stops:    [0.0,  1.0 - cloudRatio,  1.0]
colors:   [gold, gold,              gray/blue]
```
This keeps the top portion solid gold and smoothly transitions to the secondary color in the bottom portion.

## Files to Modify

| File | Change |
|------|--------|
| `app/.../util/WeatherConditionColors.kt` | Add `cloudRatio(iconRes)` and `forecastGradient()` helper |
| `app/.../widget/DailyForecastGraphRenderer.kt` | Apply gradient shader to mixed-condition bars |

## Implementation Steps

### Step 1: Add gradient helpers to `WeatherConditionColors.kt`
**File:** `app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt`

Add:
```kotlin
/** Returns the "cloud ratio" (0.0 = fully clear, 1.0 = fully overcast) for mixed-condition icons. Returns null for non-mixed icons. */
fun cloudRatio(iconRes: Int): Float? {
    return when (iconRes) {
        R.drawable.ic_weather_fog_sunny -> 0.15f
        R.drawable.ic_weather_partly_cloudy,
        R.drawable.ic_weather_partly_cloudy_night -> 0.35f
        R.drawable.ic_weather_partly_cloudy_chance_rain -> 0.40f
        R.drawable.ic_weather_mostly_cloudy,
        R.drawable.ic_weather_mostly_cloudy_night,
        R.drawable.ic_weather_fog_cloudy -> 0.70f
        else -> null  // Not a mixed condition — use solid color
    }
}

/** Returns a LinearGradient shader for a mixed-condition bar, or null if solid color should be used. */
fun forecastBarGradient(iconRes: Int, topY: Float, bottomY: Float): LinearGradient? {
    val ratio = cloudRatio(iconRes) ?: return null
    val topColor = FORECAST_SUNNY
    val bottomColor = if (iconRes == R.drawable.ic_weather_partly_cloudy_chance_rain) FORECAST_RAINY else FORECAST_CLOUDY
    return LinearGradient(
        0f, topY, 0f, bottomY,
        intArrayOf(topColor, topColor, bottomColor),
        floatArrayOf(0f, (1f - ratio).coerceIn(0.01f, 0.99f), 1f),
        Shader.TileMode.CLAMP
    )
}
```

### Step 2: Apply gradient in `DailyForecastGraphRenderer.kt`
**File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`

**2a. Future forecast bars (line ~352-366):** After selecting the paint for non-past, non-gap-fallback bars:
- If `day.isMixed && day.iconRes != null`: set `paint.shader = WeatherConditionColors.forecastBarGradient(day.iconRes, hY, effectiveLowY)`
- After `canvas.drawLine(...)`: clear shader with `paint.shader = null`

**2b. Today's forecast bar (line ~440-441):** Same treatment for `todayForecastBluePaint`

**2c. Forecast overlay bars (line ~377-383):** Same treatment for `overlayPaint`

**Pattern for each bar draw site:**
```kotlin
// Before drawLine:
if (day.isMixed && day.iconRes != null) {
    paint.shader = WeatherConditionColors.forecastBarGradient(day.iconRes, topY, bottomY)
}
canvas.drawLine(x, topY, x, bottomY, paint)
// After drawLine:
paint.shader = null
```

## What NOT to change
- **Solid-color bars** (sunny, rainy, history, today-observed) — unchanged
- **Hourly forecast line** — already has per-segment coloring, no gradient needed
- **Labels, rain indicators, icons** — unchanged

## Verification
1. `./gradlew testDebugUnitTest` — existing tests pass (gradient is visual-only, no geometry changes)
2. `./gradlew installDebug` — build and install
3. Visual check on emulator: "Partly Cloudy" days should show gold-to-gray gradient; "Fog then Sunny" should be mostly gold with small gray bottom; "Mostly Cloudy" should be mostly gray with small gold top
4. Solid-color days (clear, rainy) should be unchanged
