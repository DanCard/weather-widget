# ForecastRepository Refactor Plan

Date: 2026-04-16
Trigger: Code review of ForecastRepository.kt (1113 lines, God class)

## Problem: Per-API Duplicate Types

Each API defines its own `HourlyForecast` and `DailyForecast` data classes despite
having nearly identical fields. This forces ForecastRepository to have 7 mapping
methods and 7 hourly-save methods that do the same thing with different types.

### Current State

Each API has its own nested types:
- `OpenMeteoApi.HourlyForecast` - uses `weatherCode: Int`
- `OpenWeatherMapApi.HourlyForecast` - uses `condition: String`
- `VisualCrossingApi.HourlyForecast` - uses `condition: String`
- `WeatherApi.HourlyForecast` - uses `condition: String`
- `SilurianApi.HourlyForecast` - uses `condition: String`, `precipProbability: Int` (non-nullable)
- `TomorrowIoApi.HourlyForecast` - uses `weatherCode: Int`
- `NwsApi.HourlyForecastPeriod` - uses `shortForecast`, has `localDate`/`localHour`, `startTime` instead of `dateTime`

Same pattern for daily types.

### Design Decision: Condition vs WeatherCode

Should every API use a numeric weather code instead of a condition string?

Arguments for (condition code):
- WMO codes are standardized and language-independent
- Conversion to display text happens once, at the UI layer
- Avoids storing free-text conditions that vary by API ("Partly Cloudy" vs "P Cloudy")
- OpenMeteo and TomorrowIo already use numeric codes

Arguments against:
- NWS, OWM, VC, WeatherApi, Silurian all provide text conditions from the API
- We'd be inventing our own mapping from their text to WMO codes (lossy)
- The text conditions from these APIs are often more specific than WMO codes
- More work to maintain the text-to-code mapping tables

Recommendation: Keep what the API gives us. Store `condition: String` in the shared
type. For APIs that give weather codes (OpenMeteo, TomorrowIo), convert to text
in the API parse layer (as they already do via `weatherCodeToCondition()`). This is
the current behavior and avoids lossy conversions.

### Plan

1. Define shared types in `data/model/`:
   - `HourlyForecast` (common fields: dateTime, temperature, condition, precipProbability, cloudCover, precipAmountMm)
   - `DailyForecast` (common fields: date, highTemp, lowTemp, condition, iconToken, precipProbability, precipAmountMm)
   - `ForecastResult` (common wrapper: currentTemp, currentCondition, currentObservedAt, daily, hourly)

2. Change each API to return the shared types instead of per-API types.
   The conversion (weatherCode -> condition, inches -> mm, etc.) stays in the API class.

3. ForecastRepository can then use a single save method and single daily mapping.

## Other Fixes (Independent)

These were identified in code review and can be applied separately:

- #2: Remove dead `isPlugged()` method (never called)
- #3: `locationName = ""` in `saveForecastSnapshot` and `fetchClimateNormalsGap` discards location data
- #5: `entities.first()` in `saveHourlyEntities` is fragile
- #4: 7 boolean params in `fetchFromAllApis` -> Set<WeatherSource>
- #8: Duplicate catch blocks -> `safeFetch` helper
- #9/#10: 10-param `applyForecastPeriods` and 9-param `logTodayDiagnostics` -> `NwsDayAccumulator`
- #11: `enabledSources.map { it.id }` -> `.any`
- #12: Hardcoded cache range days -> named constants
- #13: Redundant `getWeatherRange` delegate
- Observation retention: increased from 4 days to 6 days

## Deferred

- #1: Extract NWS logic to separate NwsForecastMapper class. Requires updating
  11 test files' ForecastRepository constructors. Do after shared API types are
  in place, which will simplify the constructor anyway.
