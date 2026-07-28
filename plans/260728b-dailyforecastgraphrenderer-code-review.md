# Code Review: DailyForecastGraphRenderer.kt (Priority 1, file 4)

Source: `plans/260725-code-review-queue.md` (score 10/12)
Reviewed: 2026-07-28
Implemented: 2026-07-28 (all findings through split B; F11 intentionally deferred)
File: `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` (1397 lines)

## Overall Assessment

Highest branch count in Priority 1 (171). Size and branch density are driven by
two clusters:
1. `drawDayBars` + `drawTodayTripleBar` + `drawWeatherAdaptiveBar` (~190 lines)
2. `resolveHighLabelPlan` + `resolveHighLabelBaseline` + `resolveHighLabelDrawScale`
   + `resolveLowLabelBaseline` (~110 lines)

Everything else is well-factored: paint management is LRU-cached, layout calc is
pure, debug callbacks are comprehensive (BarDrawnDebug, RainLabelDrawnDebug,
HeaderDrawnDebug, DayLabelDrawnDebug), and shared logic lives in `:shared`
(DualHighLabel, TodayColumnHighlight, DailyDayValueResolver).

Excellent inline rationale — every non-obvious decision has a "why" comment
with concrete past-bug references (e.g. the "yesterday 15%" report).

Test coverage: 5 test files (RoboTest, SizingTest, Test, ColumnCountTest,
RobolectricTest).

## Findings

### F1 — `suppressHeaderDateForRainOverlap` recomputes rain label placement that drawDayColumn will redo [HIGH]

`:396-433` calls `DailyForecastRainLabelRenderer.resolveDailyRainLabelPlacement`
per day BEFORE `drawDayColumn` (`:689`) draws the actual rain labels via the
same resolver at `:774`. Two text-measure + geometry passes per day.

**Fix:** Defer the overlap check until after `drawDayColumn` draws the rain
label (it already emits a `RainLabelDrawnDebug`), or cache the resolved
placement on a per-day map shared between the two calls.

### F2 — `computeLayout` 7N-allocation temp range scan [HIGH]

`:458-461` flatMaps 7 fields per day into a List, then walks it twice (min +
max). renderGraph runs per-frame during navigation/zoom, N up to ~14 days →
~98 allocations + 2 list walks per frame.

**Fix:** Single-pass running min/max with no allocations:

```kotlin
var minTemp = Float.POSITIVE_INFINITY
var maxTemp = Float.NEGATIVE_INFINITY
for (d in days) {
    for (t in listOfNotNull(d.solidLineHigh, d.solidLineLow, ...)) {
        if (t < minTemp) minTemp = t
        if (t > maxTemp) maxTemp = t
    }
}
```

### F3 — Magic `(2f).dp(...)` column-edge margin repeated 3× [MED]

`:404, 519, 809` (and one more) all use `(2f).dp(layout.density)` as the
"column edge margin" for triple-bar spacing / panel bounds / header-rain
overlap padding.

**Fix:** Add `private const val COLUMN_EDGE_MARGIN_DP = 2f` and reference.

### F4 — `drawTempLabel` has TWO KDoc blocks back-to-back, second orphaned [MED]

`:957-968` — KDoc for `drawTempLabel` immediately followed by KDoc for
`fitScaleForWidth`, but `drawTempLabel`'s signature sits between. Copy-paste
error: the `fitScaleForWidth` KDoc ended up above the wrong function.

**Fix:** Move the orphaned KDoc to its actual function (`fitScaleForWidth`
lives at `:969`).

### F5 — `DayData.effectiveHigh()` recomputed multiple times per render [MED]

`:208-215` is called at least 3× per day in the render path:
- `drawDayBars :935`
- `resolveHighLabelPlan :1151`
- `resolveHighLabelDrawScale :1234`

Each call re-evaluates the today-cutoff logic.

**Fix:** Compute once into a local at the top of `drawDayBars`, or cache
lazily on a per-render wrapper.

### F6 — `drawWeatherIcon` inline-tints with same predicate→color pattern [MED]

`:785-788` — third call site for icon-tint-by-predicates (file 1:
DailyTextRenderer, file 3: drawHourLabelsAndIcons, this file: drawWeatherIcon).
Each uses a different palette.

**Fix:** Add inline comment documenting the intentional divergence (cell vs
hourly-on-graph vs daily-bar) — different backgrounds want different palettes.
Don't force consolidation; the contexts are genuinely different.

### F7 — `resolveHighLabelPlan` uses `offsets!!` after a null-check [MED]

`:1180` — `offsets!!.forecastDp` inside `forecastHigh?.let { ... }`. The
compiler can't see that `offsets` is non-null iff `forecastHigh` is non-null
(they're both derived from the same source).

**Fix:** Use `offsets?.let { offsetPx(it.forecastDp) }` inside the
`forecastHigh?.let`, or hoist into a single `if (forecastHigh != null &&
offsets != null)` block.

### F8 — `drawTodayHighlightPanel` allocates Paint per render [LOW]

`:815-818` — `Paint(...)` constructed once per render (today only). PaintSet
already exists for cached paints.

**Fix:** Hoist `todayPanelFillPaint` into PaintSet.

### F9 — `formatTempLabel` mixes two formatting paths [LOW]

`:1392-1396` — `forceInteger` branch builds `"${roundToInt()}°"` manually;
non-integer branch delegates to `TempUtils.formatTemp`. The `displayVal`
computed at the top is only used by the integer branch (formatTemp does its
own conversion).

**Fix:** Document why two paths, or add `forceInteger` to `formatTemp`.

### F10 — `paintCaches: List<PaintCache>` non-atomic RMW on `@Volatile` [LOW]

`:610` — read-modify-write on `@Volatile var`. Volatile guarantees visibility
not atomicity; two concurrent renders can both miss and clobber. Acceptable
since the loser's PaintSet is still returned to its own caller (work not
wasted, cache briefly wrong).

**Fix:** Comment to acknowledge the race; concurrent renders are rare.

### F11 — `drawWeatherAdaptiveBar` allocates 2 Paint copies per call [LOW]

`:664-671` — `Paint(paint)` for top and bottom segments, per adaptive bar per
render. ~28 Paint allocations across 14 mixed-condition days.

**Fix (optional):** Cache by color key, similar to PaintSet.barByColor.
Defer unless profiled.

### F12 — Three independent scales all equal 0.7 [LOW]

`:76-78` — `HISTORY_BAR_WIDTH_SCALE`, `FORECAST_OVERLAY_WIDTH_SCALE`,
`FORECAST_BAR_OFFSET_SCALE` all = 0.7. Either coincidence or drift waiting
to happen.

**Fix:** Comment they're independently tunable and 0.7 happens to suit all
three (or dedupe to one `BAR_WIDTH_SCALE` if they're meant to track).

## Split

Two extraction candidates mirror the file-1 pattern:

### A. `DailyHighLabelPlanner.kt` (extract)
- `HighLabelPlan` data class
- `resolveHighLabelPlan(day, layout, paints)`
- `resolveHighLabelBaseline`
- `resolveHighLabelDrawScale`
- `resolveLowLabelBaseline`
- `HIGH_LABEL_OFFSET_DP` constant (only used here)

Surface: takes `DayData`, `LayoutInfo`, `PaintSet`; uses
`formatTempLabel`, `tempLabelDrawScale`, `measureTextWidth`,
`DayData.effectiveHigh()`, `LayoutInfo.tempToY`, `.dp(density)`. The
shared helpers become `internal` on `DailyForecastGraphRenderer` (or a
small `DailyGraphInternals` object) so the planner can call them.

### B. `DailyBarRenderer.kt` (extract)
- `drawDayBars`
- `drawTodayTripleBar`
- `drawWeatherAdaptiveBar`
- `shouldUseAdaptiveSegments`
- `drawTodayHighlightPanel`
- `clampMinBarHeight`, `resolveBarEndpoints`
- Bar-width constants (`FORECAST_BAR_WIDTH_DP`, `TODAY_TRIPLE_BAR_WIDTH_DP`,
  `HISTORY_BAR_WIDTH_SCALE`, `FORECAST_OVERLAY_WIDTH_SCALE`,
  `FORECAST_BAR_OFFSET_SCALE`, `CLIMATE_OVERLAY_WIDTH_SCALE`,
  `CLIMATE_OVERLAY_ALPHA`, `GHOST_BAR_ALPHA`, `BULB_RADIUS_SCALE`,
  `BULB_VERTICAL_CENTER_FRACTION`)
- Color constants used only by bars (`COLOR_FORECAST`, `COLOR_OBSERVED_RED`,
  `COLOR_TODAY_HIGHLIGHT`, `COLOR_GAP_FALLBACK`)

Calls into `DailyHighLabelPlanner.resolveHighLabelPlan` (extracted A).

### Stay in `DailyForecastGraphRenderer.kt`
- `renderGraph` entry point
- `computeLayout` + `LayoutInfo`
- `getPaintSet` + `PaintSet` + `PaintCache` + LRU
- `suppressHeaderDateForRainOverlap` + `hasMeaningfulHeaderRainOverlap`
- `drawDayColumn` (orchestrates bars + icon + label + rain)
- `drawWeatherIcon`
- `formatTempLabel` + measure helpers
- `resolveDayLabelLayout` + `fittingScale` + `dayLabelMeasurePaint`
- All data classes (`DayData`, `RainData`, `HeaderRenderData`, debug classes)
- `drawTempLabel`, `tempLabelDrawScale`, `fitScaleForWidth`

Expected size after split: ~750 / ~250 / ~400 (main / planner / bar-renderer).

## Implementation Order

1. F4 (trivial) — move orphaned KDoc
2. F12 (trivial) — comment on 0.7 scales
3. F3 (trivial) — name COLUMN_EDGE_MARGIN_DP
4. F10 (trivial) — comment on race
5. F9 — document formatTempLabel dual paths
6. F6 — document drawWeatherIcon tint divergence
7. F7 — replace `!!` with safe access
8. F8 — hoist todayPanelFillPaint into PaintSet
9. F2 (perf) — single-pass temp range
10. F5 — cache effectiveHigh per day
11. F1 (perf) — defer overlap check until after drawDayColumn
12. F11 — defer per the plan
13. Split A — extract DailyHighLabelPlanner
14. Split B — extract DailyBarRenderer

## Verification

* `:app:compileDebugKotlin` + `:app:compileDebugUnitTestKotlin`
* `:app:testLongDebugUnitTest --tests "com.weatherwidget.widget.DailyForecastGraph*"`
* `:app:testLongDebugUnitTest --tests "com.weatherwidget.widget.handlers.*"`
  (DailyGraphRenderer in handlers package uses these symbols)
* `:app:testShortDebugUnitTest`

## Implementation Result

Completed both planned extractions:

1. `DailyHighLabelPlanner.kt` owns high-label planning and the effective-high helper.
2. `DailyBarRenderer.kt` owns daily bar drawing, today-panel/triple-bar drawing, adaptive segments,
   bar geometry helpers, and bar-specific constants/colors.

`DailyForecastGraphRenderer` remains the entry point and retains layout, paint caching, column/icon/
rain/day-label orchestration, formatting, and shared text-measurement helpers. Its existing
test-facing bar-width functions remain as compatibility delegates to `DailyBarRenderer`.

Verification completed 2026-07-28:

1. `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin` — passed.
2. `:app:testLongDebugUnitTest --tests "com.weatherwidget.widget.DailyForecastGraph*" --tests
   "com.weatherwidget.widget.handlers.*" :app:testShortDebugUnitTest` — passed.
3. `:app:assembleDebug` and debug APK install on `emulator-5554` (Google API 36) — passed.
4. Runtime refresh of widget 52 — logs confirmed a 10-column daily render through history,
   today snapshot/forecast, and future/adaptive bar paths, followed by a launcher widget push.
5. Emulator screenshot — visually confirmed the daily graph, highlighted today triple bar,
   thermometer bulb, history/forecast overlays, future bars, icons, and temperature labels.
