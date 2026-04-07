# Weather-Adaptive Forecast Colors

## Context
The forecast line (hourly graph) and forecast bars (daily view) currently use static blue colors. The user wants forecast colors to reflect weather conditions — amber for sunny, gray for cloudy, blue for rainy — creating a visual "mood" that communicates the forecast at a glance. The actual/observed line changes from gold to hot pink (`#FF3366`) to contrast against all weather-adaptive colors. This hot pink is already used as `COLOR_OBSERVED_RED` in the daily bars, creating visual consistency.

## Color Mapping

| Condition | Forecast Color | Hex |
|-----------|---------------|-----|
| Sunny/clear | Amber/gold | `#F4C542` |
| Cloudy/mixed | Slate gray | `#8E99A4` |
| Rainy/storm/snow | Steel blue | `#5A8FBF` |
| Night (clear) | Muted silver | `#BBBBBB` |
| Default (no flags) | Amber/gold | `#F4C542` |
| **Actual/observed** | **Hot pink** | **`#FF3366`** |

## Files to Modify

| File | Change |
|------|--------|
| `app/.../util/WeatherConditionColors.kt` | **NEW** — shared color constants + `forecastColor()` mapper |
| `app/.../widget/TemperatureGraphRenderer.kt` | Actual line → pink; segmented forecast line drawing |
| `app/.../widget/GraphRenderUtils.kt` | Add `buildPerSegmentPaths()` |
| `app/.../widget/DailyForecastGraphRenderer.kt` | Per-day condition-based bar colors |

## Implementation Steps

### Step 1: Create `WeatherConditionColors.kt`
**New file:** `app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt`

- Color constants for each condition
- `forecastColor(isSunny, isRainy, isMixed, isNight) → Int` — priority: rainy > mixed > night > sunny > default

### Step 2: Add `buildPerSegmentPaths()` to `GraphRenderUtils.kt`
- Takes `points: List<Pair<Float, Float>>`, returns `List<Path>`
- Each Path is one cubic segment (point[i] → point[i+1]) using same Catmull-Rom tangent logic as existing `buildSmoothCurveAndFillPaths()`
- Reuses existing `computeTangents()`

### Step 3: Modify `TemperatureGraphRenderer.kt`

**3a. Change actual line color:**
- `COLOR_ACTUAL_LINE`: `#F4C542` → `#FF3366` (hot pink)
- `COLOR_ACTUAL_LABEL`: `#FFF1A8` → `#FFB3C6` (light pink, readable)

**3b. Add `forecastSegmentPaths` to data classes:**
- Add `forecastSegmentPaths: List<Path>` to both `RenderContextUpdate` (line ~1257) and `RenderContext` (line ~1208)
- Compute in `computePoints()` via `GraphRenderUtils.buildPerSegmentPaths(forecastPoints)`

**3c. Replace single forecast path draw with per-segment loop:**
- In `drawFillAndCurves()` at line 615, replace `canvas.drawPath(ctx.forecastPath, paints.forecastDashedPaint)` with:
  - Clone `forecastDashedPaint` once
  - Loop over `forecastSegmentPaths`, set paint color per segment from `hours[i]` condition flags via `WeatherConditionColors.forecastColor()`
- `drawFillAndCurves()` needs `hours: List<HourData>` parameter added (already available at call site)

### Step 4: Modify `DailyForecastGraphRenderer.kt`

**4a. Future forecast bars:** In `drawDayBars()`, replace static `paints.barPaint` with condition-colored paint using `WeatherConditionColors.forecastColor(day.isSunny, day.isRainy, day.isMixed, false)`

**4b. Today's forecast bar (right bar in triple):** Change `todayForecastBluePaint` color to condition-based

**4c. Forecast overlay bars:** Same treatment for non-today forecast overlays

**4d. Approach:** Use a single reusable `Paint` (cloned from `barPaint`) and set `.color` per bar — avoids allocation while keeping the shared paint immutable

### Step 5: Update remaining color constants
- Remove or deprecate `COLOR_FORECAST_LINE` in TemperatureGraphRenderer (no longer one static color)
- `COLOR_FORECAST` in DailyForecastGraphRenderer becomes fallback only

## What NOT to change
- **Forecast labels** — keep uniform color, avoid visual noise
- **Forecast fill/gradient** — not currently drawn, leave as-is
- **Expected/ghost line** — stays as-is (different concept from forecast)
- **Rain text paint** in DailyForecastGraphRenderer — keep existing color for now

## Verification
1. `./gradlew testDebugUnitTest` — existing tests pass (geometry unchanged)
2. Build and install: `./gradlew installDebug`
3. Visual check on device: sunny days should show amber bars, rainy days steel blue, cloudy days gray
4. Hourly graph: forecast line should change color as it passes through different weather conditions
5. Actual line should be hot pink on hourly graph, matching the observed bars on daily view
