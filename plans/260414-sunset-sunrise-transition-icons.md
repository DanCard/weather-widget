# Sunset/Sunrise Transition Icons

## Problem

The hourly graph currently classifies each hour as either DAY or NIGHT using `SunPositionUtils.isNight()`. At sunset (e.g. 8:10 PM), the 8 PM hour bucket flips entirely to a moon icon, even though the first ~10 minutes of that hour were still daylight. The same happens at sunrise. This creates a jarring visual jump — clear sky goes from full sun to full moon in one hour, with no transitional state for the hour that straddles the boundary.

## Solution

Introduce a three-state `SunPhase` enum (DAY, TWILIGHT, NIGHT) with a `getSunPhase()` method in `SunPositionUtils`. The TWILIGHT state applies to any hour that contains the sunrise or sunset boundary. For clear-sky conditions during twilight, show a horizon-sun icon instead of abruptly switching between sun and moon.

## Implementation Steps

### 1. Add `SunPhase` enum and `getSunPhase()` to `SunPositionUtils.kt`

- Define `enum class SunPhase { DAY, TWILIGHT, NIGHT }` inside `SunPositionUtils.kt`
- Add `getSunPhase(dateTime, lat, lon)` that leverages the existing `getSunTimes()`:
  - If `sunsetHour == 24.0` (sun never sets): return DAY
  - If `sunriseHour == 0.0 && sunsetHour == 0.0` (sun never rises): return NIGHT
  - Compute `hourStart = dateTime.hour + dateTime.minute / 60.0`
  - Compute `hourEnd = hourStart + 1.0`
  - If `hourEnd <= sunriseHour || hourStart >= sunsetHour`: NIGHT (entirely dark)
  - If `hourStart >= sunriseHour && hourEnd <= sunsetHour`: DAY (entirely light)
  - Otherwise: TWILIGHT (hour spans a sunrise or sunset boundary)
- Keep `isNight()` as a convenience that delegates to `getSunPhase() == NIGHT`

### 2. Create new drawable: `ic_weather_horizon_sun.xml`

- A 24dp vector drawable showing a half-sun above a horizon line
- Sun disc and rays in `#FFD600` (matches existing clear sun icon color)
- Horizon line in `#90A4AE` (matches existing slate gray)
- Design: bottom half of the disc hidden behind the horizon, top half visible with a few short rays, similar style to existing `ic_weather_clear.xml` but cut at the midpoint
- Single icon for both sunrise and sunset (the icon tinting system differentiates warmth)

### 3. Add `isTwilight` field to data models

Files to update:
- `TemperatureGraphModels.kt` — `HourData`: add `val isTwilight: Boolean = false`
- `CloudCoverGraphRenderer.kt` — `CloudHourData`: add `val isTwilight: Boolean = false`
- `PrecipitationGraphRenderer.kt` — `PrecipHourData`: add `val isTwilight: Boolean = false`

Keep `isNight: Boolean` for backward compatibility. The `isTwilight` flag enables twilight-specific icon selection while `isNight` continues to drive existing night-color logic.

### 4. Update `WeatherIconMapper.kt` — Add twilight icon mapping

- Add `isTwilight: Boolean = false` parameter to `getIconResource()`
- For **clear/sunny conditions** when `isTwilight == true`, return `R.drawable.ic_weather_horizon_sun` instead of the full sun or moon icon
- For **all other conditions** (rain, snow, fog, cloudy, partly cloudy, etc.), fall through to existing day/night logic. Rationale: the jarring transition is most visible on clear skies; cloudy/rain icons already look similar day vs night
- Add `ic_weather_horizon_sun` to `isSunny()` set so twilight clear hours get sunny-tint color in the forecast line and icon tinting
- Do NOT add it to `PRECIPITATION_ICONS`, `MIXED_ICONS`, or `RAIN_INDICATOR_ICONS`

Implementation sketch in the `when` block:

```kotlin
// Before the final "clear/sunny" catch-all at the bottom:
normalizedCondition.contains("clear") || normalizedCondition.contains("sunny") || ... -> {
    if (isTwilight) R.drawable.ic_weather_horizon_sun
    else if (isNight) R.drawable.ic_weather_night
    else R.drawable.ic_weather_clear
}
```

### 5. Update `WeatherConditionColors.forecastColor()` — Add twilight color

Add `isTwilight: Boolean = false` parameter:
```kotlin
fun forecastColor(isSunny: Boolean, isRainy: Boolean, isMixed: Boolean, isNight: Boolean, isTwilight: Boolean = false): Int {
    return when {
        isRainy -> FORECAST_RAINY
        isNight -> FORECAST_NIGHT
        isTwilight && isSunny -> FORECAST_TWILIGHT  // warm amber
        isMixed -> FORECAST_SUNNY
        isSunny -> FORECAST_SUNNY
        else -> FORECAST_CLOUDY
    }
}
```

Add `val FORECAST_TWILIGHT = Color.parseColor("#FFA726")` — a warm orange-amber between sunny gold `#F4C542` and night silver `#BBBBBB`. This gives a 1-hour visual gradient transition on the forecast line at sunrise/sunset boundaries.

### 6. Update all hourly data builders to use `getSunPhase()`

#### `TemperatureHourDataBuilder.kt`
- Replace `val isNight = SunPositionUtils.isNight(currentHour, lat, lon)` with:
  ```kotlin
  val sunPhase = SunPositionUtils.getSunPhase(currentHour, lat, lon)
  val isNight = sunPhase == SunPhase.NIGHT
  val isTwilight = sunPhase == SunPhase.TWILIGHT
  ```
- Pass `isTwilight` to `WeatherIconMapper.getIconResource(condition, isNight, cloudCover, precipProbability, isTwilight)`
- Pass `isTwilight` to `HourData(..., isTwilight = isTwilight)`
- Same for the sub-hourly actuals section (line ~331)

#### `CloudCoverViewHandler.kt`
- Update both the current-conditions call (~line 181) and the hourly loop (~line 608) to use `getSunPhase()` and pass `isTwilight`

#### `PrecipViewHandler.kt`
- Update both the current-conditions call (~line 129) and the hourly loop (~line 609)

#### `DailyForecastIconResolver.kt`
- The daily view resolves a single icon for an entire day. Twilight is less impactful here (the icon represents the dominant condition). Leave `isNight()` as-is for daily icons. Only update if we later want today's icon to show twilight when the user is viewing near sunrise/sunset.

#### `TemperatureStateResolver.kt`
- Current-conditions resolver (~line 79). Could benefit from twilight for the "right now" icon. Update to use `getSunPhase()`.

### 7. Update icon tinting in renderers

All three renderers have similar tint blocks. Update each:

**`TemperatureGraphRenderer.kt` (~line 262):**
```kotlin
drawable.setTint(when {
    hour.isNight -> Color.parseColor("#BBBBBB")
    hour.isTwilight -> Color.parseColor("#FFA726")   // warm amber
    hour.isSunny -> Color.parseColor("#FFD60A")
    else -> Color.parseColor("#BBBBBB")
})
```

**`CloudCoverGraphRenderer.kt` (~line 264):** — same pattern

**`PrecipitationGraphRenderer.kt` (~line 288):** — same pattern

### 8. Update forecast line segment coloring

**`TemperatureGraphRenderer.kt` (~line 218):**
```kotlin
segmentPaint.color = WeatherConditionColors.forecastColor(
    hour.isSunny, hour.isRainy, hour.isMixed, hour.isNight, hour.isTwilight
)
```

**`TemperatureLabelResolver.kt` (~line 391):** — same pattern

### 9. Tests

#### `SunPositionUtilsTest.kt` — Add `getSunPhase()` tests
- Noon in SF summer → DAY
- Midnight in SF → NIGHT
- 5 AM in SF June (sunrise ~5:47) → TWILIGHT
- 8 PM in SF June (sunset ~20:35) → TWILIGHT
- 6 AM in SF June → DAY (fully after sunrise)
- 9 PM in SF June → NIGHT (fully after sunset)
- Polar day edge case (sunsetHour == 24.0) → DAY
- Polar night edge case (sunriseHour == 0.0, sunsetHour == 0.0) → NIGHT

#### `WeatherIconMapperTest.kt` — Add twilight tests
- "clear" + isTwilight=true → `R.drawable.ic_weather_horizon_sun`
- "clear" + isNight=true → `R.drawable.ic_weather_night` (unchanged)
- "clear" + isNight=false, isTwilight=false → `R.drawable.ic_weather_clear` (unchanged)
- "partly cloudy" + isTwilight=true → `R.drawable.ic_weather_partly_cloudy` (falls through, no twilight variant)
- "rain" + isTwilight=true → `R.drawable.ic_weather_rain` (falls through)
- Verify `isSunny(R.drawable.ic_weather_horizon_sun)` returns true

#### `WeatherConditionColorsTest` — Add twilight color test
- `forecastColor(isSunny=true, isRainy=false, isMixed=false, isNight=false, isTwilight=true)` → `FORECAST_TWILIGHT`
- Verify fallback ordering: rainy > night > twilight+sunny > mixed > sunny > cloudy

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Single vs separate sunrise/sunset icons | Single `ic_weather_horizon_sun.xml` | Icon tinting differentiates warmth; widget-scale makes subtle shape differences hard to see |
| Which conditions get twilight variants | Clear/sunny only (Option A) | The sun-to-moon jump is the most jarring visual; cloudy/rain icons already look similar day vs night. Can extend to partly cloudy later if desired |
| Twilight forecast line color | `#FFA726` warm amber | Creates a smooth 3-step gradient (gold → amber → silver) across sunset/sunrise boundaries |
| Daily view icon for twilight | Leave as-is (binary day/night) | Daily icons represent the whole day; twilight is only relevant for the specific boundary hour |
| `isNight` backward compat | Keep as `Boolean`, derive from `SunPhase` | Avoids a massive refactor; `isNight` is used in many places and means "use the night variant of this icon/color" |

## Files Changed (Summary)

| File | Change |
|------|--------|
| `SunPositionUtils.kt` | Add `SunPhase` enum, `getSunPhase()` method |
| `ic_weather_horizon_sun.xml` | New drawable (half sun above horizon) |
| `TemperatureGraphModels.kt` | Add `isTwilight` to `HourData` |
| `CloudCoverGraphRenderer.kt` | Add `isTwilight` to `CloudHourData`, update tinting |
| `PrecipitationGraphRenderer.kt` | Add `isTwilight` to `PrecipHourData`, update tinting |
| `WeatherIconMapper.kt` | Add `isTwilight` param, return horizon icon for clear twilight |
| `WeatherConditionColors.kt` | Add `isTwilight` param, `FORECAST_TWILIGHT` color |
| `TemperatureHourDataBuilder.kt` | Use `getSunPhase()`, pass `isTwilight` |
| `CloudCoverViewHandler.kt` | Use `getSunPhase()`, pass `isTwilight` |
| `PrecipViewHandler.kt` | Use `getSunPhase()`, pass `isTwilight` |
| `TemperatureStateResolver.kt` | Use `getSunPhase()`, pass `isTwilight` |
| `TemperatureGraphRenderer.kt` | Update icon tinting, forecast color to use `isTwilight` |
| `TemperatureLabelResolver.kt` | Update forecast color to use `isTwilight` |
| `SunPositionUtilsTest.kt` | Add `getSunPhase()` tests |
| `WeatherIconMapperTest.kt` | Add twilight icon tests |