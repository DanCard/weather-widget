# Session: Sunset/Sunrise Transition Icons with Golden-Hour Coloring

**Date:** 2026-04-14
**Commit:** 47413fa

## Problem

On the temperature graph view, 8 PM showed a full moon icon despite being half daylight. The binary DAY/NIGHT classification using `SunPositionUtils.isNight()` flipped abruptly at sunset, with no transitional state for the hour that straddles the boundary.

## Initial Design Discussion

User asked about a half-sun/half-moon icon for boundary hours. Suggested a `SunPhase` enum (DAY/TWILIGHT/NIGHT) with a horizon-sun icon for transition hours. User approved the approach and asked for a plan.

## Plan Written

Created `plans/260414-sunset-sunrise-transition-icons.md` with detailed implementation steps covering SunPhase enum, horizon-sun drawable, data model changes, icon mapper updates, renderer tinting, and tests.

## Implementation — Phase 1: Basic TWILIGHT state

1. **SunPositionUtils.kt**: Added `SunPhase` enum and `getSunPhase()`. Initially used official sunrise/sunset boundaries (zenith 93.33°). An hour was TWILIGHT if it contained the sunrise or sunset boundary.

2. **ic_weather_horizon_sun.xml**: Created half-sun above horizon drawable using gold (#FFD600) for sun and slate (#90A4AE) for horizon line.

3. **Data models**: Added `isTwilight: Boolean` to `HourData`, `CloudHourData`, `PrecipHourData`.

4. **WeatherIconMapper.kt**: Added `isTwilight` param. Clear/sunny conditions during twilight returned `ic_weather_horizon_sun`. Also added twilight handling for "mostly clear", "mostly sunny", "overcast" conditions.

5. **WeatherConditionColors.kt**: Added `FORECAST_TWILIGHT = #FFA726` (warm amber) and `isTwilight` param to `forecastColor()`.

6. **Data builders**: Updated `TemperatureHourDataBuilder`, `CloudCoverViewHandler`, `PrecipViewHandler`, `TemperatureStateResolver` to compute `SunPhase` and pass `isTwilight`.

7. **Renderers**: Updated `TemperatureGraphRenderer`, `CloudCoverGraphRenderer`, `PrecipitationGraphRenderer`, `TemperatureLabelResolver` — amber tint (#FFA726) for twilight icons, amber forecast line color.

8. **Tests**: Added SunPositionUtilsTest for getSunPhase() and WeatherIconMapperTest for twilight icon selection.

## Bug: Icon matched but conditions missed

Initial build showed "Mostly Clear" at twilight getting `ic_weather_night` instead of `ic_weather_horizon_sun`. The `when` block in `WeatherIconMapper` only handled twilight in the "clear/sunny/fair" and fallback branches — missed "mostly clear/mostly sunny" and "overcast". Fixed by adding `isTwilight` checks to those branches too.

## Bug: 8 PM moon, 7 PM horizon icon (wrong boundary hour)

On Pixel, the emulator showed 7 PM as TWILIGHT with horizon icon but 8 PM as NIGHT with moon icon. The official zenith (93.33°) computed sunset at ~19:55, making the 7 PM hour contain the boundary. But visually, 8 PM still has daylight.

**Fix**: Switched `getSunPhase()` to use civil twilight zenith (96°) instead of official (93.33°). This pushes the sunset boundary ~30 min later, making 8 PM the twilight hour in April. Added `calculateSunTimesWithZenith()` helper and `zenith` parameter to `calculateSunriseSunset()`.

## Bug: 7 PM horizon icon when it should be normal sun with amber color

User clarified they wanted the amber color for golden-hour hours (pre-sunset, post-sunrise) but the horizon icon **only** for the actual boundary hour. The problem: `isTwilight` controlled both icon and color.

**Fix**: Separated concerns by adding `isSunBoundary()`:
- `isSunBoundary = true` → horizon-sun icon (only the hour containing the sunrise/sunset)
- `isTwilight = true` → warm amber color (golden hour: 1 hour before sunset, 1 hour after sunrise, plus boundary hours)

`getSunPhase()` was updated to include golden-hour detection: within 1 hour of civil sunrise/sunset → TWILIGHT. `isSunBoundary()` uses the civil twilight boundaries to find the exact transition hour.

Updated all data models to include `isSunBoundary`. Updated `WeatherIconMapper` to use `isSunBoundary` for icon selection and `isTwilight` only for color. Updated all callers to pass both flags.

## Bug: isSunBoundary used wrong zenith

`isSunBoundary()` initially used `getSunTimes()` (official zenith 93.33°), putting the boundary at 7:55 PM → 7 PM got the horizon icon. Should use the same civil twilight zenith (96°) as `getSunPhase()` so 8 PM gets the horizon icon. Fixed by calling `calculateSunTimesWithZenith(dateTime, lat, lon, 96.0)`.

## Files Changed Summary

| File | Change |
|------|--------|
| `SunPositionUtils.kt` | Added `SunPhase` enum, `getSunPhase()` with civil twilight + golden-hour logic, `isSunBoundary()` with civil twilight zenith, `calculateSunTimesWithZenith()` |
| `ic_weather_horizon_sun.xml` | New drawable: half-sun above horizon |
| `TemperatureGraphModels.kt` | Added `isTwilight`, `isSunBoundary` to `HourData` |
| `CloudCoverGraphRenderer.kt` | Added `isTwilight`, `isSunBoundary` to `CloudHourData`, amber tint |
| `PrecipitationGraphRenderer.kt` | Added `isTwilight`, `isSunBoundary` to `PrecipHourData`, amber tint |
| `WeatherIconMapper.kt` | Added `isTwilight` + `isSunBoundary` params; `isSunBoundary` → horizon icon, `isTwilight` → no icon change |
| `WeatherConditionColors.kt` | Added `FORECAST_TWILIGHT` color (#FFA726), `isTwilight` param |
| `TemperatureHourDataBuilder.kt` | Compute `SunPhase`, `isTwilight`, `isSunBoundary`; debug logging for twilight/boundary hours |
| `CloudCoverViewHandler.kt` | Same phase/boundary computation |
| `PrecipViewHandler.kt` | Same phase/boundary computation |
| `TemperatureStateResolver.kt` | Same phase/boundary computation |
| `TemperatureGraphRenderer.kt` | Amber tint for twilight, forecast color with isTwilight |
| `TemperatureLabelResolver.kt` | Forecast color with isTwilight |
| `SunPositionUtilsTest.kt` | Rewritten for golden-hour semantics, April MV test |
| `WeatherIconMapperTest.kt` | Added `isSunBoundary` tests for horizon icon, `isTwilight` tests for color-only |

## Key Design Decisions

1. **Single horizon-sun icon** for both sunrise and sunset — icon tinting differentiates warmth
2. **Two-tier twilight system**: `isTwilight` (color only, amber tint) vs `isSunBoundary` (icon change, horizon-sun)
3. **Civil twilight zenith (96°)** for both phase and boundary calculations — pushes boundary ~30 min later than official sunset, matching visual perception
4. **Golden hour = 1 hour before sunset + 1 hour after sunrise** — the TWILIGHT phase covers both the boundary hour and adjacent golden-hour hours
5. **Twilight color #FFA726** (warm amber) sits between sunny gold (#F4C542) and night silver (#BBBBBB)
6. **Only clear/sunny conditions get twilight icon treatment** — rain, fog, partly cloudy fall through to existing day/night icons