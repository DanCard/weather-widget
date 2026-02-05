# Findings: NWS Icon Mismatch

## Icon Mapping Logic
- **Location:** `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt`
- **Current mapping for "observed":** Maps to `R.drawable.ic_weather_clear` (Correct).
- **Default fallback:** `R.drawable.ic_weather_clear`.

## Bug 1: Restricted "isSunny" list for tinting
- **Location:** `WeatherWidgetProvider.kt` (in `populateDay` and `buildDayDataList`).
- **Logic:** `val isSunny = iconRes == R.drawable.ic_weather_clear || iconRes == R.drawable.ic_weather_partly_cloudy`.
- **Issue:** It MISSES `R.drawable.ic_weather_mostly_clear`. 
- **Consequence:** Conditions like "Mostly Sunny (25%)" or "Mostly Clear" are tinted GREY (#AAAAAA) instead of YELLOW (#FFD60A). A grey sun icon looks like a cloud to users.

## Bug 2: Forecast overwriting Actual observations
- **Location:** `WeatherRepository.kt` in `fetchFromNws`.
- **Logic:** The loop that processes NWS forecast periods (`forecast.forEachIndexed`) overwrites `conditionByDate[date]` even if it was already set by `fetchDayObservations`.
- **Issue:** For "Today", we might have actual observations saying it's Sunny, but the NWS forecast (which might be hours old) says "Cloudy". The forecast wins.
- **Consequence:** The widget shows the forecast condition instead of the actual observed condition for the current day.

## Bug 3: "Fair" missing from cloudScores
- **Location:** `WeatherRepository.kt` in `fetchDayObservations`.
- **Issue:** "Fair" is a common NWS observation but is not handled in the `cloudScores` map. It defaults to 50 (Partly Cloudy).
- **Consequence:** Sunny "Fair" days are downgraded to "Partly Cloudy".

## Database State (from backups)
- Recent backups show `condition = 'Observed'` for Feb 3 and Feb 4.
- If it's "Observed", it *should* be yellow sunny.
- However, if the user sees "cloudy", maybe they are actually seeing "Mostly Sunny (25%)" tinted grey, OR they are seeing "Cloudy" from an overwritten forecast.
