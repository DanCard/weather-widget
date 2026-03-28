# Fix: Next Saturday missing from daily forecast view (NWS)

## Context
Today is Saturday Mar 28. The emulator uses NWS API. Next Saturday (Apr 4, day +7) doesn't appear in the daily forecast.

## Root Cause
NWS returns 14 forecast periods (7 day/night pairs). The last **night** period (Friday night) has its low attributed to the following day (Saturday Apr 4, line 501 in `ForecastRepository.kt`):

```kotlin
val lowDateString = extractNwsForecastDate(period.endTime) ?: dateString
```

This creates a "phantom day" — Apr 4 gets a `ForecastEntity` with `lowTemp` but `highTemp == null`. Two consequences:

1. **Display filter drops it** — `DailyViewLogic.kt:226-227` requires both high AND low for future days
2. **Gap-fill skips it** — NWS reports Apr 4 as its max coverage date, so climate normals gap-fill starts from Apr 5 (line 142, 156-157)

Result: Apr 4 has incomplete NWS data AND no climate normal fallback.

## Fix
**In `fetchFromNws()`, remove future phantom days from `temperatureMap` before building ForecastEntities.** A phantom day is a future date with only a low temp and no high temp (caused by the last overnight period attribution).

### File: `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`

After `applyForecastPeriods` (line 387) and before `temperatureMap.map { ... }` (line 393), add:

```kotlin
// Remove phantom future days that only have a low from the last NWS overnight
// period. Without a corresponding daytime period, these can't display (both
// high and low required). Removing them lets climate normals gap-fill provide
// complete data instead.
temperatureMap.entries.removeAll { (dateStr, temps) ->
    LocalDate.parse(dateStr).isAfter(todayDate) && temps.first == null
}
```

This shifts the NWS max coverage date back by one day (Apr 3 instead of Apr 4), allowing climate normals to gap-fill Apr 4 with complete high+low data.

**Total: 1 file, ~5 lines added.**

## Why this approach
- **Minimal** — single targeted change at the data source
- **No display logic changes** — the filter requiring both high+low for future days is correct behavior
- **Climate normals provide better data** — a complete high+low from historical averages is more useful than an orphaned low temp
- **Self-correcting gap-fill** — once the phantom day is gone, the existing gap-fill machinery fills Apr 4 automatically

## Not changed
- `DailyViewLogic.kt` display filter — correct as-is
- `NavigationUtils.kt` — no column calculation issues
- Open-Meteo forecast_days — separate from this NWS issue (though could be increased independently later)

## Verification
1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest` — unit tests pass
2. `./gradlew installDebug` on emulator
3. Force widget refresh, verify Apr 4 (next Saturday) column appears with climate normal data
4. Check logs for gap-fill covering Apr 4: look for `SNAPSHOT_SAVE` or climate normal entries
