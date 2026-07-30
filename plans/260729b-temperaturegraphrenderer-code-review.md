# TemperatureGraphRenderer Code Review and Refactor Plan

Reviewed: 2026-07-29
Status: Implemented and verified
Primary file: `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
Related files inspected:

- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphModels.kt`
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphStyle.kt`
- `app/src/main/java/com/weatherwidget/widget/AndroidCurvePathBuilder.kt`
- `app/src/main/java/com/weatherwidget/widget/HourlyFooterRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/HourlyIndicatorRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`
- `app/src/main/java/com/weatherwidget/widget/WidgetStartupExecution.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
- Renderer-focused JVM and Robolectric tests under
  `app/src/test/java/com/weatherwidget/widget/`

This is a fresh review of the current 1,081-line file after commits `4ba689eb` and `ff7b712b`.
The former addressed the findings in `plans/260728-temperaturegraphrenderer-code-review.md`; the
latter removed `GraphRenderUtils` and extracted curve paths, footer layout/drawing, indicators, and
failure-watermark rendering. Closed findings from the earlier review are not repeated here.

## Evidence Collected

1. `git status --short` was clean before this review artifact was created.
2. `scripts/code-review-audit.sh` passed:
   - complexity/file-size audit completed;
   - CPD completed;
   - `:app:ktlintCheck` passed;
   - test-duration category validation passed.
3. The complexity audit still reports `TemperatureGraphRenderer.kt` at 1,081 lines, above the
   repository's 500-line threshold. It also reports the 110-line positional
   `RenderContext.create(...)` factory in `TemperatureGraphModels.kt`.
4. Focused renderer tests passed:
   `./gradlew :app:testDebugUnitTest --tests 'com.weatherwidget.widget.TemperatureGraph*' --tests
   'com.weatherwidget.widget.TemperatureFetchDotColorTest'` — 93 tests, 0 failures, 0 errors.
5. No emulator/device render was performed because this turn is a read-only review. Device
   verification is required after implementing the runtime-affecting findings below.

## Overall Assessment

The renderer produces a well-tested graph and now delegates generic curve, footer, indicator, and
label-placement logic to appropriate collaborators. It is nevertheless still an orchestration hub
with four separate responsibilities:

1. resolving timeline/series geometry and actual-to-forecast transitions;
2. painting temperature series;
3. planning and painting the fetch dot, value, and staleness label;
4. placing graph annotations and maintaining collision state.

Those responsibilities are coupled through a large mutable `RenderContext`, provisional collision
bounds, cached mutable Android `Paint` objects, and high-arity positional construction. This is not
only a file-size concern: the coupling currently causes one concurrency race and one deterministic
fetch-dot placement bug. The structural split in F4 is therefore required work, not an optional
cleanup.

## Findings

### F1 — A cached shared Paint is mutated by parallel widget renders [HIGH, correctness]

Evidence:

- `TemperatureGraphStyle.ensurePaints(...)` caches and returns one `PaintSet` for a
  density/label-scale pair (`TemperatureGraphStyle.kt:91-99`, `:233-255`).
- `drawFillAndCurves(...)` assigns a render-specific gradient to
  `paints.expectedFillPaint.shader`, then draws with that shared paint
  (`TemperatureGraphRenderer.kt:373-377`).
- Startup explicitly renders widgets in parallel with one `async` child per widget
  (`WidgetStartupExecution.kt:40-58`).
- `WeatherWidgetWorker.updateAllWidgets(...)` also launches one child render per widget
  (`WeatherWidgetWorker.kt:537-574`).

Two same-scale widgets can therefore share `expectedFillPaint` while rendering different graph
dimensions or temperature ranges. One render can replace the other render's shader between
assignment and draw, producing a gradient based on the wrong graph. `@Volatile` protects only the
cache reference; it does not make the returned mutable paints safe for concurrent mutation.

Required fix:

1. Treat every cached `PaintSet` member as immutable after construction.
2. In the series painter, create a render-local copy:
   `Paint(ctx.paints.expectedFillPaint).apply { shader = buildTempGradient(...) }`.
3. Audit every `PaintSet` consumer for mutation; retain local copies for color, alignment,
   `pathEffect`, alpha, shader, stroke, and typeface changes.
4. Document the immutable-cache contract beside `PaintSet` and `ensurePaints`.

Required regression coverage:

- Assert that rendering a ghost fill does not mutate the cached base
  `expectedFillPaint.shader`.
- Render two same-scale graphs with different axes/sizes concurrently and compare each bitmap with
  its serial-render baseline. Use real Canvas/Bitmap instrumentation if Robolectric cannot make the
  interleaving deterministic.

### F2 — The staleness label always collides with its own provisional bounds [HIGH, correctness]

Evidence:

1. `computeFetchDotBounds(...)` includes the provisional staleness rectangle
   (`TemperatureGraphRenderer.kt:896-903`).
2. `renderGraph(...)` adds that rectangle to `ctx.drawnLabelBounds` before other labels are placed
   (`:1044-1049`).
3. `drawFetchDot(...)` later forms `allBounds` from `ctx.drawnLabelBounds` and asks
   `resolveStalenessInitialLayout(...)` to place the same staleness label against that list
   (`:920-927`).
4. The candidate rectangle therefore intersects an identical copy of itself. The initial
   below-dot position is always reported as colliding and is flipped above even when the graph has
   empty space below.
5. The existing test at
   `TemperatureGraphLabelPlacementRobolectricTest.kt:809-848` asserts only that a deliberately
   colliding/bottom-edge case ends above the dot. It passes for both the intended reason and the
   self-collision, so it does not cover the unobstructed default.

Required fix:

1. Move fetch-dot planning/drawing into the extraction specified in F4.
2. Give the fetch-dot plan separate `hardBounds` (ring and value), `provisionalAgeBounds`, and
   final drawn bounds.
3. Permit the provisional age bounds to reserve space for temperature/day-label planning, but
   remove or identity-exclude that reservation before final staleness placement.
4. Replace the provisional rectangle with the final age rectangle in the obstacle registry; do
   not append both.

Required regression coverage:

- An unobstructed middle-of-graph fetch dot places its age label below the dot.
- A bottom-edge or real-label collision moves the age label above.
- A displacement requiring a leader line records the final bounds only.
- The collision list contains one ring, at most one value label, and at most one final age label
  for the fetch dot.

### F3 — Day-label bounds are not published to later collision consumers [MEDIUM, correctness]

`placeDayLabels(...)` records bounds only in its local `drawnDayBounds`
(`TemperatureGraphRenderer.kt:720-750`). It never adds the selected bounds to
`ctx.drawnLabelBounds`. Consequently:

- the fetch-dot staleness reflow at `:920-945` cannot see day labels;
- yesterday-delta and ghost-line placement at `:582-600` and `:657-680` cannot see them;
- the final NOW indicator receives `ctx.drawnLabelBounds + drawnIconBounds` at `:1059-1069`, also
  without day labels.

The initial fetch-dot reservation makes some overlaps less likely, but F2 shows that the final age
label can move away from its reservation. Later annotations can also independently select a day
label's rectangle.

Required fix:

1. Introduce one render-scoped obstacle registry owned by the facade/orchestrator.
2. Every placement stage must return or register the bounds it actually draws.
3. Use owner/type identity (`TEMPERATURE_LABEL`, `DAY_LABEL`, `FETCH_DOT_RING`,
   `FETCH_DOT_VALUE`, `FETCH_DOT_AGE`, `YESTERDAY_DELTA`, `GHOST_LABEL`, `ICON`) so a component can
   exclude or replace its own provisional reservation without losing other obstacles.
4. Pass a read-only snapshot to the shared placement engines; do not expose a freely mutable list
   through `RenderContext`.

Required regression coverage:

- Final staleness placement avoids both left and right day labels.
- Yesterday-delta, ghost, and NOW labels avoid a day label selected at TOP, MIDDLE, and BOTTOM.
- Replacing a provisional fetch-dot bound does not remove unrelated day/temperature bounds.

### F4 — Split the remaining renderer by lifecycle responsibility [MEDIUM, structure]

The current file remains 1,081 lines after generic utilities were extracted. Its private methods
form three cohesive components plus a facade. Implement this split:

#### A. `TemperatureGraphSeriesResolver.kt`

Own:

- point/temperature list construction from `computePoints(...)`;
- timeline x-coordinate resolution;
- fetch/now transition selection;
- actual-series anchoring and interpolation;
- construction of the minimal series geometry consumed by painters and label engines.

The resolver may call `AndroidCurvePathBuilder`, but it must not own a `Canvas`, `Paint`, collision
bounds, or annotation callbacks. Use named construction for its result.

#### B. `TemperatureGraphSeriesRenderer.kt`

Own:

- ghost-line render gating that is specific to Android presentation;
- expected fill, ghost line, segmented forecast line, and actual-line drawing;
- per-segment weather colors and dash-phase continuity;
- the render-local paint copies required by F1.

Keep provider-independent gates (`GhostLineGate`) and curve math in `:shared`; do not move Android
`Path`, `Canvas`, or `Paint` into `:shared`.

#### C. `TemperatureFetchDotRenderer.kt`

Own:

- value-label priority layout;
- age/staleness metrics and initial/final placement;
- fetch-dot ring/value/age drawing;
- provisional/final obstacle identities and replacement described in F2/F3;
- `FetchDotDebug` emission.

Its plan/result types belong beside this renderer, not in the general
`TemperatureGraphModels.kt`.

#### D. `TemperatureGraphAnnotationRenderer.kt`

Own the Android adapters for:

- temperature-label drawing from `TemperatureLabelEngine` placements;
- day labels;
- yesterday-delta labels;
- ghost-line labels.

The shared placement engines remain the source of platform-neutral decisions. This Android
component measures/draws text and registers final bounds.

#### E. `TemperatureGraphRenderer.kt`

Retain only the stable public entry point and ordered render pipeline:

1. validate/create bitmap;
2. resolve paints and graph layout;
3. resolve series geometry;
4. draw NOW line and temperature series;
5. draw footer;
6. plan fetch-dot reservations;
7. draw/register annotations in z-order;
8. finalize/draw the fetch dot;
9. draw the final NOW indicator and failure watermark;
10. emit render timing.

Acceptance criteria:

- The facade is an orchestration file, targeted at 250-350 lines.
- No extracted component takes the entire mutable `RenderContext` merely for convenience; each
  receives a narrow input/model.
- No behavior is duplicated between old and extracted paths.
- Existing shared/desktop ownership remains intact.

### F5 — High-arity state pipes retain dead fields and positional-construction risk [MEDIUM, maintainability]

Evidence:

- `RenderContext.create(...)` takes 25 parameters and is called positionally for most of them
  (`TemperatureGraphModels.kt:200-279`; `TemperatureGraphRenderer.kt:1015-1020`).
- `RenderContextUpdate(...)` carries 19 fields and is constructed positionally at
  `TemperatureGraphRenderer.kt:262-267`.
- Several fields are computed and transported but never consumed:
  - `fetchIdx` (`TemperatureGraphRenderer.kt:193`);
  - `originalPath` (`:218`, then stored only);
  - `forecastPath` and `forecastFillPath` (`:220`, then stored only);
  - persisted `anchorDelta` after `expectedTemps` is built;
  - `Geometry.footerTop`, `Geometry.minTimeEpoch`, and `Geometry.iconTopPad`.
- The file also retains an unused placement implementation at `:27-145`
  (`debug`, `prefersAbovePlacement`, curve-intrusion helpers/constants, and
  `CURVE_AVOIDANCE_ROLES`) after placement moved to `TemperatureLabelEngine`.

Required fix:

1. Delete the dead functions, imports, constants, paths, fields, and intermediate values.
2. Replace `RenderContextUpdate` with the narrow, named `TemperatureGraphSeriesGeometry` result
   from F4.
3. Replace the positional `RenderContext.create(...)` pipe with small named inputs owned by each
   extracted component.
4. Keep the public `renderGraph(...)` signature during the extraction unless a request object
   demonstrably improves external callers; internal high-arity positional construction must be
   removed either way.

Required regression coverage:

- Compilation plus all focused graph tests prove that removed paths/state were not observable.
- Architecture/source tests assert that the facade does not reacquire extracted responsibilities.

### F6 — Per-render diagnostics use DEBUG instead of VERBOSE [LOW, maintainability]

`placeTemperatureLabels(...)` emits one `Log.d` row per selected temperature label
(`TemperatureGraphRenderer.kt:530-549`), `RenderTimings.log(...)` emits a DEBUG row on every render
(`TemperatureGraphModels.kt:309-315`), and `GraphLayout` emits scaling/layout DEBUG rows on every
render. These are per-render/per-label traces and therefore belong at VERBOSE under the project's
logging policy. The unused `debug` wrapper at `TemperatureGraphRenderer.kt:27-29` does not gate
either call.

Required fix:

1. Use `Log.v` for placement and render-breakdown traces.
2. Guard expensive message/list construction with `Log.isLoggable(TAG, Log.VERBOSE)` where useful.
3. Keep sparse failures and state transitions at DEBUG or above; do not remove diagnostics.

## Implementation Order

1. Add the failing F1-F3 regression tests first.
2. Make cached paints immutable and use render-local mutable copies (F1).
3. Add the obstacle registry and extract `TemperatureFetchDotRenderer`; fix reservation
   replacement and day-bound publication (F2-F3).
4. Extract series resolution, series drawing, and annotation adapters; leave the original object
   as the ordered facade (F4).
5. Narrow the models and remove dead state/code (F5).
6. Correct logging levels (F6).
7. Run focused, module-wide, and emulator verification below.

Compile immediately after each extraction so a removed import/model field is caught at the
smallest possible step.

## Verification Required for Implementation

### Static and JVM/Robolectric

1. `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
2. Focused new tests for:
   - immutable cached paints/concurrent rendering;
   - unobstructed versus colliding staleness placement;
   - provisional-bound replacement;
   - day-label publication and downstream avoidance.
3. `./gradlew :app:testDebugUnitTest --tests 'com.weatherwidget.widget.TemperatureGraph*' --tests
   'com.weatherwidget.widget.TemperatureFetchDotColorTest'`
4. `./gradlew :app:testByDurationDebugUnitTest`
5. `./gradlew :shared:test` because shared label/ghost gates remain integration boundaries.
6. `./scripts/code-review-audit.sh`
7. `./gradlew assembleDebug`

### Emulator

Use `Medium_Phone_API_36` or the already-running emulator. Before testing, record each visible
widget's selected view, source, zoom, and offset; restore them afterward.

Verify with a screenshot and renderer-specific logcat:

1. narrow temperature view with an unobstructed fetch dot: age label is below;
2. bottom-edge/colliding fetch dot: age label moves above and uses a leader only after sufficient
   displacement;
3. day labels at left/right do not overlap fetch age, yesterday delta, ghost, or NOW labels;
4. two widgets with different sizes/ranges render simultaneously without gradient crossover;
5. actual/forecast/ghost junction, weather-colored dashes, icons, day labels, Celsius formatting,
   and error watermark remain visually unchanged outside the intended fixes.

Use `./scripts/emulator-tests.sh -c <fully.qualified.TestClass>` for any new real-Canvas
instrumented regression. Do not claim runtime completion without this device evidence.

## Completion Definition

The review findings are complete only when F1-F6 are implemented, all required tests and audit
lanes pass, the emulator scenarios are evidenced, and the original source/view/zoom state is
restored. A clean compile or the current 93-test baseline alone is not sufficient because neither
currently exercises paint sharing or the unobstructed staleness-label path.

## Completion Evidence

Implemented 2026-07-29:

1. `TemperatureGraphRenderer.kt` is now a 322-line ordered facade. Series resolution, series
   painting, fetch-dot planning/drawing, and annotations live in the four required cohesive
   collaborators. `TemperatureGraphModels.kt` no longer contains `RenderContext`,
   `RenderContextUpdate`, or the dead paths/state listed in F5.
2. Cached `PaintSet` members are documented and treated as immutable. The ghost fill, forecast
   segment, fetch-dot, value, and dynamic annotation paints use render-local copies whenever they
   mutate a paint property.
3. One typed render-scoped obstacle registry publishes final icon, temperature, day, fetch,
   yesterday-delta, and ghost-label bounds. The fetch age reservation is removed before final
   placement and replaced with one final age obstacle.
4. Real Android font metrics exposed a second fetch-layout constraint during verification: the
   side value could overlap the centered below-dot age label even after self-reservation was fixed.
   Fetch planning now increases side separation from the measured age-label width so the genuinely
   unobstructed case remains below without weakening collision checks.
5. Per-label, scaling/layout, and render-breakdown diagnostics now use VERBOSE logging; expensive
   label/debug list construction is guarded.
6. `TemperatureGraphRendererArchitectureTest` pins the facade at no more than 350 lines and rejects
   reacquisition of the extracted implementation methods.

Final verification:

1. Compile: `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, and
   `:app:compileDebugAndroidTestKotlin` passed.
2. Focused renderer lane passed: 97 tests across `TemperatureGraph*` and
   `TemperatureFetchDotColorTest`.
3. `:app:testByDurationDebugUnitTest` passed after the final fetch-layout change.
4. `:shared:test` passed.
5. `scripts/code-review-audit.sh` passed complexity/file-size, CPD, ktlint, and duration-category
   gates. The facade and extracted files are absent from the audit's over-500-line and long-function
   findings.
6. `assembleDebug` passed.
7. API 36 real-Canvas regressions passed on `emulator-5554`:
   `TemperatureGraphRendererRegressionTest` verifies below-dot placement with only the fetch
   ring/value obstacles, cached-paint immutability, and concurrent same-scale bitmap parity.
8. Live NWS screenshots/logcat covered Wide and Narrow temperature renders, a real colliding age
   label above the fetch dot, day labels, NOW, actual/forecast/ghost junctions, colored dashes,
   icons, and yesterday delta. No fatal exception occurred.
9. The visible widget was restored to its recorded state:
   `view_mode=0` (Daily), `display_source=20` (NWS as displayed), `zoom_level=0` (Wide),
   `date_offset=-2`, and `hourly_offset=-6`. The emulator remains running.

One transient KSP generated-file error occurred only when two Gradle builds were deliberately run
concurrently. The same compile and test lane passed immediately when rerun serially; all final
verification above was serial.
