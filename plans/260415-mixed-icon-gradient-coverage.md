# Mixed Icon Gradient Coverage

**Date:** 2025-04-15
**Trigger:** Sunday and Tuesday daily bars showed grey gradient, but Monday was solid amber.
**Root cause:** `ic_weather_partly_cloudy_slight_chance_rain` was in `MIXED_ICONS` but missing from `cloudRatio()`, so `forecastBarGradient()` returned null silently.

## Problem

`WeatherConditionColors.cloudRatio()` is the lookup that decides the gradient transition point for a forecast bar. When an icon is in `MIXED_ICONS` (causing `isMixed=true` on the `DayData`) but absent from `cloudRatio()`, the renderer logs `gradient=true` but actually paints a solid-color bar because `forecastBarGradient()` returns null.

At the time of the report, 11 of 20 `MIXED_ICONS` entries were missing from `cloudRatio()`.

## Changes

### 1. `WeatherConditionColors.kt` — Add missing icons to `cloudRatio()`

New ratios (ordered low→high cloud cover):

| Icon | Ratio | Bottom Color |
|---|---|---|
| `ic_weather_horizon_sun` | 0.12 | CLOUDY |
| `ic_weather_clear_chance_rain` | 0.25 | RAINY |
| `ic_weather_clear_slight_chance_rain` | 0.22 | CLOUDY |
| `ic_weather_night_chance_rain` | 0.25 | RAINY |
| `ic_weather_night_slight_chance_rain` | 0.22 | CLOUDY |
| `ic_weather_fog_light` | 0.30 | CLOUDY |
| `ic_weather_fog_light_night` | 0.30 | CLOUDY |
| `ic_weather_cloudy_chance_rain` | 0.65 | RAINY |
| `ic_weather_cloudy_slight_chance_rain` | 0.60 | CLOUDY |
| `ic_weather_fog_night` | 0.50 | CLOUDY |
| `ic_weather_partly_cloudy_chance_rain_night` | 0.40 | RAINY |

### 2. `WeatherConditionColors.kt` — Expand bottom color logic in `forecastBarGradient()`

Before: only `ic_weather_partly_cloudy_chance_rain` got `FORECAST_RAINY` bottom.
After: all "chance_rain" icons (excluding "slight_chance") get `FORECAST_RAINY` bottom.

### 3. `WeatherIconMapper.kt` — Expose `MIXED_ICONS` for testing

Change `private val MIXED_ICONS` to `@VisibleForTesting internal val MIXED_ICONS` so the test can iterate the authoritative list.

### 4. New: `WeatherConditionColorsTest.kt` — Safety-net tests

Three plain JUnit tests (`@Category(ShortDuration::class)`):

1. **`every mixed icon has a cloud ratio`** — For each icon in `MIXED_ICONS`, asserts `cloudRatio()` returns non-null. Adding a new mixed icon without a cloud ratio fails the build.
2. **`cloud ratio values are in valid range`** — Asserts every returned ratio is in `[0.0, 1.0]`.
3. **`forecastBarGradient returns valid gradient for all cloud ratio icons`** — For each icon with a cloud ratio, calls `forecastBarGradient()` and asserts non-null `LinearGradient` with correct color stops.

## Files

- `app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt` — cloudRatio + gradient bottom color
- `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt` — expose MIXED_ICONS
- `app/src/test/java/com/weatherwidget/util/WeatherConditionColorsTest.kt` — new test file
