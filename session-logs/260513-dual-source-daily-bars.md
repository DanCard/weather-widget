# Session Log: Dual-Source Daily Bars Feature (2026-05-13)

## Overview
User requested a new Settings option to display two API source bars on the daily forecast view simultaneously. Implementation evolved iteratively across the session through several visual refinements driven by on-device feedback from a Samsung Galaxy Z Fold 4 (`RFCT71FR9NT`). Final feature includes a parallel "next-source" bar rendered next to the primary bar on every day, with the second bar reflecting its source's *own* cloud-cover and condition data (not a copy of the primary's), a dual-source header label that fits when there is space, and automated tests covering both the data layer and the renderer.

## 1. Initial Feature Design and Implementation

### Requirement
- Settings toggle "Show two bars on daily forecast view"
- When ON: draw the NEXT API source's bar alongside the current source's bar on every day
- Header API indicator shows `"<first> - <second>"` when there's space, falls back to single-source label otherwise
- Tapping the indicator still cycles the toggle step by 1 (both labels update together because "next" is derived from "current")
- Existing forecast overlay (yellow), today's triple bar, sizing rules unchanged

### Architecture Discovery
Three parallel `Explore` agents established the relevant code paths:
- **Settings UI**: plain `AppCompatActivity` at `SettingsActivity.kt`, uses `WidgetStateManager` singleton for preferences (key pattern: companion constant + getter/setter)
- **Renderer**: `DailyForecastGraphRenderer.kt` already supports offset bars (existing `FORECAST_BAR_OFFSET_SCALE = 0.7`) — adding a second source bar is geometrically the same trick
- **Source filtering**: happens in `DailyViewHandler.kt:216` where `weatherList` is filtered to current `displaySource.id` only. Both sources' data exists upstream (composite PK `(date, source)` keeps NWS + Open-Meteo data side-by-side in the DB)
- **Source cycling**: `WidgetStateManager` uses a step-counter modulo visible-sources list — so "next API" = `visibleSources[(step + 1) % size]`, not a binary flip. Matters because users can have 2-8 visible sources

### Files Modified (Initial Implementation)
- `WidgetStateManager.kt`: new global `KEY_SHOW_TWO_BARS` pref, `isShowTwoBarsEnabled()`/`setShowTwoBarsEnabled()`, `getNextDisplaySource(widgetId)` peek helper
- `activity_settings.xml`: new `CheckBox @+id/show_two_bars_checkbox`
- `strings.xml`: new `show_two_bars_on_daily` string
- `SettingsActivity.kt`: wired CheckBox listener → calls `setShowTwoBarsEnabled()` + `WeatherWidgetProvider.triggerUiOnlyUpdate()`
- `DailyViewHandler.kt`: parallel `nextSourceWeatherByDate` map built alongside the existing single-source filter (does NOT touch the existing filter — additive); composes dual-source header label using `HeaderWidthChecker.resolveHeaderDisclosure` twice (once with single text, once with dual) and uses the longer label only when both yield the *same* disclosure level
- `DailyViewLogic.kt`: new `nextSourceWeatherByDate` parameter on `prepareGraphDays()`; populates new `DayData` fields
- `DailyForecastGraphRenderer.kt`: new `nextSourceHigh/Low` on `DayData`, new offset constants, new draw block in `drawDayBars()` and parallel block for today

## 2. Visual Refinement Iterations

The user reviewed each device build and gave concrete corrections. Each iteration touched the renderer only, no data-layer changes needed after the initial wiring.

### Iteration 1: Left → Right
- **Original ask**: place next-source bar LEFT of primary
- **Correction**: "I confused left and right — put it on the right"
- **Change**: flipped offset sign and added clearance past the forecast overlay. Layout became `[primary] [forecast-overlay] [next-source]` for non-today, `[snapshot] [observed] [today-forecast] [next-source]` for today
- **Result**: bars on the right but too widely spaced on future days (the formula used full `barWidth` of clearance past forecast overlay center, which over-reserved since forecast overlay paint is only `0.7 × barWidth` wide)

### Iteration 2: Tighter future-day gap
- **Feedback**: "Too much space on future days"
- **Root cause**: formula assumed clearance equal to a full primary bar width past the forecast overlay's *center*, but the forecast overlay's *actual* width is `0.7 × barWidth`
- **Fix**: changed to `2 × barWidth × FORECAST_BAR_OFFSET_SCALE + gap` (geometric formula treating forecast overlay's half-width correctly)

### Iteration 3: Narrower second bar + tighter gap
- **Asks**: "Make the second api 0.6 width of main api" and "future-day gap still too big"
- **Architectural change**: introduced a new paint type `nextSourceBarPaint` in `PaintSet` with stroke width `barWidth × 0.6`, plus matching `nextSourceForColor(color)` cache helper. Same pattern as existing `barPaint`, `forecastBarPaint`, `historyBarPaint`
- **Offset reworked**: switched to a clean geometric formula:
  ```
  nextSourceBarOffset = (rightEdgeOfPrecedingBar) + (halfNextSourceWidth) + (1dp gap)
  ```
  For non-today: precedingBar = forecast overlay (right edge at `barWidth × 1.05`)  
  For today: precedingBar = today-forecast (right edge at `tripleBarOffset + tripleBarWidth/2`)
- **Reduced** `NEXT_SOURCE_BAR_GAP_DP` from `2f` to `1f` since the geometric formula already accounts for half-widths properly

### Iteration 4: Correct cloud-cover for the second bar
- **Feedback**: "The bar shows cloud cover at the bottom with grey. This is not correct on second api — it's just a copy of first api cloud cover"
- **First attempt (quick fix)**: set `allowAdaptiveSegments = false` on the next-source draw call. This disabled the grey-bottom split entirely. User rejected: "I don't want a quick fix. I want the correct cloud cover painted on second vertical bar. Create an automated test plan for this."
- **Proper fix** (entered Plan mode, designed via Plan agent, wrote final plan to `~/.claude/plans/add-an-option-to-radiant-horizon.md`):
  - Added five new fields to `DayData`: `nextSourceIconRes`, `nextSourceIsSunny`, `nextSourceIsRainy`, `nextSourceIsMixed`, `nextSourceCloudCoverRatioOverride`
  - Added `nextSource: WeatherSource?` parameter to `prepareGraphDays()` and threaded it through from `DailyViewHandler.kt`
  - Reused **existing helpers** for the next-source computation: `resolveNoonCloudCoverRatio(date, hourlyForecasts, displaySource = nextSource, weatherSourceId = nextSourceWeather.source)` and `DailyForecastIconResolver.resolveIcon(weather, …, cloudCover = nextSourceCloudCoverPercent)`. No new helpers introduced
  - In the renderer's next-source draw block, used `data class .copy()` to synthesize a `nextDayView` with the next-source-specific fields swapped into the `iconRes`/`isSunny`/`isRainy`/`isMixed`/`cloudCoverRatioOverride` slots, then passed that copy to `drawWeatherAdaptiveBar`. This was the load-bearing trick — `drawWeatherAdaptiveBar` and `shouldUseAdaptiveSegments` read those slots directly, so the existing rendering logic correctly computes cloud-cover splits for the next source with zero helper-signature changes
  - Color also derived from `nextSourceIs*` flags (previously was using primary `isSunny`/`isRainy`/`isMixed`)
  - Restored `allowAdaptiveSegments = !day.isPast` (was `false` during quick-fix)

### Iteration 5: Reduce primary bar width 20% in dual mode
- **Ask**: "When there are two bars displayed, can we reduce the main bar width by 20%?"
- **Implementation**: added `PRIMARY_BAR_DUAL_SOURCE_WIDTH_SCALE = 0.8f`, new paint `primaryDualSourceBarPaint` with stroke `barWidth × 0.8`, new cache `primaryDualSourceByColor` + helper `primaryDualSourceForColor(color)`. Selected via a new `when` branch in the primary-bar paint selection at `drawDayBars` — scoped to future days only (past days continue using `historyBarPaint`, today triple uses `tripleBarWidth`)
- **Why scope to future only**: the user said "main bar" (singular). Past `historyBarPaint` is already `0.7 × barWidth` (shrinking further would make it thinner than the next-source bar — visually weird). Today triple bars use `tripleBarWidth` which is already narrow

### Iteration 6: Move toggle higher in Settings
- **Ask**: "What do you think of moving up the 'show two bars' on settings screen?"
- **Reasoning**: original placement was below the API sources list and above the icon gallery — visually straddled two sections. Toggle semantically belongs to the API-sources section
- **Change**: moved the `CheckBox` to sit between the section description TextView and the `api_sources_container` LinearLayout. Adjusted margins: description's `layout_marginBottom` reduced `16dp → 8dp` (tight gap to related toggle), toggle's `layout_marginBottom` set to `16dp` (full section break before the source list)
- **Tap-target preserved**: kept `layout_width="match_parent"` so the whole row is tappable, matching the per-source `item_api_source` pattern

## 3. Automated Tests

Two complementary tests cover the cloud-cover-correctness fix:

### Test 1: `DailyViewLogicTest.prepareGraphDays populates next-source cloud cover from next-source hourly`

**File**: `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt`

Constructs two parallel hourly cloud-cover streams at noon (NWS=10%, Open-Meteo=85%) plus two `ForecastEntity` rows (NWS clear/dry, Open-Meteo cloudy/40%-precip). Calls `prepareGraphDays(…, nextSource = WeatherSource.OPEN_METEO)` and asserts:
- `day.cloudCoverRatioOverride == 0.10f` (primary cloud cover from NWS hourly)
- `day.nextSourceCloudCoverRatioOverride == 0.85f` (next-source from Open-Meteo hourly — the critical assertion)
- `day.iconRes != day.nextSourceIconRes` (icons resolved per source differ when conditions differ)
- `!day.isRainy && (day.nextSourceIsRainy || day.nextSourceIsMixed)` (condition flags don't bleed across sources)

This is the **load-bearing test for the data layer**. Reuses existing test helpers `createWeather()` and `createHourlyForecast()` at lines 1362–1410 of the same file.

### Test 2: `DailyForecastGraphRendererRoboTest.nextSourceBar_usesNextSourceConditionColor`

**File**: `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt`

Constructs a `DayData` directly with primary=sunny/dry and next-source=rainy/cloudy, calls `renderGraph` with an `onBarDrawn` capture callback, then asserts:
- `BarDrawnDebug` for `barType = "NEXT_SOURCE"` has the color produced by `WeatherConditionColors.forecastColor(isSunny=false, isRainy=true, …)`
- `BarDrawnDebug` for `barType = "FUTURE"` has the color produced by `WeatherConditionColors.forecastColor(isSunny=true, isRainy=false, …)`
- **`assertNotEquals(primaryBar.color, nextBar.color)`** — the load-bearing assertion. Today's code reads `day.isSunny`/`day.isRainy` for the next-source color (primary's flags); after the fix it reads `day.nextSourceIs*`. Same conditions across two sources → no diff catches it; different conditions → diff catches the bug

The test pair forms a contract: data correctness × consumption correctness.

### Results
```
DailyViewLogicTest > prepareGraphDays populates next-source cloud cover from next-source hourly PASSED
DailyForecastGraphRendererRoboTest > nextSourceBar_usesNextSourceConditionColor PASSED
```

Full renderer + view-logic suite (38 tests) all green. Existing tests unaffected because new `DayData` fields all default (`null` / `false`).

## 4. Final Visual State (Samsung Z Fold 4)

Verified on `RFCT71FR9NT` (Samsung Galaxy Z Fold 4, inner display 1812×2176, density 420). Screenshots captured via `adb exec-out screencap -p` with the warning-prefix workaround (strip until PNG magic `\x89PNG`):

- **Future days**: `[primary at 0.8× width with own cloud-cover split] [forecast overlay] [narrow next-source at 0.6× width with its own cloud-cover split]`. Visible difference in grey-bottom ratio between primary and next-source bars on days where the two APIs disagree on cloud cover
- **Today**: `[snapshot-yellow] [observed-red+bulb] [today-forecast-blue] [next-source]` — 4-bar cluster, next-source bar uses its own metadata
- **Past days**: `[history-red] [forecast overlay] [narrow next-source]` — next-source bar drawn solid (no adaptive segments for past) matching the history bar's solid style
- **Header**: reads `NWS - Tmrw` on a 4-col-wide widget; falls back to single-source on narrower widgets via the disclosure-level fit check
- **Settings**: "Show two bars on daily forecast (current + next API)" sits inside the Weather Data Sources section, above the per-source list

## 5. Architectural Patterns Reused

The implementation deliberately avoided introducing new abstractions:
- **PaintSet cache lanes**: added `nextSourceBarPaint`, `primaryDualSourceBarPaint` as parallel lanes alongside existing `barPaint`/`historyBarPaint`/`forecastBarPaint`. Same `ConcurrentHashMap<Int, Paint>` + `getOrPut` color-tinted variant pattern
- **`HeaderWidthChecker.resolveHeaderDisclosure` twice**: used the existing disclosure cascade to decide if the dual-source label fits without degrading the rest of the header — no new width-fit logic
- **`resolveNoonCloudCoverRatio` reused**: called with `displaySource = nextSource` for the next-source case
- **`DailyForecastIconResolver.resolveIcon` reused**: called with the next-source `ForecastEntity`
- **`data class .copy()` for the renderer swap**: avoided adding parameters to `drawWeatherAdaptiveBar`
- **`DayData` extension**: all new fields default to `null`/`false`, so the 5+ existing test files that construct `DayData(...)` compile and pass unchanged
- **Parallel `nextSourceWeatherByDate` map** in `DailyViewHandler`: built alongside the existing single-source filter at line 216 instead of modifying it — keeps the blast radius minimal

## 6. Files Touched

**Production**:
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- `app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt`
- `app/src/main/res/layout/activity_settings.xml`
- `app/src/main/res/values/strings.xml`

**Tests**:
- `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt`
- `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt`

**Plan file**:
- `~/.claude/plans/add-an-option-to-radiant-horizon.md` (extended in-session with Step 6 + automated test plan during the cloud-cover refinement)

## 7. Verification Commands

```bash
# Compile + unit tests
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest \
  --tests "com.weatherwidget.widget.handlers.DailyViewLogicTest*" \
  --tests "com.weatherwidget.widget.DailyForecastGraphRenderer*"

# Install on Samsung specifically (avoids reinstalling on other connected devices)
./gradlew :app:assembleDebug
adb -s RFCT71FR9NT install -r app/build/outputs/apk/debug/app-debug.apk

# Verify wiring across the codebase
grep -rn "isShowTwoBarsEnabled\|nextSourceIconRes\|nextSourceCloudCoverRatioOverride\|PRIMARY_BAR_DUAL_SOURCE_WIDTH_SCALE" app/src/main app/src/test
```

## 8. Metadata
- **Date**: 2026-05-13
- **Device**: Samsung SM-F936U1 (Galaxy Z Fold 4), inner display, density 420
- **Status**: Feature complete and verified on-device. Two automated tests passing. No regressions in existing test suite.
- **Iterations driven by**: live screenshot feedback loop (build → install → screencap → user review → adjust)
