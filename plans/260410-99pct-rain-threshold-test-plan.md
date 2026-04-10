# Test Plan: >=99% Precipitation Threshold for Rainfall Amount Label

## Change Under Test

`DailyViewLogic.buildDailyRainLabel()` was changed from `precipProbability == 100` to `precipProbability >= 99` on line 409.

This means rainfall amount is now shown when probability is 99% or above (previously only at exactly 100%).

## Method Logic

```kotlin
private fun buildDailyRainLabel(
    date, today, isPastDate, iconRes, precipProbability, precipAmountMm
): String? {
    if (date == today || isPastDate || !isRainIndicatorIcon(iconRes)) return null
    return when {
        precipProbability >= 99 && precipAmountMm != null -> formatPrecipAmount(precipAmountMm)
        precipProbability > 0                                -> "$precipProbability%"
        else                                                -> null
    }
}
```

## Test Cases

### Essential new tests (the >= 99 boundary change)

| # | Name | Probability | Amount | Icon | Expected | Rationale |
|---|------|-------------|--------|------|----------|-----------|
| 1 | `99 with amount shows amount` | 99 | 5.0f | Rain | `"5mm"` (locale) | New threshold — 99 now triggers amount display |
| 2 | `98 with amount shows percentage` | 98 | 5.0f | Rain | `"98%"` | Just below threshold — still shows % |
| 3 | `99 with null amount shows percentage` | 99 | null | Rain | `"99%"` | Fallback when amount data missing |

### Edge cases and regression confirmation

| # | Name | Probability | Amount | Icon | Expected | Rationale |
|---|------|-------------|--------|------|----------|-----------|
| 4 | `0 with rain icon returns null` | 0 | null | Rain | `null` | Zero probability — no label |
| 5 | `1 with rain icon shows 1%` | 1 | null | Rain | `"1%"` | Minimum non-zero % |
| 6 | `100 with amount still shows amount` | 100 | 0.0508f | Rain | `".002in"` | Regression — existing behavior preserved |

### Preconditions that suppress the label regardless of probability

These are already tested (test `today omits graph rain label`) but worth noting:

- `date == today` → null (even if 99%+ with amount)
- `isPastDate == true` → null
- `iconRes` is not rain/storm → null

## Implementation Notes

- Tests use `createWeather()` helper which defaults `precipProbability = 0`, so tests must explicitly set it
- `condition = "Rain"` ensures `isRainIndicatorIcon()` returns true via `ic_weather_rain`
- Format strings depend on `Locale.getDefault().country` — Robolectric defaults to US, so expect inches format for amounts ≥ 0.01in
- The `dailyRainLabelText` field on `ForecastBarData` is what we assert against

## Files

- Test: `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt`
- Code under test: `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` (line 399-413)