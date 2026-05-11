# Mirror `TemperatureGraphStyle` Pattern for Cloud Cover & Precipitation Renderers

## Context

The code review in `plans/260511-code-review-PrecipitationGraphRenderer.kt.md` flagged sibling-renderer duplication as a future direction (out of scope at the time, conditional on findings #1 + #2 surfacing cross-renderer infra). Finding #1 was rejected (`PrecipRect` is load-bearing for plain-JUnit test stubability), so a base-class consolidation is unattractive — the three renderers diverge on rectangle representation.

A lighter, additive move exists: replicate the `TemperatureGraphStyle.kt` pattern for the other two renderers. Today, `CloudCoverGraphRenderer` and `PrecipitationGraphRenderer` each carry their own ~80-line `ensurePaints` builder and a copy-paste `dpToPx` — substantively the same paint construction (hourLabel, nowLabel, dayLabel, todayDay, currentTime) with minor per-domain additions. Pulling each renderer's style surface into a sibling `XxxGraphStyle.kt` file:

- Mirrors an already-validated pattern in the codebase (no new convention).
- Shrinks the renderer bodies, isolating drawing logic from paint plumbing.
- Doesn't require any interface, base class, or signature churn.
- Doesn't touch the testability seam (`PrecipRect` stays where it is).

Outcome: two new files, two renderers ~80 lines lighter each, no behavior change.

## Files

**Create:**
- `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphStyle.kt`
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphStyle.kt`

**Modify:**
- `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`

## Reference pattern

Follow `app/src/main/java/com/weatherwidget/widget/TemperatureGraphStyle.kt`:
- `object XxxGraphStyle` in the same `com.weatherwidget.widget` package.
- Owns: style-only `const val` sizes/colors, the `PaintSet` data class, the cached `ensurePaints(context, ...)` function, and `dpToPx(context, dp)`.
- Renderer reduces to a `private fun ensurePaints(...) = XxxGraphStyle.ensurePaints(...)` one-liner — same as `TemperatureGraphRenderer.kt:43`.

One divergence from Temperature's split: Temperature factored `PaintSet` into `TemperatureGraphModels.kt` because that file holds several model classes. CloudCover/Precip have no other models worth a separate file — define `PaintSet` directly inside the new `XxxGraphStyle.kt`. Less file sprawl.

## What moves vs. what stays

### CloudCoverGraphRenderer → CloudCoverGraphStyle

**Move:**
- Style-only constants in lines 38–50: `COLOR_CLOUD_CURVE`, `COLOR_CLOUD_GRADIENT_START`/`END`, `COLOR_MISSING_DIAG_*`, `MISSING_DIAG_TEXT_SIZE_DP`, `MISSING_DIAG_REASON_TEXT_SIZE_DP`, `MISSING_DIAG_MIN_LABEL_SCALE`, `MISSING_DIAG_LINE_SPACING`, `MISSING_DIAG_SHADOW_RADIUS_DP`, `MISSING_DIAG_SHADOW_DY_DP`.
- `PaintSet` (lines 96–108) — promote to `internal data class` so the renderer can still see it.
- `ensurePaints` (lines 112–188) — promote to `internal fun`. Move the `cachedPaints` field with it.
- `dpToPx` (line 794) — make it `internal` in the style file. Renderer's own `dpToPx` becomes a one-line delegate (consistent with how `TemperatureGraphRenderer.kt:44` does it).

**Stay (these are logic, not style):**
- All `data class`es: `CloudHourData`, `LabelPlacementDebug`, `DayLabelPlacementDebug`, `WatermarkPlacementDebug`, `VerticalScaleDebug`.
- Domain constants used by placement/scaling logic: `LOW_CLOUD_BELOW_OVERFLOW_MAX_PERCENT`, `LOW_CLOUD_BELOW_OVERFLOW_DP`, `GRAPH_TOP_PADDING_DP`, `GRAPH_BOTTOM_PADDING_DP`, `TOP_SCALE_HEADROOM_PERCENT`, `MIN_DYNAMIC_TOP_SCALE_PERCENT`, `MAX_DYNAMIC_TOP_SCALE_PERCENT`, `SOFT_DIP_MAX_PERCENT`, `SOFT_DIP_MIN_DIFF`, `WATERMARK_*`.
- All rendering / placement / diagnostic functions.

### PrecipitationGraphRenderer → PrecipitationGraphStyle

**Move:**
- Style-only constants used inside `ensurePaints` (curve color, rain-amount color, rain-amount text size, etc. — enumerate during the edit pass).
- `PaintSet` (lines ~490–507) — promote to `internal data class`.
- `ensurePaints` (lines 513–605) — promote to `internal fun`. **Preserve the double-checked locking** (`@Volatile private var cachedPaints` + `private val paintsLock`). Precip's `ensurePaints` is the only one of the three with this pattern, and it's intentional. Move both fields together.
- `dpToPx` (line 1046) — make it `internal` in the style file.

**Stay (load-bearing):**
- `PrecipRect` (lines 101–115) — the testability seam. **Do not touch.** See `memory/precip_rect_testability.md`.
- All `data class`es: `PrecipHourData`, `LabelPlacementDebug`, `DayLabelPlacementDebug`, `WatermarkPlacementDebug`, `NowLabelPlacementDebug`, `ProbabilityLabelPlacement`, `RainAmountPlacement`, `RainPeriod`, `TextMeasurer`, `PrecipGraphLayout`, `GraphGeometry`.
- All `internal fun` placement algorithms: `calculateLayout`, `calculateProbabilityLabelPlacements`, `calculateRainAmountPlacements`, `findVisibleWindowRainPeriods`, `findFixedWindowRainPeriods`.
- Domain constants: `SOFT_DIP_MAX_PROBABILITY`, `SOFT_DIP_MIN_ELEVATION`, `ELEVATED_PEAK_PROBABILITY_RANGE`, `ELEVATED_PEAK_MIN_DELTA`, `FAR_OUT_DATA_HOURS_THRESHOLD`, the `TAG` constant.

## Execution order

Do CloudCover first (simpler — no thread-safety wrinkle), then Precipitation. Test gate between them.

1. **CloudCover extraction**
   - Create `CloudCoverGraphStyle.kt`. Add `package com.weatherwidget.widget` + the imports `ensurePaints` needs (`Context`, `Color`, `Paint`, `Typeface`, `DashPathEffect`, `TypedValue`).
   - Define `internal data class PaintSet(...)` with the same fields the renderer's private class had.
   - Move the style-only constants and `ensurePaints` (including the `cachedPaints` cache). Drop `private` → `internal` for anything still referenced from the renderer.
   - Move `dpToPx`.
   - In `CloudCoverGraphRenderer.kt`: delete the moved members; replace with one-line delegate `private fun ensurePaints(...) = CloudCoverGraphStyle.ensurePaints(...)` and `private fun dpToPx(...) = CloudCoverGraphStyle.dpToPx(...)`. Update internal `PaintSet` references to `CloudCoverGraphStyle.PaintSet`.
   - Test gate: `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.CloudCover*"`.

2. **Precipitation extraction**
   - Create `PrecipitationGraphStyle.kt`. Same shape.
   - Carefully migrate `@Volatile private var cachedPaints` and `private val paintsLock` together with `ensurePaints` — these three are a single locking unit, do not split them.
   - In `PrecipitationGraphRenderer.kt`: delete the moved members; add one-line delegates for `ensurePaints` and `dpToPx`. Update `PaintSet` references to `PrecipitationGraphStyle.PaintSet`. Leave `PrecipRect` and everything else untouched.
   - Test gate: `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.Precipitation*"` plus `RainPeakLabelRoboTest`.

3. **Full verification** (below).

## Verification

1. **Unit tests** — all three renderer suites must pass:
   ```
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.CloudCover*"
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.Precipitation*"
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.RainPeakLabelRoboTest"
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.Temperature*"
   ```
   Temperature is included as a regression check — nothing should change for it, but its style file is the template we're following, so a sanity test costs nothing.

2. **Build the APK**: `./gradlew installDebug`.

3. **Visual smoke test** on the emulator (`./scripts/run-emulator-tests.sh` only if testing wider; for a visual check just install + look). Inspect on a widget at a size that shows graphs:
   - Cloud cover graph: curve, gradient fill, percent labels, NOW indicator, day labels, watermark.
   - Precipitation graph: probability labels, rain-amount text, NOW indicator, day labels, watermark, hour icons.
   - Compare with a build from `main` if anything looks off — a behavior-preserving refactor should be pixel-identical.

4. **Diff check**: each renderer file should shrink by roughly the size of its `ensurePaints` + constants + `PaintSet` (~100–120 lines). The two new style files should be roughly the same size combined. Net line count should be slightly higher (the new files add their own `package`/imports/object boilerplate), and that's expected — the win is separation, not LoC.
