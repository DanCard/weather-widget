# GraphRenderUtils code review

Date: 2026-07-29
Scope: `app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt` plus its production callers and focused tests
Reviewed revision: `6788d009` (`main`)
Review mode: initially read-only; implementation was subsequently authorized by the user
Implementation status: completed and verified on 2026-07-29

## Executive conclusion

`GraphRenderUtils` is not ready to remain as a general-purpose utility object. It is 1,199 lines long
and currently owns Android path construction, timeline math, hourly-footer planning and drawing,
NOW/day-label placement, series smoothing, an unused fetch-dot renderer, failure-watermark
presentation, drawable tinting, date formatting, and error-code formatting. Those responsibilities
do not change for the same reasons and cannot be verified through one coherent test seam.

The review found two correctness defects in the active hourly-graph paths, one narrow-layout
correctness defect in the failure watermark, and two structural problems that must be resolved as
part of the work rather than deferred:

1. Preserve source indices when building paths across missing-temperature gaps, so weather colors
   remain attached to the correct hours.
2. Replace the duplicated footer “simulate, then independently draw” loops with one pure layout
   plan that is safe when content is wider than the canvas and has an explicit no-fit fallback.
3. Extract and width-fit the failure watermark.
4. Move platform-free smoothing/timeline logic to `:shared` and remove duplicated extrema code.
5. Delete the residual `GraphRenderUtils` object after moving its remaining Android drawing
   responsibilities into cohesive renderers and removing dead APIs.

The existing focused tests pass, but they do not exercise a `NaN` gap in per-segment color mapping,
an all-configurations-overlap footer, an oversized footer group, or watermark bounds on a narrow
canvas.

## Findings

### F1 — High: per-segment forecast colors shift after the first `NaN` gap

Evidence:

- `buildPerSegmentPaths` calls `splitContiguousSegments`, discards each segment's original point
  indices, and returns a flat `List<Path>` (`GraphRenderUtils.kt:64-82`).
- Missing forecast temperatures are a supported runtime state. `TemperatureHourDataBuilder` emits
  `Float.NaN` for a missing forecast, and `GraphRenderUtils` explicitly splits paths at `NaN`
  (`TemperatureHourDataBuilder.kt:269-272`; `GraphRenderUtils.kt:85-100`).
- `TemperatureGraphRenderer` builds `forecastSegmentPaths` from those possibly gapped points
  (`TemperatureGraphRenderer.kt:198-221`) but colors returned path `i` from `hours[i + 1]`
  (`TemperatureGraphRenderer.kt:378-382`).

With points `[0, 1, NaN, 3, 4]`, the builder returns the paths for source intervals `0→1` and
`3→4`. The renderer nevertheless colors them from hours `1` and `2`. Every returned path after the
gap can therefore use the weather state of an earlier, unrelated hour. The visible line stays
disconnected as intended, but its adaptive color semantics are wrong.

Required remediation:

1. Extract path construction to `AndroidCurvePathBuilder.kt`.
2. Replace `List<Path>` with indexed records, for example:

   ```kotlin
   internal data class IndexedCurvePath(
       val startPointIndex: Int,
       val endPointIndex: Int,
       val startsContour: Boolean,
       val path: Path,
   )
   ```

3. Preserve original indices while splitting finite runs; do not reconstruct indices from the
   flattened result position.
4. In `TemperatureGraphRenderer`, choose the adaptive color from
   `hours[segment.endPointIndex]`.
5. Reset or deliberately preserve dash phase at `startsContour`; encode the chosen behavior in a
   test instead of allowing the flattened list to decide accidentally. Resetting at a missing-data
   gap is the clearer default because no visible curve connects the contours.

Required regression coverage:

1. Pure/indexed builder test using finite points on both sides of one and multiple `NaN` gaps.
2. Assert exact `(startPointIndex, endPointIndex)` pairs.
3. Renderer seam test with distinct weather conditions before and after a gap; assert the
   post-gap path receives the post-gap hour's color.
4. Retain the existing path-length/dash tests for a fully contiguous series.

### F2 — High: hourly-footer layout can throw on oversized content and selects the densest fallback when no configuration fits

Evidence:

- Both the overlap simulation and draw pass clamp non-date labels with
  `centerX.coerceIn(leftExtent, widthPx - rightExtent)` or
  `centerX.coerceIn(halfWidth, widthPx - halfWidth)` without first proving the content fits
  (`GraphRenderUtils.kt:287-313`, `445-480`).
- If the text/icon group is wider than the canvas, the lower bound is greater than the upper bound.
  Kotlin's `coerceIn` rejects that empty range instead of producing a placement. Date labels avoid
  this through `placeDateLabelCenter`, but ordinary time labels do not.
- The adaptive selector initializes `selectedConfig` to `configs.first()` and changes it only when
  `checkHourlyLabelOverlap` returns false (`GraphRenderUtils.kt:359-395`). If all seven candidates
  overlap, it silently renders the first candidate: original spacing with icons. That is the least
  conservative fallback in the list.
- The overlap checker and draw pass separately implement the same candidate filtering, last-label
  icon rule, width measurement, edge clamp, and date-label state (`GraphRenderUtils.kt:259-321`,
  `415-485`). This duplication allows simulation and rendering to drift.
- The only direct adaptive-footer test merely asserts that the icon count is zero or less than the
  item count (`TemperatureGraphLabelPlacementRobolectricTest.kt:1106-1148`). That assertion is true
  even for ordinary last-icon suppression and does not verify selected spacing, non-overlap,
  on-canvas bounds, or the no-fit path.

Required remediation:

1. Extract a pure `HourlyFooterLayoutPlanner.kt`; it must return the selected configuration and the
   exact placements to draw. `HourlyFooterRenderer.kt` should consume that plan without repeating
   selection or geometry.
2. Model measured content explicitly:

   ```kotlin
   internal data class FooterLabelPlacement(
       val itemIndex: Int,
       val text: String,
       val textCenterX: Float,
       val baselineY: Float,
       val iconBounds: GraphRect?,
   )

   internal data class HourlyFooterPlan(
       val spacingPx: Float,
       val drawsIcons: Boolean,
       val placements: List<FooterLabelPlacement>,
   )
   ```

3. Treat an oversized group as an invalid icon configuration, then retry without the icon. Treat
   oversized text as a dropped label; never pass an empty range to `coerceIn`.
4. Make the all-configurations-fail behavior explicit. Use the safest evaluated plan (no icons,
   largest spacing, and only non-overlapping/on-canvas placements), not `configs.first()`.
5. Base simulation on whether an icon can actually be drawn. The current checker considers
   `drawIcons` and `hasIcon`, while the draw pass additionally requires `drawIcon != null`.
6. Return the icon bounds from the plan so cloud, precipitation, and temperature label collision
   engines reserve exactly what was drawn.

Required regression coverage:

1. Oversized text+icon group on a narrow canvas does not throw and retries without the icon.
2. Text wider than the canvas is dropped without throwing.
3. All candidate spacings overlap: the selected plan is explicitly the safest fallback and its
   placements do not overlap.
4. `drawIcon == null` cannot cause the planner to reserve icons the renderer will not draw.
5. THREE_DAY date labels retain the last-day-icon rule and neighbor gap.
6. Assert exact label/icon bounds and selected configuration; do not use icon count as the oracle.
7. Run fixtures at multiple densities and at narrow/wide widget widths.

### F3 — Medium: failure-watermark text is not constrained to the pill or canvas

Evidence:

- `drawErrorWatermark` caps `pillWidth` at `width - 8dp`, but it always draws the original main and
  detail strings at their original text sizes (`GraphRenderUtils.kt:1026-1071`, `1095-1102`).
- A capped pill therefore does not cap either text line. A long source label or detail line can
  extend outside the pill and canvas on a narrow widget.
- Current watermark tests only capture whether text containing `UPDATES FAILING` was drawn, using
  300px-wide fixtures. They do not inspect layout bounds or narrow sizes
  (`RateLimitedWatermarkRobolectricTest.kt:49-258`).

Required remediation:

1. Extract `GraphFailureWatermarkRenderer.kt`.
2. Extract a pure `FailureWatermarkLayout` calculation that accepts canvas bounds and measured main
   and detail lines.
3. Fit each line to the available inner width. Prefer deterministic text-size reduction down to a
   documented minimum, then ellipsize if it still does not fit.
4. Guard non-positive canvas dimensions and ensure the returned pill rectangle is never inverted.
5. Keep error-code and failure-time formatting with this component; expose the formatter to pure
   tests.

Required regression coverage:

1. Long source + long error detail on narrow, normal, and wide canvases.
2. Both text lines remain within the pill's inner horizontal bounds.
3. Pill bounds remain within the canvas and have non-negative width/height.
4. Today-versus-older failure-time formatting and known/unknown error-code mapping.
5. Existing four-renderer presence tests remain as integration coverage.

### F4 — Medium: platform-free smoothing/extrema logic is duplicated instead of having one owner

Evidence:

- `GraphRenderUtils.findLocalExtremaIndices` (`GraphRenderUtils.kt:975-1015`) is the same
  plateau-aware integer algorithm already owned by
  `shared/.../GraphLabelPlacementUtils.findLocalExtremaIndices`
  (`GraphLabelPlacementUtils.kt:173-199`). The shared documentation even says Android renderers
  delegate there, but this file does not.
- `GraphRenderUtils.smoothValuesPreservingAllExtrema` and its moving-average loop
  (`GraphRenderUtils.kt:683-798`) are also duplicated in
  `shared/.../TemperatureInterpolator.kt:68-128`.
- These functions depend on neither Android graphics nor widget state. Keeping copies allows
  plateau rules, rounding policy, and anchor reapplication to change independently.

Required remediation:

1. Add a platform-free `SeriesSmoothing` owner under
   `shared/src/main/kotlin/com/weatherwidget/shared/graph/`.
2. Move the weighted moving average and the configurable preserved-anchor variants there.
3. Delegate local-extrema detection to `GraphLabelPlacementUtils`; do not retain another copy in
   Android or `TemperatureInterpolator`.
4. Preserve the current integer-rounding semantics for cloud-cover/precipitation in the initial
   move. If temperature smoothing needs sub-degree extrema, change that policy in a separately
   specified behavior change with its own fixtures.
5. Update `CloudCoverGraphRenderer`, `PrecipitationGraphRenderer`, and
   `TemperatureInterpolator` to use the single shared owner.

Required regression coverage:

1. Move the existing smoothing tests to `:shared` and cover zero/negative iterations, fewer than
   three values, repeated global extrema, plateau extrema, and configurable endpoint preservation.
2. Add parity fixtures proving current Android cloud/precipitation outputs are unchanged.
3. Run `:shared:testShortShared` plus focused Android renderer tests.

### F5 — Medium: `GraphRenderUtils` is a non-cohesive facade with dead and overexposed APIs

Evidence:

- The object exposes 37 function/data-class declarations across unrelated responsibilities in
  1,199 lines.
- `drawFetchDot` (`GraphRenderUtils.kt:800-904`) has no production or test caller. Temperature has a
  separate active fetch-dot renderer that returns collision bounds
  (`TemperatureGraphRenderer.kt:892`, `1039`). The dead version allocates three paints plus two text
  paints per call and accepts an unused `Context`.
- `evaluateCubicY` has no caller. `splitContiguousSegments`, `computeTangents`,
  `findLocalExtremaIndices`, and base `smoothValues` are exposed outside their actual ownership
  needs.
- `drawDayLabels` also accepts an unused `Context`.
- Pure timeline calculations (`computeXForTime`, `computeNowX`, `dayLabelEndpoints`) live beside
  Android `Canvas`, `Paint`, `Path`, drawable loading, and localized error presentation.

Required structural result:

1. `AndroidCurvePathBuilder.kt`
   - Android `Path` construction only.
   - Indexed contour/segment records required by F1.
   - Delegates tangent math to shared `CurveMath`.
2. `HourlyFooterLayoutPlanner.kt` and `HourlyFooterRenderer.kt`
   - Pure footer candidate/placement selection and the Android draw adapter required by F2.
   - Footer icon size/gap/width-class policy and standard hourly icon tint belong here.
3. Shared `HourlyTimelineGeometry.kt`
   - `computeXForTime` and `computeNowX`, with explicit aligned-item/point contracts.
4. `HourlyIndicatorRenderer.kt`
   - Android NOW line/label adapter and day-label drawing.
   - Use `GraphRect` for pure day-label placement and convert to `RectF` only at the draw boundary.
   - Keep `dayLabelEndpoints` either in this cohesive component or in shared timeline geometry.
5. `GraphFailureWatermarkRenderer.kt`
   - Watermark layout, formatting, paints, and draw pass required by F3.
6. Shared `SeriesSmoothing.kt`
   - All platform-free smoothing required by F4.
7. Delete the unused `drawFetchDot`, unused `evaluateCubicY`, redundant extrema implementation,
   unused `Context` parameters, duplicated KDoc, and finally `GraphRenderUtils.kt` itself.

Do not leave forwarding wrappers in `GraphRenderUtils` after call sites migrate. A compatibility
facade would preserve the same dumping-ground ownership and make future additions gravitate back to
it.

### F6 — Low: per-render diagnostics use DEBUG instead of VERBOSE

Evidence:

- `drawHourLabels` emits a DEBUG row every footer render (`GraphRenderUtils.kt:397`).
- `drawDayLabels` emits a DEBUG row per endpoint label per render (`GraphRenderUtils.kt:962`).
- Project logging policy assigns per-render placement decisions to VERBOSE.

Required remediation:

1. Change both diagnostics to `Log.v` during the relevant extraction.
2. Keep the selected footer configuration and day-label placement details; only lower their level.
3. Use the owning extracted component's `TAG`, not the generic `GraphRenderUtils`/`DayLabel` tags.

## Required implementation order

1. Add failing regression tests for F1 and F2 against the current behavior.
2. Extract indexed curve paths and fix temperature segment coloring.
3. Extract the footer planner/renderer and make no-fit behavior explicit.
4. Extract and bounds-test the failure watermark.
5. Consolidate smoothing and timeline geometry in `:shared`.
6. Extract NOW/day-label Android drawing, move icon tinting into the footer component, and migrate
   all callers.
7. Remove dead APIs and delete `GraphRenderUtils.kt`.
8. Run focused JVM/Robolectric suites, then the wider module suites.
9. Install on the emulator and visually verify all hourly graph types and a narrow failure
   watermark before declaring the refactor complete.

This sequence fixes active correctness defects before the broad ownership move and keeps each
extraction protected by a narrow test seam.

## Implementation result

All six findings were implemented; none remain as follow-up work.

| Finding | Implemented result |
|---|---|
| F1 — indexed path/color correctness | Added `AndroidCurvePathBuilder.IndexedCurvePath`, retained original indices across finite runs, reset dash phase at new contours, and selected adaptive color from `hours[segment.endPointIndex]`. |
| F2 — footer safety/cohesion | Added pure `HourlyFooterLayoutPlanner` plus `HourlyFooterRenderer`; one returned plan now owns selection, placement, exact icon bounds, oversized-content rejection, and the explicit safest no-fit fallback. |
| F3 — watermark bounds | Added `GraphFailureWatermarkRenderer` and `FailureWatermarkLayout`; text shrinks then ellipsizes, and non-positive/inverted canvas cases decline layout safely. |
| F4 — shared smoothing | Added shared `SeriesSmoothing`, migrated Android/shared/desktop consumers, and removed the duplicate smoothing/extrema implementation from `TemperatureInterpolator`. |
| F5 — structural split | Added the curve, footer, timeline, indicator, watermark, and smoothing owners specified above; migrated all callers; removed dead APIs; deleted `GraphRenderUtils.kt` without a forwarding facade. |
| F6 — render logging | Footer and indicator placement diagnostics now use `Log.v` with their owning component tags. |

The final ownership split is:

1. `AndroidCurvePathBuilder.kt` — Android path construction and indexed contours.
2. `HourlyFooterLayoutPlanner.kt` — pure footer candidate selection and geometry.
3. `HourlyFooterRenderer.kt` — Canvas/icon adapter for an already-selected plan.
4. `HourlyTimelineGeometry.kt` — shared time-to-x and day-endpoint math.
5. `HourlyIndicatorRenderer.kt` — NOW and endpoint day-label drawing.
6. `GraphFailureWatermarkRenderer.kt` — failure text formatting, fitting, layout, and drawing.
7. `SeriesSmoothing.kt` — shared smoothing and preserved-extrema policy.

`cpdCheck` initially identified duplicated parameter/setup code inside the new footer adapter. The
duplicate entry point was removed, callers now explicitly plan then draw, and the extracted files
are absent from CPD's duplicate-file findings.

### Added regression coverage

1. `HourlyFooterLayoutPlannerTest` covers oversized icon groups, oversized text, all-candidates-fail
   fallback, no-icon planning, date-label behavior, and on-canvas/non-overlap bounds.
2. `SeriesSmoothingTest` covers zero/negative iterations, short inputs, repeated extrema, plateau
   extrema, and endpoint-preservation configuration.
3. `HourlyTimelineGeometryTest` covers aligned/misaligned data and NOW/day endpoints.
4. `GraphFailureWatermarkRendererRobolectricTest` covers narrow bounds, invalid dimensions, error
   mapping, and failure-time formatting.
5. `TemperatureGraphDashContinuityTest` now covers original indices and post-gap color identity.
6. `GraphFailureWatermarkRendererInstrumentedTest` renders both a narrow long-detail watermark and
   a realistic narrow temperature graph with a deliberate `NaN` gap on a real API 36 Canvas.

### Verification performed after implementation

Automated verification:

```bash
./gradlew :shared:compileKotlin :shared:compileTestKotlin \
  :desktop:compileKotlin :desktop:compileTestKotlin \
  :app:compileDebugKotlin :app:compileDebugUnitTestKotlin

./gradlew :shared:testShortShared \
  --tests com.weatherwidget.shared.graph.SeriesSmoothingTest \
  --tests com.weatherwidget.shared.graph.HourlyTimelineGeometryTest \
  :app:testShortDebugUnitTest \
  --tests com.weatherwidget.widget.HourlyFooterLayoutPlannerTest \
  :app:testLongDebugUnitTest \
  --tests com.weatherwidget.widget.TemperatureGraphDashContinuityTest \
  --tests com.weatherwidget.widget.TemperatureGraphLabelPlacementRobolectricTest \
  --tests com.weatherwidget.widget.GraphFailureWatermarkRendererRobolectricTest \
  --tests com.weatherwidget.widget.HourlyGraphDayLabelRobolectricTest \
  --tests com.weatherwidget.widget.CloudCoverGraphLabelPlacementRobolectricTest \
  --tests com.weatherwidget.widget.PrecipitationGraphRendererRobolectricTest

./gradlew :shared:test :desktop:test :app:testByDurationDebugUnitTest cpdCheck
./gradlew assembleDebug
./scripts/emulator-tests.sh \
  -c com.weatherwidget.widget.GraphFailureWatermarkRendererInstrumentedTest
```

Results:

1. Focused compile and regression lanes passed.
2. Final whole-module `:shared:test`, `:desktop:test`, and Android
   `:app:testByDurationDebugUnitTest` passed (`BUILD SUCCESSFUL`).
3. `cpdCheck` completed; no extracted component is named as a duplicated-file start.
4. `assembleDebug` passed.
5. Both API 36 instrumented real-Canvas fixtures passed.
6. `git diff --check` passed.

Runtime verification used `Medium_Phone_API_36` (`emulator-5554`):

1. Captured the installed widget's baseline state and screenshot.
2. Installed the final debug APK only on the emulator with app data preserved.
3. Used the app's real `ACTION_SET_VIEW` receiver path to render Temperature, Precipitation, and
   Cloud Cover on the existing 6-column widget; curves, footer labels, NOW marker, day endpoints,
   and icons remained in bounds.
4. Visually inspected the 180×120 narrow watermark fixture: both long lines ellipsized inside the
   pill.
5. Visually inspected the realistic narrow gap fixture: the rain-colored pre-gap contour and
   sun-colored post-gap contour remained disconnected and kept their source-hour colors.
6. Found no `FATAL EXCEPTION` in logcat and no ERROR rows in the persistent app log during the
   validation window.
7. Restored widget 2 to its exact prior state: Daily mode, date offset `-2`, hourly offset `-6`,
   WIDE zoom, display-source step `20` (NWS). The emulator was left running.

## Affected production files

At minimum:

1. `app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt` — delete after migration.
2. `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — indexed segment
   coloring, footer, timeline, indicator, watermark call sites.
3. `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt` — smoothing, footer,
   timeline, day/NOW placement, icon, watermark call sites.
4. `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt` — smoothing, curve,
   footer, timeline, day/NOW placement, icon, watermark call sites.
5. `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` — watermark call site.
6. `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureHourDataBuilder.kt` — no behavior
   change required, but retain its `Float.NaN` missing-forecast contract in F1 fixtures.
7. `shared/src/main/kotlin/com/weatherwidget/shared/graph/GraphLabelPlacementUtils.kt` — single
   extrema owner.
8. `shared/src/main/kotlin/com/weatherwidget/shared/util/TemperatureInterpolator.kt` — use shared
   smoothing.
9. New cohesive files named in F5.

## Verification matrix

| Risk | Required automated proof | Runtime proof |
|---|---|---|
| Gap segment identity/color | Indexed builder test plus temperature renderer seam test with distinct conditions around `NaN` | Temperature graph fixture/device state with a source gap; confirm disconnected segments and post-gap color |
| Footer crash/overlap | Pure planner tests for oversized groups, all-fail fallback, date labels, and exact bounds at multiple densities | Narrow and wide temperature/precip/cloud widgets; inspect labels and icons |
| Watermark overflow | Pure layout bounds tests and four renderer integration tests | Narrow graph with long source/error detail |
| Smoothing parity | `:shared` unit fixtures plus cloud/precip focused renderer tests | Compare pre/post screenshots for cloud and precipitation curves |
| NOW/day labels | Existing geometry tests plus focused renderer tests | Check endpoint day labels and NOW marker in all hourly views |

Recommended command lane after implementation:

```bash
./gradlew :shared:testShortShared
./gradlew :app:testShortDebugUnitTest \
  --tests 'com.weatherwidget.widget.*Graph*Utils*' \
  --tests 'com.weatherwidget.widget.*Footer*' \
  --tests 'com.weatherwidget.widget.*Watermark*'
./gradlew :app:testLongDebugUnitTest \
  --tests 'com.weatherwidget.widget.TemperatureGraphDashContinuityTest' \
  --tests 'com.weatherwidget.widget.TemperatureGraphLabelPlacementRobolectricTest' \
  --tests 'com.weatherwidget.widget.CloudCoverGraphLabelPlacementRobolectricTest' \
  --tests 'com.weatherwidget.widget.PrecipitationGraphRendererRobolectricTest' \
  --tests 'com.weatherwidget.widget.HourlyGraphDayLabelRobolectricTest'
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
./gradlew :app:testByDurationDebugUnitTest
```

Then:

```bash
./gradlew assembleDebug installDebug
```

Use the already-running emulator by default. Preserve its selected widget source/view state, take
before/after screenshots, and verify temperature, precipitation, and cloud-cover views at narrow
and wide sizes. Trigger a visible failure watermark fixture or state for the narrow-width check.

## Review verification performed

The review ran the following focused baseline command against revision `6788d009`:

```bash
./gradlew :app:testShortDebugUnitTest \
  --tests 'com.weatherwidget.widget.GraphRenderUtilsTest' \
  :app:testLongDebugUnitTest \
  --tests 'com.weatherwidget.widget.TemperatureGraphDashContinuityTest' \
  --tests 'com.weatherwidget.widget.TemperatureGraphLabelPlacementRobolectricTest' \
  --tests 'com.weatherwidget.widget.HourlyGraphDayLabelRobolectricTest'
```

Result: `BUILD SUCCESSFUL`; 9 short `GraphRenderUtilsTest` tests and 44 focused long
renderer/placement tests passed. No emulator/device validation was performed because this turn
changed only the review document and did not change runtime behavior.

## Completion criteria

This review is implemented only when all of the following are true:

1. F1-F6 are resolved; none are left as optional follow-up.
2. `GraphRenderUtils.kt` is deleted, not merely shortened behind forwarding wrappers.
3. Missing-data path records retain original source indices and the renderer uses them for color.
4. Footer layout cannot call `coerceIn` with an empty range and has a tested explicit no-fit plan.
5. Watermark text and pill bounds stay within the canvas for narrow fixtures.
6. Android and `:shared` no longer contain duplicate smoothing/extrema algorithms.
7. New/modified test classes have exactly one correct duration category.
8. Focused tests, compile checks, the wider duration suites, APK build/install, and emulator visual
   checks pass.
9. Existing temperature, precipitation, cloud-cover, NOW/day-label, and error-watermark behavior is
   preserved except for the explicitly corrected gap color, overflow, and fallback cases.
