# Day/Night Rain Chance Thresholds

## Goal

Apply separate rain-chance suppression thresholds for daytime and nighttime. Currently, rain icons and labels are suppressed when precipitation probability is below a single threshold that ramps from 16% (today) to 33% (day 7+). The new logic:

- **Day threshold** (current formula, unchanged): `(7/3 * daysFromToday + 16).toInt().coerceIn(0, 33)` — ramps 16% to 33%
- **Night threshold** (new): `(35/6 * daysFromToday + 16).toInt().coerceIn(0, 51)` — ramps 16% to 51%

Each threshold is applied to **its own data**: day precip from daytime hourly max, night precip from nighttime hourly max. If **either** says "show rain," the rain icon/label is shown. Rain is only hidden when **both** agree it should be hidden.

## Threshold Table

| Day offset | Day threshold | Night threshold |
|------------|---------------|-----------------|
| 0 (today)  | 16%           | 16%             |
| 1          | 18%           | 21%             |
| 2          | 21%           | 27%             |
| 3          | 23%           | 33%             |
| 4          | 25%           | 39%             |
| 5          | 28%           | 45%             |
| 6          | 30%           | 51%             |
| 7+         | 33%           | 51%             |

## Data Source: Hourly Forecasts

The daily `ForecastEntity.precipProbability` field is a single daily max — it doesn't distinguish day from night. Instead of adding a database column, we derive separate day/night max from **hourly forecast data that is already loaded** during widget rendering.

### How hourly data is split into day vs night

For each future day, we compute sunrise/sunset hours using `SunPositionUtils` (which we'll make public), then:

1. Filter hourly forecasts to that date and display source
2. Classify each hour as "day" (between sunrise and sunset) or "night" (before sunrise or after sunset)
3. Take `max(precipProbability)` across day hours → `dayMaxPrecipProbability`
4. Take `max(precipProbability)` across night hours → `nightMaxPrecipProbability`

### Example

- Day 4 forecast, latitude 37.4, longitude -122.1
- Sunrise at 6.2h, sunset at 19.8h
- Day hours (06:00–19:00): max precip probability = 22%
- Night hours (20:00–05:00): max precip probability = 48%

With day threshold = 25% and night threshold = 39%:
- Day: 22% < 25% → day says "suppress"
- Night: 48% >= 39% → night says "show"
- **Result: show rain** (OR logic — one threshold passes = display)

Compare with old behavior: single daily max = 48%, threshold = 25% → would have shown anyway. But consider a day where **night precip is the only meaningful signal**:

- Day 6 forecast
- Day hours: max precip = 15% (below day threshold 30%)
- Night hours: max precip = 48% (above night threshold 51%? No, 48 < 51 → night also says suppress)
- **Result: hidden** — both day and night agree it's too low-confidence

Without this change, the single daily max of 48% on day 6 would show a rain icon even though the night threshold says 51%+ is needed for confidence at that distance.

## Implementation Steps

### 1. Add night threshold formula to `DailyForecastIconResolver`

**File:** `app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt`

- Rename `getMinimumPrecipProbability()` → `getMinimumPrecipProbabilityDay()` (keep old name as alias for backward compatibility)
- Add `getMinimumPrecipProbabilityNight(daysFromToday: Long): Int`
  - Formula: `(35.0 / 6.0 * daysFromToday + 16).toInt().coerceIn(0, 51)`
- Add `data class DayNightPrecip(val dayMax: Int?, val nightMax: Int?)`
- Add `calculateDayNightPrecipProbabilities(...)` that takes hourly forecasts, target date, lat/lon, and returns `DayNightPrecip`

### 2. Make `SunPositionUtils.calculateSunriseSunset()` accessible

**File:** `app/src/main/java/com/weatherwidget/util/SunPositionUtils.kt`

- Change `private fun calculateSunriseSunset(...)` → `fun getSunriseSunsetHours(...)` (public)
- Or add a new public method `getSunriseSunsetHours()` that returns both sunrise and sunset hours as a pair
- This is needed because the current `isNight()` only tells you if it's night *right now*, but we need sunrise/sunset for **future dates** to classify hourly data

### 3. Update `shouldSuppressRainIcon()` with OR logic

**File:** `DailyForecastIconResolver.kt`

Current logic:
```kotlin
val minProb = getMinimumPrecipProbability(daysFromToday)
return precipProbability != null && precipProbability < minProb
```

New logic:
```kotlin
val dayMinProb = getMinimumPrecipProbabilityDay(daysFromToday)
val nightMinProb = getMinimumPrecipProbabilityNight(daysFromToday)

val dayPrecip = dayPrecipProbability ?: weather.precipProbability
val nightPrecip = nightPrecipProbability ?: dayPrecip  // fallback to daily max if no hourly data

val daySuppresses = dayPrecip != null && dayPrecip < dayMinProb
val nightSuppresses = nightPrecip != null && nightPrecip < nightMinProb

return daySuppresses && nightSuppresses  // suppress only if BOTH agree
```

### 4. Update `resolveIcon()` signature

**File:** `DailyForecastIconResolver.kt`

Add parameters:
```kotlin
fun resolveIcon(
    weather: ForecastEntity?,
    targetDate: LocalDate,
    now: LocalDateTime,
    latitude: Double,
    longitude: Double,
    dayPrecipProbability: Int? = null,   // NEW: max precip during daytime hours
    nightPrecipProbability: Int? = null,  // NEW: max precip during nighttime hours
): Int
```

When both are null (first pass, no hourly data), fall back to current behavior using `weather.precipProbability` with day-only threshold. When provided, use OR logic as described above.

### 5. Update `buildDailyRainLabel()` with OR logic

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`

Add `nightPrecipProbability: Int? = null` parameter. Same OR logic:

```kotlin
val dayMinProb = DailyForecastIconResolver.getMinimumPrecipProbabilityDay(daysFromToday)
val nightMinProb = DailyForecastIconResolver.getMinimumPrecipProbabilityNight(daysFromToday)
val dayPrecip = precipProbability ?: dailyPrecipProbability
val nightPrecip = nightPrecipProbability ?: dayPrecip

val daySuppresses = dayPrecip != null && dayPrecip < dayMinProb
val nightSuppresses = nightPrecip != null && nightPrecip < nightMinProb

if (daySuppresses && nightSuppresses) {
    return null  // hide rain label only if both thresholds agree to suppress
}
```

Also remove the `println("DEBUG: ...")` debug logging at line 432 and 436.

### 6. Compute day/night precip in `prepareGraphDays()` and `prepareTextDays()`

**File:** `DailyViewLogic.kt`

Both functions already receive `hourlyForecasts: List<HourlyForecastEntity>`. For each future day (skip past/today — today uses `todayNext8HourPrecipProbability`), compute:

```kotlin
val dayNightPrecip = if (!isPastDate && !isToday) {
    DailyForecastIconResolver.calculateDayNightPrecipProbabilities(
        hourlyForecasts, date, now, lat, lon
    )
} else {
    null
}
```

Then pass `dayNightPrecip?.dayMax` and `dayNightPrecip?.nightMax` to `resolveIcon()` and `buildDailyRainLabel()`.

### 7. Update callers in `DailyViewHandler.kt`

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`

The header weather icon resolveIcon call (~line 270) should also be updated to pass day/night precip for today's date. The hourly forecasts are already available.

### 8. Update `DailyForecastGraphRenderer` rain font scaling (optional, future)

The rain label *font scaling* in `drawDailyRainLabel()` currently uses `day.dailyPrecipProbability` (the single daily max). This could optionally be updated to use the max of day/night precip for font scaling purposes, but this is a cosmetic refinement and can be done later.

### 9. Update tests

**File:** `app/src/test/java/com/weatherwidget/util/DailyForecastIconResolverTest.kt`

- Add tests for `getMinimumPrecipProbabilityNight()` at each day offset (0-7+)
- Add tests for OR logic suppression:
  - Both day and night below threshold → icon suppressed
  - Day above threshold, night below → icon shown
  - Day below threshold, night above → icon shown
  - Both above → icon shown
- Add tests with `nightPrecipProbability = null` (fallback to daily)
- Existing tests don't break because the new parameters default to `null`

**File:** `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt` (if it exists)

- Add tests for `buildDailyRainLabel()` with night threshold

### 10. Add `calculateDayNightPrecipProbabilities()` tests

Test the hourly splitting logic:
- All day hours with precip, no night hours → night max = null
- All night hours with precip, no day hours → day max = null
- Mixed hours → correct max values for each
- Empty hourly data → both null
- Hourly data from different source → filtered to display source

## Files Modified

| File | Change |
|------|--------|
| `DailyForecastIconResolver.kt` | Add night threshold formula, `calculateDayNightPrecipProbabilities()`, update `shouldSuppressRainIcon()` with OR logic, add params to `resolveIcon()` |
| `SunPositionUtils.kt` | Make sunrise/sunset calculation public for future dates |
| `DailyViewLogic.kt` | Add `nightPrecipProbability` param to `buildDailyRainLabel()`, compute day/night precip in `prepareGraphDays()` and `prepareTextDays()`, remove debug println |
| `DailyViewHandler.kt` | Update `resolveIcon()` and `prepareGraphDays()`/`prepareTextDays()` calls to pass day/night precip |
| `DailyForecastIconResolverTest.kt` | Night threshold tests, OR logic tests |
| `DailyViewLogicTest.kt` | Rain label night threshold tests |

## What Stays the Same

- `ForecastEntity` — no schema change, no migration
- `HourlyForecastEntity` — no change
- `DayData` data class — `dailyPrecipProbability` stays (used for rain font scaling)
- `DailyForecastGraphRenderer` — rain font scaling unchanged
- Rain label *content* — still shows the daily percentage from `ForecastEntity.precipProbability`

## Rendering Strategy: Inline Computation

The day/night precip computation will happen **inline within the same function call** as the rest of the day data building. This is fast because:

1. Hourly forecasts are already loaded and passed to `prepareGraphDays()` / `prepareTextDays()`
2. `SunPositionUtils` is pure math — no IO
3. Filtering hourly data for one day is O(~24) iterations
4. The total overhead per day is microseconds

If performance becomes a concern (e.g., 30+ days of history), a separate refinement pass could be extracted later, but there's no need for the architectural complexity now.

The "second pass" concept from the original discussion is achieved naturally: on the first widget render (before hourly data is available or when it's stale), the code falls back to the current day-only behavior. When hourly data refreshes, the next render will have full day/night precision. No explicit two-phase rendering pipeline is needed.