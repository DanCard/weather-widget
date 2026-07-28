# Code Review: TemperatureGraphRenderer.kt (Priority 1, file 3)

Source: `plans/260725-code-review-queue.md` (score 11/12)
Reviewed: 2026-07-28
File: `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` (1010 lines)

## Overall Assessment

The 1010-line size is misleading — most complexity lives in `:shared`
(`TemperatureLabelEngine`, `GhostLineLabel`, `YesterdayDeltaLabel`,
`GhostLineGate`, `GraphLayout`, `GraphLabelPlacementUtils`). This file is
mostly a thin canvas/paint adapter for shared logic. The "god class"
verdict from the queue doesn't really apply; the file is cohesive
(single responsibility: drive canvas drawing for the temperature graph).
Splitting would likely harm readability more than help.

Excellent test coverage — 14 test files covering label placement,
fetch dot, staleness, junctions, plateaus, dash continuity, yesterday
delta, clutter, wapi, actuals.

## Findings

### F1 — `drawFetchDot` recomputes the layout that `computeFetchDotBounds` already produced [HIGH]

`:837 drawFetchDot` and `:826 computeFetchDotBounds` both call
`resolveFetchDotLayout(ctx, hours)` independently. `renderGraph` calls
`computeFetchDotBounds` (`:976`) to seed `ctx.drawnLabelBounds`, then
calls `drawFetchDot` (`:982`) which re-resolves the same layout
(measuring text, computing dot radius, etc.). Two passes through
`resolveFetchDotLayout` per render.

**Fix:** Either cache the resolved `FetchDotLayout` (e.g. in `ctx` or
make `resolveFetchDotLayout` memoize on
`(observedAt, fetchDotX, lastObservedTemp)`), or restructure so
`computeFetchDotBounds` returns the layout and `drawFetchDot` accepts
it. Saves one full layout pass per render.

### F2 — `placeDayLabels` collision loop is O(N²) with monotonic bound list [MED]

`:663-665` re-evaluates `drawnIconBounds.any { RectF.intersects(it, bounds) }`
for every candidate × position. For each of `topB`/`midB`/`bottomB` for
each of 2 candidates, this is O(boundCount) — and
`ctx.drawnLabelBounds` grows monotonically through the render (every
placed label appends). Worst case ~12 hour temp labels + 1 fetch dot +
3 sub-bounds + N ghost labels = ~20+ bounds, times 6 candidate
positions = 120+ intersections per day-label pass.

Not a hot path but the structure is O(N²) and easy to fix with a
spatial index (or just accept it given N is small). The bigger smell is
that `drawnIconBounds` is passed in and also re-scanned — it's
immutable for the duration, so it could be pre-filtered by x-overlap
with the candidate.

**Fix (optional):** Pre-filter `drawnIconBounds` by x-overlap with the
candidate's x range before the .any() loop. Marginal; defer unless a
profile flags this phase.

### F3 — Magic numbers in `resolveValueLabelLayout` [MED]

`:705-733` layout function has implicit relationships: "right of dot
if fits, else left, else above" with no comment explaining the
priority order or why `aboveGap` is preferred over just placing below.
`VALUE_LABEL_BASELINE_DIVISOR = 3f` (`:147`) is the only documented
one.

**Fix:** Short doc explaining "we avoid below because the staleness
age label lives there" would help.

### F4 — `placeDayLabels` unconditionally falls through to bottom on collision [HIGH]

`:700-701`:
```kotlin
ctx.canvas.drawText(candidate.text, candidate.x, dayYBottom, paint)
ctx.onDayLabelPlaced?.invoke(DayLabelPlacementDebug(...))
```
When both `topB` and `midB` collide, the function unconditionally
falls through to `dayYBottom` and draws + logs — without checking if
`dayYBottom` also collides. So overlapping day labels can be drawn on
top of temperature labels at the bottom.

**Fix:** At minimum check `collides(bounds(dayYBottom))` and either
skip the draw or log a "forced overlap" reason in the debug callback.

### F5 — `drawHourLabelsAndIcons` re-derives icon tint inline [MED]

`:419-426` re-implements the same predicate→tint mapping that file 1's
F5 fix consolidated into `WeatherIconMapper.resolveDailyTextIconTint`,
but uses `HourlyGraphDefaults.ICON_TINT_*` constants instead of
resource ids.

**Fix:** Verify whether hourly icons truly need different constants
than daily text icons (probably yes — hourly icons appear next to the
curve, not in cells). If so, document the divergence; otherwise unify
via `WeatherIconMapper` or a sibling helper.

### F6 — `placeGhostLineLabel` allocates a Paint per render [LOW]

`:613-618`:
`Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)` returns a new
typeface each call (not cached by the system on older API levels).
`Paint(Paint)` also copies. Per render is probably OK (one allocation),
but if you ever render multiple ghost labels in a frame, hoist to
`PaintSet`.

**Fix (optional):** Hoist the italic ghost paint into `PaintSet` if a
profile shows allocation pressure. Otherwise defer.

### F7 — `LOGGED_ROLES` and `CURVE_AVOIDANCE_ROLES` are identical contents [LOW]

`:40-56` — two `setOf(...)` calls with the same 8 elements:
`{ACTUAL_END, ACTUAL_HIGH, ACTUAL_LOW, HIGH, LOW, LOCAL, START, END}`.

**Fix:** Declare one set, alias the other. Trivial dedup.

### F8 — `placeTemperatureLabels` ambiguous `paint` naming [LOW]

`:441 val paint = ctx.paints.actualTempLabelTextPaint` is only used as
a metrics source (ascent/descent at `:447-448`). Then drawing picks
`labelPaint` per-placement (`:480-485`). A reader might assume `paint`
is the actual-series label paint, not just a metrics source.

**Fix:** Rename to `metricsSourcePaint` or inline the two
`fontAscent(ctx.paints.actualTempLabelTextPaint)` calls.

### F9 — `TemperatureGraphStyle` forwarders add boilerplate [LOW]

`:150-165` — seven one-line forwarders to the same `TemperatureGraphStyle`
object (`formatAgeLabel`, `withAlpha`, `fontAscent`, `fontDescent`,
`ensurePaints`, `dpToPx`, `buildTempGradient`). 16 lines of pure
boilerplate.

**Fix (optional):** Status quo is fine for terseness. Could use
`with(TemperatureGraphStyle) { ... }` wrapping, but adds nesting.
Defer.

### F10 — Ghost line gate logic fragmented across three sites [MED]

`MIN_GHOST_LINE_DELTA` is defined here (`:31`), but the gate is
`shouldProcessGhostLine` (`:321-333`) delegates to shared
`GhostLineGate.shouldProcess`, then `drawFillAndCurves` re-checks
`abs(appliedDelta) >= MIN_GHOST_LINE_DELTA` locally (`:343`). Same
pattern at `:592` in `placeGhostLineLabel`. Three places applying
overlapping gate logic.

**Fix:** Consolidate into a single `GhostLineGate.shouldRender(...)`
that includes the delta threshold, in `:shared`.

## Implementation Priority

1. **F1** (perf, isolated) — duplicate layout pass per render
2. **F4** (correctness) — overlapping day labels at bottom
3. **F7** (trivial dedup) — role sets
4. **F5** (cohesion, ties to file 1) — verify hourly vs daily tint divergence
5. **F10** (cohesion) — gate consolidation in `:shared`
6. **F3** (docs) — magic numbers
7. **F8** (rename) — ambiguous `paint`
8. **F2, F6, F9** — defer unless profiled

## Verification (when implementing)

* `:app:compileDebugKotlin` + `:app:compileDebugUnitTestKotlin`
* `:app:testLongDebugUnitTest --tests "com.weatherwidget.widget.TemperatureGraph*"`
* `:shared:testShared` (if F10 touches shared gate logic)
* `:app:testShortDebugUnitTest`
