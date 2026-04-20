# DailyForecastGraphRenderer Code Review and Refactor

**Date:** 2026-04-19
**Model:** glm-5.1
**Scope:** `DailyForecastGraphRenderer.kt` and all call sites across production and test code

---

## Prompts

1. "code review DailyForecastGraphRenderer"
2. "implement, I like to logging, so try not to remove too much"
3. "What's next?"
4. "do it"
5. "What do you think about: DayData decomposition?"
6. "Can we start with 5. Then decide how to proceed after that?"
7. "Option B" (full migration, no backward-compatible delegating accessors)
8. "continue" (after session pause during DailyViewLogicTest.kt fixes)
9. "write detailed session log to session-logs/ dir. Include all prompts"

---

## Phase 1: Code Review

Initial review of `DailyForecastGraphRenderer.kt` (760 lines) identified these issues:

### Bugs and Correctness

1. **Dead `heightScaleFactor` branch** (lines 212-216): Two branches returned `1.0f` (`< 150f` and `< 250f`), making the first unreachable. Likely a copy-paste bug.
2. **Rain probability type safety** (line 681): `(probability ?: 0) / 100f` — `Int?` divided by `100f` assumes percentage, but no documentation or type safety confirms it.
3. **Rain label suppression** (line 640): `drawNightRainLabel` exits early if `day.dailyRainLabelText != null`, silently suppressing night rain info.
4. **`effectiveHigh()` fallback** (lines 67-70, 502): `day.effectiveHigh() ?: day.high` fallback is mostly dead code since `maxOrNull()` returns non-null when any list element is non-null.

### Performance

5. **Paint allocation in draw loops** (lines 440-442, 477-481, 551): `Paint(paints.xxx).apply { color = condColor }` creates ~3N new Paint objects per render cycle.
6. **`createScaledRainPaint`** (lines 674-691): Allocates a new `Paint` per rain label per render.
7. **`dpToPx` in tight loops**: `TypedValue.applyDimension` called inside per-day loops; many values are constant across days.

### Readability

8. **Magic numbers**: `1.2f`, `0.7f`, `0.8f`, `75`, `80` (alphas), `0.5f`, `6f`, `3f`, `11f` scattered inline.
9. **`DayData` has 25+ fields**: Very wide data class; could group related fields.
10. **Repeated minBarHeight pattern** (3 copies): Same fallback geometry logic repeated with slight variations.
11. **Verbose debug logging in production**: `String.format("#%08X", ...)` runs on every bar draw even in release builds.

### Minor

12. **`effectiveHigh()` as private extension**: Only used once; logic of "which high to show" could be centralized.
13. **Unused `Job` import pattern**: Passing `Job?` for cancellation is unusual vs. using `CoroutineScope`.
14. **`formatTempLabel` parameter naming**: `allowDecimals` is misleading — it actually means "is actual past/today data."

---

## Phase 2: Implementation (Round 1 — Medium Priority)

User said: "implement, I like to logging, so try not to remove too much"

### Changes Made

1. **Fixed dead `heightScaleFactor` branch**: Changed `< 150f → 0.92f` (was `1.0f`, identical to second branch). Short widgets now actually scale down text size.

2. **Extracted `clampMinBarHeight` and `resolveBarEndpoints` helpers**:
   - `clampMinBarHeight(highY, lowY, minBarHeight)` — enforces minimum bar pixel height
   - `resolveBarEndpoints(highY?, lowY?, minBarHeight)` — handles null high/low and delegates to clampMinBarHeight
   - Replaced 3 copies of the minBarHeight fallback pattern

3. **Precomputed `minBarHeightPx` in `LayoutInfo`**: Added `minBarHeightPx` field computed once in `computeLayout` instead of calling `dpToPx()` per bar draw.

4. **Reduced Paint allocations**: Replaced `Paint(paints.xxx).apply { color = condColor }` with `paints.xxx.also { it.color = condColor }` — reuses cached Paint objects instead of copying. Safe because renderGraph is sequential and color is always set before drawing.

### Files Changed

- `DailyForecastGraphRenderer.kt`: All 4 changes above

### Test Results

All unit tests passed after changes.

---

## Phase 3: Implementation (Round 2 — Remaining Items)

User said "What's next?" and then "do it" for the remaining review items.

### Changes Made

1. **Extracted 20 magic numbers to named constants**:
   - `GHOST_BAR_ALPHA = 75`
   - `CLIMATE_OVERLAY_ALPHA = 80`
   - `BULB_RADIUS_SCALE = 1.2f`
   - `BULB_VERTICAL_CENTER_FRACTION = 0.5f`
   - `HISTORY_BAR_WIDTH_SCALE = 0.7f`
   - `FORECAST_OVERLAY_WIDTH_SCALE = 0.7f`
   - `CLIMATE_OVERLAY_WIDTH_SCALE = 0.8f`
   - `FORECAST_BAR_OFFSET_SCALE = 0.7f`
   - `PAST_TEMP_SCALE = 0.9f`
   - `HIGH_LABEL_OFFSET_DP = 6f`
   - `ICON_BELOW_BAR_SPACING_DP = 3f`
   - `TEMP_LABEL_SPACING_DP = 1f`
   - `RAIN_LABEL_SPACING_DP = 11f`
   - `RAIN_TEXT_MARGIN_DP = 4f`
   - `RAIN_LABEL_EDGE_MARGIN_DP = 2f`
   - `ICON_STACK_SPACING_DP = 4f`
   - `DAY_LABEL_BASE_SIZE_DP = 15f`
   - `ICON_BASE_SIZE_DP = 36f`
   - `RAIN_TEXT_SIZE_DP = 14.4f`
   - `DAY_LABEL_BOTTOM_MARGIN_PX = 3f`

2. **Added rain probability documentation**: KDoc on `dailyPrecipProbability` and `nighttimePrecipProbability` fields: "0–100 (percentage). Divided by 100 internally for font scaling."

3. **Renamed `formatTempLabel` parameter**: `allowDecimals` → `isActualData` to clarify semantics (today/past = actual data → allows sub-degree precision).

4. **Added section comments**: `Rain labels`, `Baseline resolvers`, `Utility` markers grouping related methods.

### Bug Fix During Round 2

Two `edit` calls collapsed lines together (missing newline after `formatTempLabel(...)`), causing syntax errors at lines 420 and 520. Fixed by re-inserting the line breaks.

### Test Results

Added `heightScaleFactor` bucket tests to `DailyForecastGraphRendererSizingTest`:
- `forecast temperature label size uses smaller scale for short widgets` — verifies `0.92f * 24dp = 22.08dp`
- `forecast temperature label size uses larger scale for tall widgets` — verifies `1.05f * 24dp = 25.2dp`

All unit tests passed.

---

## Phase 4: DayData RainData Decomposition (Option B — Full Migration)

### Discussion

User asked "What do you think about: DayData decomposition?" I recommended skipping it due to high churn (10+ test files, hundreds of call sites) with no runtime benefit.

User then asked "Can we start with 5. Then decide how to proceed after that?" — referring to group 5 (rain data fields) from my suggested groupings:
- `rainSummary, dailyPrecipProbability, nighttimePrecipProbability, dailyPrecipAmountMm, dailyRainLabelText, nightRainLabelText, hasRainForecast`

I proposed two options:
- **Option A**: Backward-compatible delegating accessors (zero call-site changes)
- **Option B**: Full migration (all call sites updated, no delegating accessors)

User chose **Option B**.

### Scope Assessment

An exploration agent mapped all call sites. Key findings:

1. The exploration agent initially misidentified some `DailyViewLogicTest.kt` assertions as `TextDayData` when they were actually `DayData` (from `prepareGraphDays()`, not `prepareTextDays()`).
2. The actual scope was:
   - **Production writes**: 1 site (`DailyViewLogic.kt` — 7 fields in DayData constructor)
   - **Production reads**: 7 (5 in `DailyForecastGraphRenderer.kt`, 2 in `DailyViewHandler.kt`)
   - **Test writes**: 6 sites across `DailyForecastGraphRendererRoboTest.kt` (13 constructions with rain fields)
   - **Test reads**: 23 in `DailyViewLogicTest.kt` (assertions on `.dailyRainLabelText`, `.nightRainLabelText`)
   - **`TextDayData`**: NOT affected — separate class with its own flat fields
   - **Instrumented tests**: 4 constructions, 0 rain field reads

### Changes Made

1. **Created `RainData` data class** inside `DailyForecastGraphRenderer`:
   ```kotlin
   data class RainData(
       val rainSummary: String? = null,
       val dailyPrecipProbability: Int? = null,
       val nighttimePrecipProbability: Int? = null,
       val dailyPrecipAmountMm: Float? = null,
       val dailyRainLabelText: String? = null,
       val nightRainLabelText: String? = null,
       val hasRainForecast: Boolean = false,
   )
   ```

2. **Modified `DayData`**: Replaced 7 flat fields with `val rainData: RainData = RainData()` (default empty, so all existing no-rain constructions still work without changes).

3. **Updated `DailyForecastGraphRenderer.kt`** (5 reads):
   - `day.dailyRainLabelText` → `day.rainData.dailyRainLabelText`
   - `day.dailyPrecipProbability` → `day.rainData.dailyPrecipProbability`
   - `day.nightRainLabelText` → `day.rainData.nightRainLabelText`
   - `day.nighttimePrecipProbability` → `day.rainData.nighttimePrecipProbability`

4. **Updated `DailyViewLogic.kt`** (1 construction site):
   - 7 flat params replaced with nested `RainData(rainSummary = ..., dailyPrecipProbability = ..., ...)` constructor

5. **Updated `DailyViewHandler.kt`** (2 reads):
   - `it.rainSummary` → `it.rainData.rainSummary`
   - `day.hasRainForecast` → `day.rainData.hasRainForecast`

6. **Updated `DailyForecastGraphRendererRoboTest.kt`** (6 constructions):
   - All rain-related flat params moved into `rainData = DailyForecastGraphRenderer.RainData(...)` parameter
   - Initially used unqualified `RainData(...)` which caused "Unresolved reference" errors — fixed by qualifying as `DailyForecastGraphRenderer.RainData(...)`

7. **Updated `DailyViewLogicTest.kt`** (23 assertions):
   - `.dailyRainLabelText` → `.rainData.dailyRainLabelText` (all occurrences)
   - `.nightRainLabelText` → `.rainData.nightRainLabelText` (all occurrences)

### Files Not Changed

- `DailyForecastGraphRendererTest.kt` — constructions use no rain fields (defaults suffice)
- `DailyForecastGraphRendererColumnCountTest.kt` — no rain fields
- `DailyGapFallbackGraphIntegrationTest.kt` — no rain fields
- `DailyForecastGraphRendererRobolectricTest.kt` — no rain fields
- `DailyViewHandlerTest.kt` — 3 constructions with no rain fields; rain assertions are on `TextDayData`, not `DayData`
- `DailyGraphTouchZoneAlignmentInstrumentedTest.kt` — no rain fields in constructions
- `DailyViewLogic.kt` `TextDayData` class — separate class, not affected

### Test Results

All unit tests passed after full migration. Both `compileDebugKotlin` and `compileDebugUnitTestKotlin` and `compileDebugAndroidTestKotlin` succeed with no errors.

---

## Summary of All Changes Across Session

| Change | Detail | Phase |
|--------|--------|-------|
| Bug fix | `heightScaleFactor` dead branch (`1.0f` → `0.92f`) | 2 |
| Extracted helpers | `clampMinBarHeight()` + `resolveBarEndpoints()` | 2 |
| Precomputed | `minBarHeightPx` in `LayoutInfo` | 2 |
| Reduced allocations | `Paint(paints.xxx).apply{}` → `paints.xxx.also{}` | 2 |
| Named constants | 20 inline values extracted | 3 |
| Rain probability docs | KDoc on `dailyPrecipProbability`, `nighttimePrecipProbability` | 3 |
| Parameter rename | `formatTempLabel(allowDecimals)` → `formatTempLabel(isActualData)` | 3 |
| Section comments | `Rain labels`, `Baseline resolvers`, `Utility` markers | 3 |
| HeightScaleFactor tests | Added 2 bucket tests for non-default values | 3 |
| RainData decomposition | 7 fields → nested `RainData` data class, full migration (Option B) | 4 |

### Files Modified

1. `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
2. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
3. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
4. `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt`
5. `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererSizingTest.kt`
6. `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt`

### Remaining Items (Not Implemented)

1. **`DayData` decomposition beyond group 5**: Groups 1–4 (`displayTemp`, `displayCondition`, `displayFlags`, `forecastOverlay`) still have flat fields. Could be done following the same Option B pattern.
2. **`createScaledRainPaint` allocation**: Still allocates a new `Paint` per rain label per render. Could be optimized with text-size override pattern.
3. **More `dpToPx` precomputation**: Some constant-per-render values still call `dpToPx` inside loops (e.g., rain label spacing).
4. **Debug logging in release builds**: Could gate behind `BuildConfig.DEBUG` or `Log.isLoggable`.
5. **File size reduction**: Renderer is ~790 lines. Could extract rain label logic into separate class.
