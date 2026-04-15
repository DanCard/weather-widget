# Fix: Rain icon suppression falls back to sunny instead of cloudy

## Problem

When `shouldSuppressRainIcon()` in `DailyForecastIconResolver` suppresses a rain icon (precipitation probability below distance-based threshold), the fallback is `getCloudCoverIcon(isNight, null)` which **always returns `ic_weather_clear` (sunny)**. 

Real-world case: Sunday April 19, NWS forecasts "Slight Chance Light Rain" (20%). At 4 days out, day threshold = 25%, night threshold = 43%. Since 20 < 25 AND 20 < 43, both suppress → sunny icon shown. Should show cloudy/partly cloudy based on actual conditions.

## Root Cause

`DailyForecastIconResolver.kt:116,129`:
```kotlin
return WeatherIconMapper.getCloudCoverIcon(isNight, null)  // null → always sunny
```

## Solution

Use actual cloud cover from hourly forecast data instead of null. The hourly data already has `cloudCover` for all 7 API sources. Mirror the existing `calculateDayNightPrecipProbabilities()` pattern.

## Files to Modify

1. `app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt`
   - Add `calculateDaytimeCloudCover(hourlyForecasts, targetDate, now, lat, lon, source): Int?`
   - Add `daytimeCloudCover: Int? = null` param to `resolveIcon()`
   - Replace `getCloudCoverIcon(isNight, null)` with `getCloudCoverIcon(isNight, daytimeCloudCover)` at lines 116 and 129

2. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
   - In `prepareTextDays` and `prepareGraphDays`: compute cloud cover via new method, pass to `resolveIcon()`

3. Test files (add/update)
   - Test `calculateDaytimeCloudCover()` with hourly data
   - Test `resolveIcon()` returns cloudy icon when rain suppressed + cloud cover present
   - Test rain suppressed + no cloud cover still returns clear (backward compat)

## Phases

### Phase 1: Add `calculateDaytimeCloudCover()` to DailyForecastIconResolver
- Aggregate daytime hourly cloud cover for target date
- Return max daytime cloud cover (same pattern as precip)
- Use SunPositionUtils for sunrise/sunset hours

### Phase 2: Update `resolveIcon()` signature and fallback
- Add `daytimeCloudCover: Int? = null` parameter
- Replace null with daytimeCloudCover at both fallback sites

### Phase 3: Wire up in DailyViewLogic
- Compute cloud cover in `prepareTextDays` and `prepareGraphDays`
- Pass to resolveIcon calls

### Phase 4: Tests
- Unit test for `calculateDaytimeCloudCover`
- Unit test for `resolveIcon` with cloud cover fallback
- Verify existing suppression tests still pass

### Phase 5: Build & verify
- `./gradlew test`
- `./gradlew assembleDebug`
- Install and verify Sunday shows correct icon on Pixel
