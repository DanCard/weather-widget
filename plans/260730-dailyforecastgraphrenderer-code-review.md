# Code Review: `DailyForecastGraphRenderer.kt`

Reviewed: 2026-07-30

Status: review complete; implementation complete and verified

Scope: correctness, maintainability, and structure of
`app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`, including the
immediately coupled daily graph renderers where ownership affects the facade.

## Review boundary

This review began read-only, with this plan as its only repository change. The user subsequently
approved implementation. F1-F6 and the required structural extractions below are now implemented;
none were deferred.

The worktree was clean at review start (`main...origin/main`, `HEAD 7dc59b3f`).

## Implementation outcome

1. F1: `DailyGraphInputNormalizer` now normalizes every renderer temperature slot before layout or
   drawing, resolves columns once, keeps the first duplicate deterministically, and reports sparse
   rejection/clamp/collision diagnostics.
2. F2: rain output is now a typed `DailyRainLabelPlacement` with `RainLabelKind.DAY`/`NIGHT`, and
   `DailyGraphRenderResult` carries placements to the handler. Header collision uses only typed
   daytime placements; `NightRainGridMapper` accepts only typed nighttime placements.
3. F3: bar drawing, right-neighbor lookup, header overlap, and click mapping all use the same
   normalized resolved-column topology.
4. F4: layout, paints, temperature labels, and per-column rendering were extracted to
   `DailyGraphLayoutResolver`, `DailyGraphPaintCache`, `DailyTemperatureLabelRenderer`, and
   `DailyColumnRenderer`. The ordered facade is 348 lines, with a 500-line architecture cap and
   forbidden-helper/callback dependency checks.
5. F5: renderer-facing rain input is now the minimal `RainLabelData`; handler-owned
   `PreparedGraphDay` retains `rainSummary` and `hasRainForecast`, and the handler explicitly maps
   prepared inputs into renderer inputs.
6. F6: render/per-bar traces use gated VERBOSE logging, and temperature labels use the shared
   formatter without the Android DEBUG wrapper.

The three primary regressions for non-finite input, equal day/night label text, and clamped-neighbor
topology were first confirmed to fail against the pre-fix source and pass after implementation.

## Current assessment

The 2026-07-28 split was valuable and should be preserved:

1. `DailyBarRenderer` owns bar drawing and today-column bar geometry.
2. `DailyHighLabelPlanner` owns the dual-high plan.
3. `DailyForecastRainLabelRenderer` owns rain-label placement.

That prior work removed the densest bar and high-label branches from the original 1,397-line
renderer. The current facade is still 1,060 lines, however, and has accumulated six cohesive
responsibilities:

1. Public render/debug data contracts (`:75-311`).
2. Render ordering and header-overlap orchestration (`:313-476`).
3. Axis and layout resolution (`:478-623`).
4. Paint construction and caching (`:625-725`).
5. Per-column icon/low/day/rain rendering (`:727-837`).
6. Temperature-label drawing, formatting, measurement, and day-label fitting (`:839-1059`).

More importantly, the current rendering boundary is not total: it detects invalid temperatures
without removing them from downstream drawing, and it uses a debug callback as production geometry
state. Those are live correctness findings, not just class-size concerns.

## Prior-plan reconciliation

`plans/260728b-dailyforecastgraphrenderer-code-review.md` was checked before this review.
Its F1-F10/F12 work and split A/B are present in the current source and are not repeated as stale
findings. The later sentinel/axis hardening in commit `3defa81e` is also present.

The old F11 (`Paint` copies inside `DailyBarRenderer.drawWeatherAdaptiveBar`) remains in the
already-extracted collaborator and was explicitly deferred by the prior plan. This review does not
promote it: there is no new correctness evidence or profile showing it is material, and it is not a
structural recommendation about the named facade.

## Findings

### F1 — Non-finite temperatures are rejected from the axis but still reach drawing and label formatting [HIGH]

Evidence:

1. `computeLayout` rejects every value for which `isPlausibleF` is false while accumulating the
   axis (`:489-524`), but it never normalizes the original `DayData`.
2. The original values are subsequently read by `DailyBarRenderer` and `drawDayColumn`. A non-null
   `solidLineHigh` enters high-label drawing; a non-null `solidLineLow`/`bottomStackLow` enters
   low-label drawing.
3. `formatTempLabel` (`:1055-1058`) delegates to `TempUtils.formatTemp`, whose shared implementation
   calls `Float.roundToInt()`. The existing
   `shared/.../TemperatureExtremaNaNTest` documents and guards the known failure mode:
   `roundToInt(Float.NaN)` throws `IllegalArgumentException` and aborts a graph render.
4. `LayoutInfo.tempToY` clamps finite outliers but does not turn `NaN` into a finite coordinate.
5. `bottomStackLow` is not one of the seven fields scanned by `computeLayout`, so it can bypass even
   the warning breadcrumb and still reach formatting.

Impact:

A non-finite value from actuals, the current-temperature path, a derived today value, a test
fixture, or a future data source can abort the entire daily render. The last widget bitmap then
remains visible or the placeholder persists. The DAO read guard reduces the chance for forecast
rows, but it is not a renderer-boundary guarantee and does not cover every producer of `DayData`.

Required fix:

1. Introduce a render-boundary `DailyGraphInputNormalizer`.
2. Before layout or drawing, normalize all renderer temperature slots:
   `solidLineHigh`, `solidLineLow`, `bottomStackLow`, `dashedLineHigh`, `dashedLineLow`,
   `snapshotHigh`, `snapshotLow`, and `ghostLineHigh`.
3. Convert non-finite/physically implausible values to `null` once, then use the normalized days for
   the axis, bars, labels, icons, rain anchors, and header-overlap work.
4. Emit one sparse warning per render containing the rejected count plus the first date/field/value;
   do not emit a per-value or per-frame DEBUG trace.
5. Keep `tempToY` clamping as defense in depth for finite containment.

Required regressions:

1. A daily render with `Float.NaN` in each label-bearing field returns a bitmap and does not throw.
2. `NaN`/positive infinity/negative infinity in overlay-only fields neither alter other columns nor
   produce non-finite bar debug coordinates.
3. An invalid `bottomStackLow` falls back to a valid low and does not enter `formatTempLabel`.
4. A valid legitimately cold value within the existing plausibility policy remains renderable.

### F2 — The daytime rain-label cache is ambiguous when day and night labels have the same text [HIGH]

Evidence:

1. `renderGraph` caches the daytime rain-label rectangle for later header collision detection
   (`:353-370`).
2. It identifies a daytime event by matching `date` and `text` (`:358-364`).
3. Day and night rain labels are drawn in that order (`:813-814`) through the same callback.
4. `RainLabelDrawnDebug` already contains `isNightLabel`, and
   `DailyForecastRainLabelRenderer` sets it to `false` for day and `true` for night.
5. Equal day/night percentages are legitimate (for example, both can be `"30%"`). In that case the
   later night event overwrites the actual daytime rectangle in `drawnDailyRainLabelByDate`.
6. `suppressHeaderDateForRainOverlap` then evaluates night-label geometry as if it were the daytime
   label (`:437-459`). This can leave the header date drawn over a daytime label or suppress the date
   because of the wrong label.

Impact:

Header collision behavior becomes data-dependent on whether two semantically different labels
happen to have identical display text. This is a visible correctness bug in a common probability
shape, and the existing `nightRainLabelDoesNotSuppressHeaderDate` test does not cover the equal-text
case.

Required fix:

1. Replace the debug-shaped production contract with a typed `DailyRainLabelPlacement` containing a
   `kind: DAY | NIGHT`, date, text, bounds, baseline, and anchor data.
2. Make `DailyForecastRainLabelRenderer` produce/draw that typed placement.
3. Collect daytime placements by `kind == DAY`; never infer identity from display text.
4. Return production placements in a `DailyGraphRenderResult(bitmap, rainLabelPlacements)` (or an
   equivalently typed non-debug result) so `NightRainGridMapper` no longer depends on a callback
   named `RainLabelDrawnDebug`.
5. Keep an optional debug callback only as an observer of the typed placement if tests still benefit
   from it.

Required regressions:

1. Equal day/night text with a daytime/header overlap suppresses the date based on the daytime
   bounds.
2. Equal day/night text with only the night label near the header does not suppress the date.
3. `NightRainGridMapper` receives only typed night placements and retains its current click-zone
   geometry.
4. Draw order remains bars, column labels/rain, header, then error watermark.

### F3 — Column indices are normalized for drawing but not for neighbor topology [MEDIUM]

Evidence:

1. `daysByColumn` is built from raw `columnIndex` values (`:351`).
2. Each day is later clamped into `[0, columns - 1]` for drawing (`:372-379`).
3. The right neighbor is looked up using the clamped current column against the raw-index map
   (`:380`).
4. The existing test explicitly treats out-of-range indices as supported input and asserts that
   they are clamped, so this is not merely an unreachable precondition.
5. If a day requested at column 99 is drawn at column 4, a day drawn at column 3 cannot find it as
   its right neighbor. Night-rain interstitial placement then uses the wrong low-label topology.
6. Multiple raw indices can also clamp into the same column; the map keeps one while the loop draws
   all of them on top of each other.

Impact:

Defensive clamping keeps bars on-canvas while leaving rain-label geometry inconsistent with what
was actually drawn. Duplicate/clamped columns can also overdraw each other.

Required fix:

1. Make `DailyGraphInputNormalizer` produce a `NormalizedDay(day, resolvedColumn)` list once.
2. Build all center positions, right-neighbor lookup, header overlap, and draw iteration from that
   resolved list.
3. Resolve duplicate columns deterministically: retain the first normalized day for the column,
   skip later collisions, and log one sparse warning with both dates/indices. Do not draw two days
   into one column.
4. Remove repeated raw-index/clamp calculations from `renderGraph` and
   `suppressHeaderDateForRainOverlap`.

Required regressions:

1. A day clamped into column 4 is the right neighbor of the day in column 3.
2. Two days resolving to the same column produce one drawn column and one warning path.
3. Sparse valid indices preserve existing center positions and missing-column spacing.
4. Production sequential indices retain numerically identical bar/debug geometry.

### F4 — The prior split left implementation dependencies pointing back into the facade [HIGH, STRUCTURAL]

Evidence:

1. The facade is still 1,060 lines after the earlier bar/high-label extraction.
2. `DailyBarRenderer` calls back into
   `DailyForecastGraphRenderer.drawTempLabel`/`formatTempLabel`.
3. `DailyHighLabelPlanner` calls back into
   `DailyForecastGraphRenderer.formatTempLabel`/`tempLabelDrawScale`.
4. `DailyForecastRainLabelRenderer` calls back into
   `DailyForecastGraphRenderer.resolveLowLabelBaseline` and reads facade constants/types.
5. Header, bar, high-label, and rain collaborators all import nested facade contracts.
6. Paint caching, layout, column content, and shared text rendering remain independently cohesive
   units inside the facade.

Impact:

The extracted classes cannot evolve or be tested as self-contained collaborators: fixes routinely
require widening `internal` methods on the facade. The facade remains the namespace, service
locator, paint factory, layout calculator, and ordered renderer, which makes further regression
work more likely to grow it again.

Required extraction:

1. `DailyGraphLayoutResolver.kt`
   - Own `LayoutInfo`, axis/range accumulation, dimension resolution, day-label layout/fitting, and
     sizing math.
   - Take primitive inputs (`density`, dimensions, scale, normalized days) rather than `Context`.
   - Keep cancellation checks in the facade before/after the resolver; layout math itself should be
     deterministic.
2. `DailyGraphPaintCache.kt`
   - Own `PaintCache`, `PaintSet`, paint factories, color caches, and the bounded LRU.
   - Use an explicit immutable key containing every paint-affecting input rather than three
     coincidentally derived floats.
   - Preserve render-local copies for every mutable paint operation.
3. `DailyTemperatureLabelRenderer.kt`
   - Own `drawTempLabel`, `formatTempLabel`, `tempLabelDrawScale`, `fitScaleForWidth`, font
     measurement fallback, and label-only constants.
   - Become the dependency used by `DailyBarRenderer`, `DailyHighLabelPlanner`,
     `DailyForecastRainLabelRenderer`, and the column renderer; those collaborators must no longer
     call implementation helpers on the facade.
4. `DailyColumnRenderer.kt`
   - Own day-label drawing, weather-icon drawing, low-stack resolution/bounds, and delegation to the
     typed rain-label renderer.
   - Accept the already normalized day, resolved right neighbor, layout, and paints.
5. Keep `DailyForecastGraphRenderer` as the ordered facade:
   - cancellation and bitmap/canvas creation;
   - input normalization;
   - layout/paint acquisition;
   - ordered per-column panel/bar/content calls;
   - typed header-overlap decision and header draw;
   - final watermark;
   - return `DailyGraphRenderResult`.

Required architecture guard:

Add `DailyForecastGraphRendererArchitectureTest`, following
`TemperatureGraphRendererArchitectureTest`, which:

1. Caps the completed facade at 500 lines or fewer.
2. Requires delegation through the four collaborators above.
3. Forbids reacquiring `computeLayout`, `getPaintSet`, `drawDayColumn`,
   `drawWeatherIcon`, `drawTempLabel`, and `formatTempLabel` implementation methods.
4. Verifies bar/high/rain collaborators no longer call implementation methods on
   `DailyForecastGraphRenderer`.

### F5 — `RainData` mixes render inputs, caller-owned state, and a dead field [MEDIUM]

Evidence:

1. `RainData.dailyPrecipAmountMm` (`:144`) has no read anywhere in the Kotlin source tree.
2. `rainSummary` is not rendered by this renderer; `DailyGraphRenderer` reads it before render to
   update “rain shown” state.
3. `hasRainForecast` is not rendered; `DailyGraphRenderer` reads it only for diagnostic logging.
4. The actual rain renderer needs only the two probabilities and two already-formatted label
   strings.

Impact:

The render input contract advertises data the renderer does not own, obscures what can affect
pixels, and encourages unrelated orchestration state to accumulate in the facade's nested models.

Required fix:

1. Delete the unused `dailyPrecipAmountMm`.
2. Replace renderer-facing `RainData` with `RainLabelData` containing only day/night probability and
   label text.
3. Keep `rainSummary`/`hasRainForecast` in the handler's prepared-day model or separate metadata
   used before rendering.
4. Map prepared days to renderer days explicitly at the `DailyGraphRenderer` boundary.

Required regressions:

1. Existing daily/night label text and font scaling tests remain green.
2. “Rain shown today” state still uses the unsuppressed summary from handler-owned metadata.
3. Graph icon diagnostic logging still reports the prepared-day rain flag.

### F6 — Per-render diagnostics use DEBUG and label formatting invokes a DEBUG-logging wrapper [MEDIUM]

Evidence:

1. The facade's `debug` helper maps to `Log.d` (`:23-25`) and is called for render/layout
   breadcrumbs.
2. `DailyBarRenderer` has the same DEBUG helper and calls it for each primary/overlay/adaptive bar.
3. `formatTempLabel` calls the Android `com.weatherwidget.util.TempUtils` wrapper (`:1058`), which
   logs every formatting call at DEBUG.
4. These paths run once per render or once per visible label/bar, matching the project's explicit
   VERBOSE tier.

Impact:

Routine rendering floods DEBUG logcat and performs avoidable message formatting. It also makes the
few sparse daily render diagnostics harder to isolate.

Required fix:

1. Route per-render/per-bar breadcrumbs to `Log.v`.
2. Gate expensive message construction with `Log.isLoggable(TAG, Log.VERBOSE)` where the lambda
   wrapper does not already avoid it.
3. Have `DailyTemperatureLabelRenderer` call the shared formatter directly; do not route each
   label through an Android wrapper that emits DEBUG.
4. Retain WARN for rejected input/layout containment and sparse DEBUG for one-per-resolution
   summaries only.

## Implementation order

1. Add failing regressions for F1, F2, and F3 before production edits.
2. Add `DailyGraphInputNormalizer`; switch the entire render path to normalized days/columns.
3. Introduce typed `DailyRainLabelPlacement` and `DailyGraphRenderResult`; migrate header collision
   and `NightRainGridMapper`.
4. Extract `DailyTemperatureLabelRenderer`.
5. Extract `DailyGraphLayoutResolver`.
6. Extract `DailyGraphPaintCache`.
7. Extract `DailyColumnRenderer`.
8. Slim `DailyForecastGraphRenderer` to the ordered facade and add the architecture guard.
9. Split the rain data contract and remove the dead field.
10. Apply the logging-tier cleanup.
11. Run focused verification after each extraction, then the broader and emulator lanes below.

## Behavior invariants to preserve

1. Draw order: today panel behind bars; bars before icons/labels; daily and night rain labels before
   header; error watermark last.
2. Today triple-bar ordering/colors, history overlay position, climate alpha, adaptive segments, and
   minimum bar height.
3. Effective-high/effective-low cutoff behavior and dual-high label placement.
4. Day-label shortening/scaling and temperature-label shrink-to-fit.
5. Sentinel containment and valid cold-temperature participation in the axis.
6. Sparse `columnIndex` spacing.
7. Header date suppression only for meaningful overlap with a daytime rain label.
8. Night-rain placement geometry and click-zone alignment.
9. Celsius/Fahrenheit formatting and whole-degree/decimal behavior.
10. Paints shared through caches remain immutable during a render; mutations use local copies.

## Verification

### Baseline completed during review

The current source passed 71 matching Daily forecast graph tests:

```text
./gradlew \
  :app:testLongDebugUnitTest --tests 'com.weatherwidget.widget.DailyForecastGraph*' \
  :app:testShortDebugUnitTest --tests 'com.weatherwidget.widget.DailyForecastGraph*'

BUILD SUCCESSFUL in 18s
```

This baseline does not cover equal-text day/night header caching, non-finite renderer input, or
normalized neighbor topology.

### Completed implementation verification

1. After every extraction:

   ```text
   ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
   ```

2. Focused renderer/handler regressions:

   ```text
   ./gradlew \
     :app:testLongDebugUnitTest \
       --tests 'com.weatherwidget.widget.DailyForecastGraph*' \
       --tests 'com.weatherwidget.widget.handlers.NightRainGridMapper*' \
       --tests 'com.weatherwidget.widget.handlers.DailyGraphRenderer*' \
     :app:testShortDebugUnitTest \
       --tests 'com.weatherwidget.widget.DailyForecastGraph*' \
       --tests 'com.weatherwidget.architecture.DailyForecastGraphRendererArchitectureTest'
   ```

3. Broader unit lanes:

   ```text
   ./gradlew :app:testByDurationDebugUnitTest :shared:test
   ```

4. Review/packaging checks:

   ```text
   ./gradlew :app:assembleDebug
   git diff --check
   ```

5. API 36 emulator validation is required because the implementation changes Canvas render
   orchestration and the production rain-placement result:
   - capture widget source, view, date offset, zoom, and size before testing;
   - install the debug APK and refresh a multi-column daily graph;
   - verify bars, today panel, high/low/day labels, day/night rain labels, header, and watermark
     ordering from screenshot plus renderer logcat;
   - exercise a same-percentage day/night rain fixture with a header date in a focused real-Canvas
     instrumented test if it cannot be produced from live weather;
   - verify night-rain click zones still align with the drawn labels;
   - restore the captured widget state before completion.

All required lanes completed:

1. Kotlin and unit-test compilation passed:

   ```text
   ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
   ```

2. Focused Daily renderer, handler, rain-grid, and architecture tests passed in both duration
   buckets. The focused command also included `DailyViewHandlerTest`.

3. Broader unit verification passed:

   ```text
   ./gradlew :app:testByDurationDebugUnitTest :shared:test
   ```

4. APK packaging passed for both the application and instrumented-test APK:

   ```text
   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
   ```

5. The API 36 `Generic_Foldable_API36` emulator passed both methods in
   `DailyForecastGraphRendererInstrumentedTest`:

   ```text
   ./scripts/emulator-tests.sh \
     -e Generic_Foldable_API36 \
     -c com.weatherwidget.widget.handlers.DailyForecastGraphRendererInstrumentedTest \
     -d 10m -v

   Total: 2
   Passed: 2
   ```

   The real-Canvas fixture returned distinct typed DAY/NIGHT placements for identical label text
   and suppressed the header date from the DAY bounds. The RemoteViews fixture drew a NIGHT label
   at bounds `(215.0,336.6361)..(285.0,382.32388)`, mapped it to columns 8-11 in each of the six
   overlay rows, and verified that every covered cell had a click listener.

6. The launcher widget on `emulator-5554` refreshed successfully as a 10-column, 5-row daily graph
   (`sizeDp=594x392`, bitmap `584x385`). Screenshot inspection confirmed the existing today panel,
   primary/history bars, high/low/day labels, icons, header, and navigation remained correctly
   ordered after refresh. Live weather had no rain labels, so the focused synthetic real-Canvas
   fixture supplied the required equal-probability and click-zone evidence.

7. State was unchanged after validation:
   - widget 59: NWS, DAILY, date offset -1, hourly offset 0, WIDE;
   - widget 2: NWS, DAILY, date offset -2, hourly offset 0, WIDE.

8. `git diff --check` passed. No commit or push was performed.

## Completion criteria

Implementation is complete only when:

1. F1-F6 are implemented; none remain marked deferred.
2. The facade is 500 lines or fewer and protected by the architecture test.
3. Extracted renderers no longer call implementation helpers on the facade.
4. New correctness regressions fail against the pre-fix behavior and pass afterward.
5. Focused and broader automated lanes pass.
6. API 36 screenshot/logcat/click-zone evidence confirms the runtime result and widget state is
   restored.
7. Implementation, verification, commit creation, and push status are reported separately. No
   commit or push is implied by this review.
