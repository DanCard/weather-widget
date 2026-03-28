# Fix: Next Saturday missing from daily forecast view (NWS)

## Context
Today is Saturday Mar 28. The emulator uses NWS API. Next Saturday (Apr 4, day +7) doesn't appear in the daily forecast.

## Root Cause
NWS returns 14 forecast periods (7 day/night pairs). The last **night** period (Friday night) has its low attributed to the following day (Saturday Apr 4) via `extractNwsForecastDate(period.endTime)` at line 501 in `ForecastRepository.kt`.

This creates a "phantom day" — Apr 4 gets a `ForecastEntity` with `lowTemp` but `highTemp == null`. Two consequences:

1. **Display filter drops it** — `DailyViewLogic.kt:226-227` requires both high AND low for future days
2. **Gap-fill skips it** — NWS reports Apr 4 as its max coverage date (line 142), so climate normals gap-fill starts from Apr 5

Result: Apr 4 has incomplete NWS data AND no climate normal fallback.

## Implementation

### Step 1: Extract phantom day removal as testable companion function

**File:** `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`

Add to `companion object` (near `hasMeaningfulHourlyChange`, line ~71):

```kotlin
@androidx.annotation.VisibleForTesting
internal fun removePhantomFutureDays(
    temperatureMap: MutableMap<String, Pair<Float?, Float?>>,
    today: LocalDate,
) {
    temperatureMap.entries.removeAll { (dateStr, temps) ->
        LocalDate.parse(dateStr).isAfter(today) && temps.first == null
    }
}
```

### Step 2: Call it in fetchFromNws

**File:** `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`

After `applyForecastPeriods` call (line 387) and before `temperatureMap.map { ... }` (line 393), add:

```kotlin
removePhantomFutureDays(temperatureMap, todayDate)
```

### Step 3: Add phantom day removal tests

**New file:** `app/src/test/java/com/weatherwidget/data/repository/ForecastRepositoryPhantomDayTest.kt`

Tests using the `@VisibleForTesting` companion function (same pattern as `ForecastRepositoryHourlyChangeTest.kt`):

| Test | Setup | Expected |
|------|-------|----------|
| `removes future date with null high and valid low` | `"2026-04-04" → (null, 65f)`, today=Mar 28 | Entry removed |
| `keeps future date with both high and low` | `"2026-04-04" → (72f, 65f)`, today=Mar 28 | Entry kept |
| `keeps today with null high` | `"2026-03-28" → (null, 65f)`, today=Mar 28 | Entry kept (early AM edge case) |
| `keeps past date with null high` | `"2026-03-27" → (null, 65f)`, today=Mar 28 | Entry kept |
| `removes multiple phantom days` | Two future dates with null high | Both removed |
| `no-op on empty map` | Empty map | No error |

### Step 4: Add DailyViewLogic gap-fill visibility test

**New file:** `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt`

Tests using `DailyViewLogic.prepareGraphDays()` (pure function, uses `TestData.forecast()` helper):

| Test | Setup | Expected |
|------|-------|----------|
| `future day with GENERIC_GAP data is visible` | Day +7 has GENERIC_GAP forecast with both high+low in `weatherByDate` | Day appears in output with `isSourceGapFallback = true` |
| `future day with null highTemp is filtered out` | Day +7 has NWS forecast with `highTemp=null` | Day absent from output |
| `NWS days 0-6 plus gap day 7 all render` | 7 NWS days (complete) + 1 GENERIC_GAP day | 8 days in output (assuming 9-column widget) |

These tests use:
- `TestData.forecast(source = "GENERIC_GAP", ...)` for gap-fill entries
- `TestData.forecast(source = "NWS", highTemp = null, ...)` for phantom days
- Fixed dates to avoid flakiness
- `@Category(ShortDuration::class)` annotation

## Key Files
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt` — phantom day removal
- `app/src/test/java/com/weatherwidget/data/repository/ForecastRepositoryPhantomDayTest.kt` — new test
- `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt` — new test
- `app/src/test/java/com/weatherwidget/testutil/TestData.kt` — existing test helpers (no changes)

## Verification
1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest` — all tests pass
2. `./gradlew installDebug` on emulator
3. Force widget refresh, verify Apr 4 (next Saturday) column appears with climate normal data
