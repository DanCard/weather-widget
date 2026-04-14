# WeatherIconMapper Code Review — 2026-04-14

1. Missing sleet/freezing rain/hail/ice conditions fall through to `ic_weather_clear` (sunny icon for freezing precipitation).
2. Slight-chance snow/storm discards precip signal entirely (`slightChanceCloudCoverIcon`), while slight-chance rain preserves it (`getPrecipitationIcon`). Inconsistent UX.
3. `isRainy()` includes snow — name should be `isPrecipitation()` for clarity.
4. `isRainIndicator()`, `isMixed()`, `isCloudForecastEligible()` are long `||` chains. Should use `Set<Int>` for O(1) lookup and maintainability.
5. `slightChanceCloudCoverIcon` is a one-line passthrough to `getCloudCoverIcon`. Inline it.

6. Overcast → `mostly_clear` is **confirmed intentional** (cloud cover data determines visual density; condition text is less extreme than "cloudy" label).

## Implementation Plan

### Step 1: Add sleet/freezing rain/hail/ice conditions
- Add `"freezing rain"`, `"sleet"`, `"ice"`, `"hail"` matchers before the rain branch.
- `"freezing rain"` / `"ice pellet"` → `ic_weather_rain` (cold-rain icon; no dedicated freezing-rain drawable exists).
- `"sleet"` → `ic_weather_snow` (mixed precip, closest available icon).
- `"hail"` → `ic_weather_storm`.
- All respect `isSlightChance` — same pattern as snow/storm branches.

### Step 2: Make slight-chance snow/storm show precip-aware icons
- Replace `slightChanceCloudCoverIcon(isNight, cloudCover)` calls with a new `slightChancePrecipIcon(isNight, cloudCover, baseIcon)` that delegates to `getPrecipitationIcon` with a low effective probability so slight-chance shows 1-drop variants.
- This brings storm/snow in line with rain: slight chance = cloud backdrop + slight precip symbol.

### Step 3: Rename `isRainy` → `isPrecipitation`
- Rename method and update all callers.

### Step 4: Convert boolean category methods to Set-based
- Build `val RAINY_ICONS: Set<Int>`, `val RAIN_INDICATOR_ICONS: Set<Int>`, `val MIXED_ICONS: Set<Int>`, `val CLOUD_FORECAST_ELIGIBLE_ICONS: Set<Int>`.
- Replace method bodies with `iconRes in SET`.

### Step 5: Inline `slightChanceCloudCoverIcon`
- Replace call sites with direct `getCloudCoverIcon` calls.

### Step 6: Update tests
- Add tests for sleet/freezing rain/hail/ice.
- Add tests for slight-chance snow/storm now showing precip icons.
- Rename `isRainy` test assertions to `isPrecipitation`.
- Verify existing tests still pass.