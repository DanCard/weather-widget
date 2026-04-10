# Session: Precipitation Graph Rainfall Amount Annotation

**Date:** 2026-04-10
**Goal:** Display rainfall amount text on the precipitation graph when rain chance is 99% or higher
**Scope:** NWS API data first

---

## Prompt 1: Design Discussion

> On the rain chance graph, I'm wondering how to display amount of rainfall? Lets focus on nws api first. Any thoughts how to add that info when rain chance is 99% or higher? I'm thinking just dump that info on the screen: something like: .44 inches between 8 am and noon, for example.

### Exploration

Launched Explore agent to trace the full precipitation data pipeline:

1. **Renderer:** `PrecipitationGraphRenderer.kt` renders a smooth curve of hourly precipitation probability (0-100%). Uses `PrecipHourData` data class — had `precipProbability: Int` but NOT `precipAmountMm`.

2. **Database:** `HourlyForecastEntity.kt` already stores `precipAmountMm: Float?` — populated from NWS grid QPF endpoint (`quantitativePrecipitation`).

3. **Data flow gap:** `PrecipViewHandler.buildPrecipHourDataList()` builds `PrecipHourData` from `HourlyForecastEntity` but only passes `precipProbability`, ignoring `precipAmountMm`.

4. **Existing formatting:** `DailyViewLogic.kt` already had locale-aware formatting:
   - `formatPrecipAmount(amountMm)` — dispatches to inches (US/GB) or mm
   - `formatInches()` — smart precision: 3dp < 0.1in, 2dp < 1in, 1dp >= 1in
   - `formatMillimeters()` — 1dp < 10mm, 0dp >= 10mm
   - `buildDailyRainLabel()` — already shows amount when prob >= 99%

**Key finding:** The heavy lifting was already done. Data flows from NWS API -> repository -> database. Only the last mile (graph rendering) was missing.

### Plan (entered plan mode)

Designed implementation in 4 steps:
1. Extract formatting functions from `DailyViewLogic` to shared `WidgetFormatUtils.kt`
2. Add `precipAmountMm` field to `PrecipHourData`
3. Pass `precipAmountMm` through in `PrecipViewHandler`
4. Aggregate 99%+ rain periods and render text annotations on graph

---

## Prompt 2: (Plan approved — implementation)

### Implementation

#### Step 1: Extract formatting to shared utility

**`WidgetFormatUtils.kt`** — Added 3 `internal` functions:
- `formatPrecipAmount(amountMm: Float): String`
- `formatInches(amountInches: Float): String` (private)
- `formatMillimeters(amountMm: Float): String` (private)

**`DailyViewLogic.kt`** — Removed 3 private copies (29 lines deleted). Existing call at line 409 resolves automatically since both files are in the same package.

#### Step 2: Add `precipAmountMm` to PrecipHourData

**`PrecipitationGraphRenderer.kt`** line 33 — Added nullable field with default:
```kotlin
val precipAmountMm: Float? = null,
```

#### Step 3: Pass through from PrecipViewHandler

**`PrecipViewHandler.kt`** line 629 — One line added:
```kotlin
precipAmountMm = forecast.precipAmountMm,
```

#### Step 4: Aggregate and render rain amount text

**`PrecipitationGraphRenderer.kt`** — Added:

**`RainPeriod` data class:**
```kotlin
private data class RainPeriod(
    val startIndex: Int,
    val endIndex: Int,
    val totalAmountMm: Float,
    val startLabel: String,
    val endLabel: String,
)
```

**`findHighProbRainPeriods()`** — Walks hours list finding contiguous blocks where `precipProbability >= 99`, sums `precipAmountMm` across each block, only emits periods where total > 0.

**Rendering code** (inserted after percent labels, before day-of-week indicators):
- Formats text as `".44in 8a-12p"` (amount + time range), or just amount for single-hour blocks
- Positions horizontally centered over the period
- Positions vertically midway between average curve Y and graphBottom (in the gradient fill area)
- Uses bold white 10dp text with shadow for readability
- Participates in `drawnLabelBounds` collision detection
- Logs placement via `Log.d` AND `onDebugLog` callback for testability

### Files Changed (4)

| File | Lines | Change |
|------|-------|--------|
| `WidgetFormatUtils.kt` | +31 | Added shared formatting functions |
| `DailyViewLogic.kt` | -29 | Removed private formatting copies |
| `PrecipViewHandler.kt` | +1 | Pass `precipAmountMm` through |
| `PrecipitationGraphRenderer.kt` | +85 | `PrecipHourData` field, `RainPeriod`, `findHighProbRainPeriods()`, rendering code |

### Verification

- `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL
- `./gradlew testDebugUnitTest` — All tests pass (no regressions)

---

## Prompt 3: Test Plan

> write an automated test plan for this

### Exploration

Launched Explore agent to survey existing test patterns:

- **PrecipitationGraphRendererTest.kt** — JUnit4 + MockK, mocks Bitmap/Canvas/Paint, uses `onLabelPlaced` callback to assert on placement decisions without pixel assertions
- **PrecipViewHandlerTest.kt** — Plain JUnit4, tests `buildPrecipHourDataList()` with `HourlyForecastEntity` lists
- **DailyViewLogicTest.kt** — Robolectric, tests `prepareGraphDays()` end-to-end including rain labels
- **No existing tests** for `formatPrecipAmount`, `formatInches`, `formatMillimeters`
- **Duration categories:** `ShortDuration`, `MediumDuration`, `LongDuration` — all tests must declare one

### Test Plan Design

Three-layer approach mirroring the code changes:

1. **WidgetFormatUtilsTest** (8 tests) — Pure formatting, locale toggling
2. **PrecipViewHandlerTest** (2 tests) — Data pipeline passthrough
3. **PrecipitationGraphRendererTest** (6 tests) — Rendering logic via `onDebugLog` callback

---

## Prompt 4: Implement Tests

> yes

### Implementation

#### Production code fix first

`PrecipitationGraphRenderer.kt` — The `rainAmountPlaced`/`rainAmountSkipped` log messages were only going through `Log.d()`, not the `onDebugLog` callback. Added `onDebugLog?.invoke(logMsg)` for both paths so tests can observe rendering decisions.

#### WidgetFormatUtilsTest.kt (NEW — 8 tests)

**Category:** `ShortDuration`
**Framework:** Plain JUnit4 with `Locale.setDefault()` in `@Before`/`@After`

| Test | Input | Expected |
|------|-------|----------|
| US locale returns inches | 25.4mm | `"1in"` |
| tiny amount uses 3dp | 0.508mm | `".02in"` |
| sub-inch uses 2dp | 11.176mm | `".44in"` |
| large amount uses 1dp | 50.8mm | `"2in"` |
| fractional large amount | 38.1mm | `"1.5in"` |
| metric locale returns mm | 25.4mm | `"25mm"` (0dp for >=10) |
| small mm uses 1dp | 2.5mm | `"2.5mm"` |
| large mm rounds to int | 25.0mm | `"25mm"` |

**Bug caught during testing:** Initially expected `"25.4mm"` for 25.4mm input, but `formatMillimeters` uses 0 decimal places for values >=10mm. Fixed test expectation to `"25mm"`.

#### PrecipViewHandlerTest.kt (EXTENDED — 2 new tests)

**Updated `hourly()` helper** to accept optional `precipAmountMm` parameter (default `null`).

| Test | Assertion |
|------|-----------|
| passes precipAmountMm through | Entity with 3.5f -> PrecipHourData has 3.5f |
| null precipAmountMm passes through | Entity with null -> all PrecipHourData have null |

#### PrecipitationGraphRendererTest.kt (EXTENDED — 6 new tests)

**Refactored:** Extracted MockK boilerplate into `@Before`/`@After` (was duplicated in each test). Uses `onDebugLog` callback to capture `"rainAmountPlaced"` / `"rainAmountSkipped"` messages.

| Test | Signal | Expected |
|------|--------|----------|
| shows rain amount for 99+ block | 12h at 100%, 1mm/h | `rainAmountPlaced` logged |
| skips when below 99% | 12h at 98%, 2mm/h | No `rainAmountPlaced` |
| skips when precipAmountMm null | 12h at 100%, null | No `rainAmountPlaced` |
| skips when total is zero | 12h at 100%, 0mm | No `rainAmountPlaced` |
| handles two separate blocks | `[100x3, 50x2, 100x3]` | Two `rainAmountPlaced` |
| single hour omits time range | `[0,0,100,0,0]` | Label has no dash |

### Test Results

```
39 tests total (16 new + 23 existing)

WidgetFormatUtilsTest:          8/8  PASSED
PrecipViewHandlerTest:          4/4  PASSED  (2 new + 2 existing)
PrecipitationGraphRendererTest: 9/9  PASSED  (6 new + 3 existing)
DailyViewLogicTest:            18/18 PASSED  (all existing, regression check)

Full suite: BUILD SUCCESSFUL
```

### Files Created/Modified for Tests

| File | Action |
|------|--------|
| `app/src/test/.../handlers/WidgetFormatUtilsTest.kt` | CREATED (8 tests) |
| `app/src/test/.../handlers/PrecipViewHandlerTest.kt` | EXTENDED (2 tests + helper update) |
| `app/src/test/.../PrecipitationGraphRendererTest.kt` | EXTENDED (6 tests + refactored setup) |
| `app/src/main/.../PrecipitationGraphRenderer.kt` | Added `onDebugLog` for rain amount logs |

---

## Summary of All Changes

### Production Code (5 files)

| File | Change |
|------|--------|
| `WidgetFormatUtils.kt` | +31 lines — shared formatting functions |
| `DailyViewLogic.kt` | -29 lines — removed private copies, delegates to shared |
| `PrecipViewHandler.kt` | +1 line — pass `precipAmountMm` through |
| `PrecipitationGraphRenderer.kt` | +87 lines — data field, period detection, rendering, debug callbacks |
| `HourlyForecastEntity.kt` | No change (field already existed) |

### Test Code (3 files)

| File | Change |
|------|--------|
| `WidgetFormatUtilsTest.kt` | NEW — 8 formatting tests |
| `PrecipViewHandlerTest.kt` | +2 tests, updated helper |
| `PrecipitationGraphRendererTest.kt` | +6 tests, refactored mock setup |

### Key Design Decisions

1. **99% threshold** — Matches existing `DailyViewLogic.buildDailyRainLabel()` behavior
2. **Contiguous block aggregation** — Sums hourly amounts across consecutive 99%+ hours, shows time range
3. **Collision avoidance** — Participates in existing `drawnLabelBounds` system
4. **Locale-aware** — Inches for US/GB, millimeters elsewhere (reuses existing formatting)
5. **Graceful degradation** — Skips annotation if amount is null/zero or label overlaps
