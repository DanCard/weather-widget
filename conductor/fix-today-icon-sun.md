# Plan: Fix Today Icon to Show Sun (Represent Day Outlook)

The "Today" icon currently shows a "Night" version (Moon/Cloud) if viewed before sunrise. To make the daily summary more representative of the upcoming day's peak weather, we will force the Daily column to always use "Day" icons (Sun-based) and ensure "Mostly Cloudy" reads as a sun-dominant icon.

## Proposed Changes

### 1. Update `DailyForecastIconResolver.kt`
- Force `isNight = false` when resolving icons for any daily column.
- This ensures that if the forecast says "Sunny" or "Mostly Cloudy," you see a Sun even if it's currently 5:00 AM.

### 2. Update `WeatherIconMapper.kt`
- Correct the mapping for `"mostly cloudy"` (Day version). It currently maps to `ic_weather_partly_cloudy` (which is very sunny). We will map it to `ic_weather_mostly_cloudy` which is a more accurate representation but still contains a Sun.

## Implementation Steps

1.  **Modify `app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt`**:
    - Inside `resolveIcon`, change `val isNight = ...` to `val isNight = false`.
    - Inside `resolveNativeTokenIcon` -> `WeatherSource.OPEN_METEO`, change `val isNight = ...` to `val isNight = false`.
    - Inside `silurianIcon`, change `val isNight = ...` to `val isNight = false`.

2.  **Modify `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt`**:
    - Update the `"mostly cloudy"` case to use `R.drawable.ic_weather_mostly_cloudy` for the `else` (Day) branch.

## Verification

- Verify on the emulator that the "Today" icon (currently "Mostly Cloudy" at 05:36 AM) now shows a Sun instead of a Moon.
- Run `WeatherIconMapperTest` to ensure no regressions in keyword mapping.
