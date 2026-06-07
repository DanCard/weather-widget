# Share the temperature-graph label-placement engine between Android and desktop

**Date:** 2026-06-07
**Status:** Plan only (no code written yet)

## Context

The hourly temperature graph's *label placement* logic (deciding each temp label's
x/y, above-vs-below, leader lines, valley-vs-valley de-collision, warmer-low flip,
curve-graze tolerance) lives entirely in `:app`
(`TemperatureGraphRenderer` + `TemperatureLabelResolver` + `GraphLabelPlacementUtils`,
~2,400 lines). The `:desktop` module has a **separate, much simpler** reimplementation in
`desktop/.../TemperatureGraph.kt` (Compose `DrawScope`, ~800 lines) that only places
HIGH/LOW/NOW/END forecast labels with a basic overlap check — it has none of the cascade /
flip / leader-line / curve-avoidance behavior.

The immediate trigger: a 2026-06-07 fix (forecast low `50.0°` colliding with actual low
`49.8°` was cascading into the hour-axis footer) had to be made in Android only. As the
desktop is brought to parity (project goal: "desktop intended to be the same as Android"),
that logic would be duplicated and every future fix would need doing twice.

**Goal:** extract the placement *decision* layer into `:shared` as a pure-Kotlin engine that
returns placement decisions, leaving each platform a thin adapter that measures text and
draws. One source of truth; fixes land once.

## Key principle: decide in shared, draw per-platform

Placement is **pure geometry** — rectangles, point coordinates, text dimensions, numbers.
The only genuinely platform-specific pieces are:

| Concern | Android | Desktop | Handling |
|---|---|---|---|
| Text **measurement** (width, ascent, descent) | `Paint.measureText` / font metrics | Compose `textMeasurer` | Inject via a `LabelTextMetrics` interface |
| **Drawing** (text, leader lines) | `Canvas.drawText/drawLine` | `DrawScope.drawText/drawLine` | Engine returns decisions; adapter draws |
| **Color** of each label | `WeatherConditionColors` (android Color int) | Compose `Color` | Irrelevant to geometry — adapter colors decisions at draw time |
| `dpToPx` | needs `density` | needs `density` | Pass `density` (Float) into the engine |
| `tempToY` | pure already | pure already | Inject as `(Float) -> Float` lambda |

`:shared` is plain `kotlin-jvm` (no Android); it already hosts graph-data logic both
platforms use (`ActualTemperatureSeriesBuilder`, already imported by desktop) and a `Log`
shim (`shared/.../util/Log.kt`, `Log.d(tag, msg)`).

## Neutral types to introduce in `:shared`

- `data class GraphRect(left, top, right, bottom)` with `intersects(other)`, `height`,
  and an intersect helper — mirrors the existing `PrecipRect` testability seam (see memory
  `[[precip_rect_testability]]`). Replaces every `android.graphics.RectF` in the placement
  path (`maxVerticalOverlap`, `PlacedLabelMeta.bounds`, bounds math in `placeSingleLabel`).
- Curve geometry stays as the **already-neutral** `List<Pair<Float, Float>>`
  (`originalPoints`/`forecastPoints`/`actualVisiblePoints`).
- `interface LabelTextMetrics { fun width(text: String, isFuture: Boolean): Float; val ascent: Float; val descent: Float }`.
- `data class PlacedLabel(index, role, text, x, baselineY, placeAbove, drawLeaderLine,
  leaderFromY, leaderToY, isFuture, reason, displacementSteps)` — the engine's output;
  this is also enough to reconstruct the existing `LabelPlacementDebug` for the
  `onLabelPlaced` callback the tests assert on.

## What moves to `:shared` vs what stays

**Moves (pure, after RectF→GraphRect / Log-shim swaps):**
- `GraphLabelPlacementUtils` (whole file; only ties are `RectF` in `maxVerticalOverlap` and
  `android.util.Log`).
- `TemperatureExtrema` (only tie: `android.util.Log`).
- Enums/data: `TemperatureRole`, `TempLabelCandidate`, `HourData`, `PlacedLabelMeta`
  (rect→`GraphRect`), `LabelPlacementDebug`.
- `TemperatureLabelResolver` candidate selection/suppression/sort: `computeExtremaIndices`,
  `collectLabelCandidates`, `resolveExtremaRole`, `buildPotentialAnchors`,
  `deduplicateAnchors`, all `check*Suppression`, `sortLabelCandidates`,
  `findPrev/NextDifferent`. (Pure today except `Log` + `TemperatureGraphStyle.formatTemp`;
  move `formatTemp` or a small format helper to shared.)
- The **placement engine** — currently `placeSingleLabel`, `tryValleyBelowCascade`,
  `tryExactFitCurveAvoidance`, `CurveIntrusion` + `curveIntrusionInLabel` /
  `combinedCurveIntrusion`, `computeForcedAboveLowIndices`. These become
  `TemperatureLabelEngine.computePlacements(...)` that **records decisions instead of
  drawing** (replace each `canvas.drawText/drawLine` + `onLabelPlaced` with appending a
  `PlacedLabel`).

**Stays platform-specific:**
- `PaintSet`, `Canvas`, `Path`, all curve/fill/dashed-line drawing.
- `RenderContext` (Android keeps it; it wraps Canvas/Paint). The engine takes a small
  neutral input struct instead.
- `WeatherConditionColors` and color selection.
- `resolveCandidatePlacement`'s paint half: split into
  `resolveCandidateGeometry(...)` (shared: sx/sy/clampedX/isValley/isEssential/label/
  fetch-dot-dup-suppression, using injected `density` + `LabelTextMetrics`) and the
  adapter's paint/color resolution.

## Engine API sketch (shared)

```kotlin
object TemperatureLabelEngine {
    fun computePlacements(
        hours: List<HourData>,
        widthPx: Int, heightPx: Int, density: Float,
        originalPoints: List<Pair<Float, Float>>,
        forecastPoints: List<Pair<Float, Float>>,
        actualVisiblePoints: List<Pair<Float, Float>>,
        transitionX: Float?, fetchDotX: Float?, lastObservedTemp: Float?,
        observedAt: Long?, effectiveActualEndIndex: Int, fetchTime: LocalDateTime?,
        numColumns: Int,
        tempToY: (Float) -> Float,
        metrics: LabelTextMetrics,
    ): List<PlacedLabel>
}
```

Each platform: build inputs → call `computePlacements` → for each `PlacedLabel`, draw text
at `(x, baselineY)` in the platform color, and draw a leader line when `drawLeaderLine`.

## Critical files

- New (shared): `shared/.../graph/GraphRect.kt`, `.../graph/TemperatureLabelEngine.kt`,
  `.../graph/LabelTextMetrics.kt`, plus moved
  `GraphLabelPlacementUtils.kt` / `TemperatureExtrema.kt` / label models.
- `app/.../widget/TemperatureGraphRenderer.kt` — `placeTemperatureLabels` becomes a thin
  adapter (measure via Paint, call engine, draw decisions). Delete the moved private
  functions.
- `app/.../widget/TemperatureLabelResolver.kt` — keep `resolveCandidatePlacement`'s paint
  half; delegate selection/sort to shared.
- `desktop/.../TemperatureGraph.kt` — replace the ad-hoc label block (≈ lines 308–460) with
  an engine call + Compose drawing adapter.

## Incremental migration (each step compiles + keeps tests green)

1. Add `GraphRect` + `LabelTextMetrics` to `:shared`. Move `GraphLabelPlacementUtils`
   (RectF→GraphRect, Log→shim) and its tests; `:app` keeps using it via the new package.
2. Move `TemperatureExtrema` + label enums/models/`TempLabelCandidate`/`HourData` to
   `:shared`; fix imports in `:app`.
3. Move resolver selection/suppression/sort to `:shared`; split
   `resolveCandidatePlacement` into shared-geometry + app-paint.
4. Extract `TemperatureLabelEngine.computePlacements` (cascade + flip + curve avoidance +
   forced-above) returning `List<PlacedLabel>`; unit-test in `:shared`.
5. Rewrite Android `placeTemperatureLabels` as adapter; feed `onLabelPlaced` from returned
   decisions so existing Robolectric/JUnit assertions still pass.
6. Wire desktop `TemperatureGraph.kt` to the same engine + a Compose adapter → parity for
   free (cascade, flip, leaders, curve-graze).

## Test strategy

- The valley/collision tests (`TemperatureValleyBelowCascadeTest`,
  `TemperatureLabelCollisionOrderTest`, incl. the 2026-06-07 `forecast low flips above when
  it rounds equal…` regression) **move to `:shared`** as plain-JUnit engine tests — faster,
  no Robolectric, and they assert on `PlacedLabel`/`LabelPlacementDebug` decisions directly.
  With `GraphRect` doing real arithmetic, these become stronger than today's stubbed-`RectF`
  variants (see memories `[[renderer_test_color_is_zero]]`, `[[precip_rect_testability]]`).
- Keep a slim set of Android Robolectric tests as **adapter** integration (engine decisions
  → Canvas draws) — re-render via `reapply()` per `[[reapply_test_pattern]]`.
- Add a desktop test calling the engine with the same fixtures to prove cross-platform
  parity.
- Regression guard: snapshot `onLabelPlaced` output for a few real fixtures before/after
  the refactor and diff — placements must be byte-identical on Android.

## Verification (end to end)

1. `./gradlew :shared:test` — new engine unit tests green.
2. `./gradlew testDebugUnitTest` — all existing Android graph tests green (no behavior change).
3. `./gradlew :app:installDebug`; broadcast `com.weatherwidget.ACTION_REFRESH`; screenshot
   the emulator and confirm the `50°`/`49.8°` valley still renders as fixed (50° above).
4. `scripts/restart-desktop-distributable.sh`; confirm the desktop hourly graph now shows
   the same valley behavior (warmer low flips above, leader lines, no footer overlap).
5. Diff the captured `onLabelPlaced` snapshots — identical pre/post on Android.

## Risks / notes

- **Font metrics differ per platform** — the engine must consume *measured* ascent/descent/
  width (via `LabelTextMetrics`), never hardcode them. Desktop pixel positions will differ
  slightly from Android because fonts differ; that's expected and correct.
- **Scope is bounded but not tiny** (~600–900 lines extracted). Do it stepwise (above);
  each step is independently shippable.
- **Color is intentionally out of scope** for the engine — keep `WeatherConditionColors`
  Android-side; the adapter colors decisions. This avoids dragging android Color ints into
  `:shared`.
- `TemperatureGraphStyle` helpers used by the resolver (`formatTemp`, `dpToPx`, `withAlpha`,
  `tempToY`): `formatTemp` is pure (move a copy to shared); `dpToPx`/`tempToY` become
  injected inputs; `withAlpha` is color-side (stays in app).
