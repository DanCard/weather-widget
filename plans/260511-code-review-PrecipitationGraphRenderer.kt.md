# Code Review: `PrecipitationGraphRenderer.kt`

## Context

This file (`app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`, ~1040 lines) was last refactored in commit `efc6295` ("Refactor PrecipitationGraphRenderer: remove dead code, narrow visibility, deduplicate computations, introduce GraphGeometry parameter object"). The user has requested a fresh review to identify what's still rough.

Scope: code quality, internal consistency, alignment with sibling renderers (`TemperatureGraphRenderer`, `CloudCoverGraphRenderer`), and small correctness foot-guns. Not a rewrite.

External API surface in use:
- `renderGraph(...)` — only called by `widget/handlers/PrecipViewHandler.kt`.
- `calculateLayout`, `findVisibleWindowRainPeriods`, `findFixedWindowRainPeriods`, `calculateProbabilityLabelPlacements`, `calculateRainAmountPlacements` — `internal`, used only from tests.

Sibling-renderer baseline:
- `TemperatureGraphRenderer.kt`, `CloudCoverGraphRenderer.kt` follow the same pattern (object singleton, `PaintSet` cache, `GraphRenderUtils` / `GraphLabelPlacementUtils` / `HourlyGraphDefaults`), but use Android's `RectF` directly throughout.

---

## Findings

Severity legend: **[H]** correctness or measurable maintainability, **[M]** clean-up worth doing soon, **[L]** style/nit.

### 1. [H] `PrecipRect` is a redundant abstraction that costs more than it gives

`PrecipRect` (lines 100–115) duplicates Android's `RectF`. It exposes only `intersects()` (which `RectF` already has) and a pair of `toRectF()` / `fromRectF()` converters that exist purely to bridge back to the rest of the codebase.

Direct cost: ~12 explicit `.toRectF()` / `.fromRectF()` call sites and several `.map { it.toRectF() }` traversals (e.g. lines 392, 436–437, 451, 969). Indirect cost: two parallel collision-bound lists (`PrecipRect` vs `RectF`) in `renderGraph` and `calculateLayout`.

The sibling renderers use `RectF` directly with no apparent ill effect. The only differentiator of `PrecipRect` is immutability — a fair value, but achievable with discipline (don't mutate the `RectF` you put into a collision list) without paying the translation tax.

**Recommendation:** replace `PrecipRect` with `RectF` throughout. Delete `toRectF` / `fromRectF` and the `.map { it.toRectF() }` translations. Use `RectF.intersect(other: RectF)` (boolean overload) or keep a tiny private extension `fun RectF.intersectsRect(o: RectF): Boolean = left < o.right && right > o.left && top < o.bottom && bottom > o.top` if the mutating-intersect semantics of the platform method are a footgun.

If you want immutability stronger than `RectF` offers, leave a single `PrecipRect` value class and only construct it at boundaries — but the current usage doesn't justify that.

### 2. [H] Icon-bounds computation is duplicated between `calculateLayout` and `renderGraph`, and the two have already started to drift

`calculateLayout` pre-computes icon bounds at lines 342–353:

```kotlin
val iconY = graphBottom
val iconX = clampedX - iconSize / 2f
drawnIconBounds.add(PrecipRect(iconX, iconY, iconX + iconSize, iconY + iconSize))
```

`renderGraph` recomputes them at lines 932–936:

```kotlin
val iconSize = dpToPx(context, HourlyGraphDefaults.WEATHER_ICON_SIZE_DP).toInt()
val iconY = layout.graphBottom + dpToPx(context, 0f)   // <-- dead +0 offset
val iconX = clampedX - iconSize / 2f
val iconRect = RectF(iconX, iconY, iconX + iconSize, iconY + iconSize)
```

Two issues:
- `+ dpToPx(context, 0f)` is unconditionally zero — leftover scaffolding from a feature that was removed. Drop it.
- The bounds are recomputed instead of being pulled from `layout`. Expose the icon bounds on `PrecipGraphLayout` (e.g. `iconBounds: List<RectF>`) and reuse — exactly the deduplication direction the recent refactor started.

### 3. [M] `isFarOutData` midpoint calculation is unnecessarily indirect

Lines 198–203:

```kotlin
val isFarOutData = hours.isNotEmpty() && abs(
    java.time.Duration.between(
        hours.first().dateTime.plusHours(hours.size.toLong() / 2),
        currentTime,
    ).toHours()
) > FAR_OUT_DATA_HOURS_THRESHOLD
```

This computes "first hour plus N/2 hours" as a proxy for the middle of the data window. It is correct *only* under the implicit assumption that hours are exactly one hour apart starting from `hours.first().dateTime` — a contract neither documented nor enforced. The straightforward expression is:

```kotlin
val midDateTime = hours[hours.size / 2].dateTime
val isFarOutData = abs(Duration.between(midDateTime, currentTime).toHours()) > FAR_OUT_DATA_HOURS_THRESHOLD
```

Same answer for the happy path, and it survives any future input where the cadence isn't strictly hourly (e.g. half-hourly observations). Also: hoist `java.time.Duration` to the imports.

### 4. [M] `TextMeasurer` exposes silently-wrong fallback defaults

Lines 148–151:

```kotlin
val measureNowText: (String) -> Float = { 15f },
val getNowTextBounds: (String) -> Pair<Float, Float> = { -12f to 3f },
val measureDayText: (String, Boolean) -> Float = { text, _ -> text.length * 8f },
val getDayTextBounds: (Boolean) -> Pair<Float, Float> = { _ -> -10f to 2f },
```

These defaults make `TextMeasurer` constructible without real Paint instances, which is nice for tests, but they will silently render with garbage measurements if production code ever forgets a field. Two paths:

- **Make them required** (drop the defaults). `renderGraph` already provides every field, so production breaks nothing. Tests that needed fakes already pass fakes — only the lazier tests will be forced to write a one-liner stub.
- **Or, isolate them in a `TextMeasurer.Companion.testStub()` factory.** Same effect for tests, clean production type.

Either is better than leaking guess-based defaults into the production API.

### 5. [M] `renderGraph` log tag is inconsistent with `TAG`

Line 956 hardcodes `"PrecipGraph"`:

```kotlin
Log.d("PrecipGraph", logMsg)
```

while every other call site in this file uses the `TAG = "PrecipGraphRenderer"` constant (line 20). Replace the literal with `TAG`. Trivially fixable; the inconsistency makes logcat filtering painful.

### 6. [M] `bestX`/`bestY`/`bestBounds` triple-nullable in `calculateRainAmountPlacements` is brittle

Lines 766–816 track `var bestX: Float? = null; var bestY: Float? = null; var bestBounds: PrecipRect? = null; var bestOverlapArea = Float.MAX_VALUE`. The three are always set together, then unwrapped together at line 816. Bundle them:

```kotlin
data class RainCandidate(val x: Float, val y: Float, val bounds: PrecipRect, val overlapArea: Float)
var best: RainCandidate? = null
// ... inside the loop:
if (overlapArea < (best?.overlapArea ?: Float.MAX_VALUE)) {
    best = RainCandidate(cx, cy, candidateBounds, overlapArea.toFloat())
}
```

Then a single null-check unwraps everything. This is the same pattern `GraphRenderUtils` uses for similar "best placement found so far" loops.

### 7. [M] Two nearly identical "run-finder" loops could be one helper

Lines 250–265 walk `labelSignal` looking for plateau runs surrounded by higher values (soft-dip candidates). Lines 268–278 walk the same list looking for zero runs surrounded by positives. They share structure (`while (i < size)`, identify run boundaries, conditionally emit midpoint). Extracting:

```kotlin
private inline fun findRunMidpoints(
    items: List<Int>,
    matches: (Int) -> Boolean,
    flank: (left: Int, right: Int, runStart: Int, runEnd: Int) -> Boolean,
): List<Int>
```

removes ~25 lines and makes the *intent* of each call site visible (the predicate names the run kind). Optional — not worth doing if it complicates `GraphRenderUtils` for one caller. Defer.

### 8. [L] `Math.max` / `Math.min` in Kotlin code

Lines 798–801 use `Math.max(...)` / `Math.min(...)`. Kotlin's stdlib offers `maxOf` / `minOf`. Doesn't change behavior; consistency only.

### 9. [L] Per-iteration `dpToPx` calls inside hot label-placement loop

In `calculateProbabilityLabelPlacements` (lines 670–671 inside the `for (index in filteredCandidates) { for ((attemptIndex, placeAbove) in directions.withIndex()) { ... } }` nested loop), `dpToPx` is called repeatedly with the same dp value across iterations. The values from `GraphLabelPlacementUtils.getLabelGapDp` are conditional on `isFallback`, so two precomputed pairs (`gapPxNormal`, `gapPxFallback`) above the loop cover all cases. The visible payoff is small (renderer runs once per widget update, not per frame) — flag, don't insist.

### 10. [L] `placed = true; break` semicolon-on-one-line oddity

Line 471: `watermarkPlacement = ...; placed = true; break` — works but reads worse than splitting onto three lines. Cosmetic.

### 11. [L] Magic constants documented only by their names

`SOFT_DIP_MAX_PROBABILITY = 65`, `SOFT_DIP_MIN_ELEVATION = 8`, `ELEVATED_PEAK_PROBABILITY_RANGE = 55..85`, `ELEVATED_PEAK_MIN_DELTA = 10` — all reasonable, all chosen presumably by visual experimentation. Per project guideline ("only comment when WHY is non-obvious"), these don't *need* comments, but if you ever tune them, a single-line `// chosen by visual tuning on Pixel 7, see plans/...` would save the future-you a `git blame`. Optional.

### 12. [L] `LabelPlacementDebug` and friends are part of the public-ish surface but are debug-only

The `LabelPlacementDebug`, `DayLabelPlacementDebug`, `WatermarkPlacementDebug`, `NowLabelPlacementDebug` data classes (lines 61–98) are exposed at object scope. They're not used by `PrecipViewHandler` — only by tests and by `onLabelPlaced` / `onDayLabelPlaced` callbacks in `renderGraph`. They could live in a separate file or nested in a `Debug` namespace; not load-bearing, just clarity. Defer.

---

## Critical files

- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt` — the subject; all findings target this file.
- `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt` — sole production caller; sanity-check any API changes here.
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`, `CloudCoverGraphRenderer.kt` — reference for `RectF` usage style.
- `app/src/test/java/com/weatherwidget/widget/PrecipitationGraph*Test.kt`, `RainPeakLabelRoboTest.kt`, `PrecipitationGraphWatermarkTest.kt` — exercise `calculateLayout`, `PrecipHourData`, and the debug structs. Any signature change in those types will touch these.

## Verification

After acting on any subset of these findings:

1. Unit tests:
   ```
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.PrecipitationGraph*"
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.RainPeakLabelRoboTest"
   ```
2. Build the APK: `./gradlew installDebug`.
3. Visual check: pick a widget with rain in the next 48h. Inspect:
   - probability labels are placed (no truncation, no overlap),
   - rain-amount text appears once per visible rain period,
   - the NOW indicator and day labels still render on the right sides,
   - the rain-cloud watermark is placed when there are enough hours.
4. If `PrecipRect` is removed (finding #1): re-run all tests under `app/src/test/.../widget/` to catch any test that imported `PrecipRect` or relied on its `intersects` semantics.

## Out of scope

- Performance profiling of `renderGraph` (already runs once per widget update, not a hot path).
- API redesign of the sibling renderers to bring them into a shared base class — separate effort; flagged only as a future direction if findings #1 + #2 surface duplicated infrastructure across all three renderers.

---

## Implementation order (user requested all 12 findings)

The fixes split into independent units. I'll execute in this order so each step can be tested before the next builds on it.

### Step A — Trivial, no behavior change
- **#5** Replace `"PrecipGraph"` literal at line 956 with the `TAG` constant.
- **#2 (partial)** Drop the dead `+ dpToPx(context, 0f)` at line 933.
- **#8** Swap `Math.max` / `Math.min` for `maxOf` / `minOf` at lines 798–801.
- **#10** Split `placed = true; break` at line 471 onto its own lines.
- **#3** Replace the indirect `isFarOutData` midpoint with `hours[hours.size / 2].dateTime`; hoist `java.time.Duration` import.

Test gate: `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.PrecipitationGraph*"`.

### Step B — `PrecipRect` removal (finding #1) — **REJECTED**

Attempted, reverted. Finding #1 was wrong: `PrecipRect` is the seam that lets `PrecipitationGraphRendererTest` and `PrecipitationGraphWatermarkTest` run in plain JUnit. Android's `RectF` is stubbed in the non-Robolectric test build (constructor is a no-op, fields stay at 0, static `RectF.intersects` returns default `false`) — every collision-driven placement silently breaks. 11 of 34 unit tests failed under the swap; revert restored green.

Memory recorded at `/home/dcar/.claude/projects/-home-dcar-projects-weather-widget/memory/precip_rect_testability.md` to prevent future re-attempts.

### Step C — Icon-bounds deduplication (finding #2, remainder)
- Add `iconBounds: List<PrecipRect> = emptyList()` to `PrecipGraphLayout` (PrecipRect, not RectF — see Step B).
- Populate it in `calculateLayout` (lines 342–353 already do the work — just expose the list).
- In `renderGraph`, drop the recomputation inside the `drawHourLabels` icon-render callback; pull from `layout.iconBounds[index].toRectF()` instead. The `drawnIconBounds: MutableList<RectF>` local can also go away — `layout.iconBounds` is the source of truth.

Test gate: rebuild + visual check (icons still render, no overlap with labels).

### Step D — `TextMeasurer` defaults (finding #4)
Drop the silent fallback defaults on `measureNowText`, `getNowTextBounds`, `measureDayText`, `getDayTextBounds`. Production already supplies them. Any test that constructs a `TextMeasurer` with missing fields will fail to compile — fix those test sites with explicit stubs (the same constants previously used as defaults are fine, just now opt-in).

### Step E — `bestX/bestY/bestBounds` bundling (finding #6)
Inside `calculateRainAmountPlacements`, introduce a local `data class RainCandidate(val x: Float, val y: Float, val bounds: PrecipRect, val overlapArea: Float)` and collapse the four `var`s into one nullable.

### Step F — Run-finder helper (finding #7) — **SKIPPED**

The two run-finders share *outer* shape (walk, find consecutive run, check flank, emit midpoint) but the inner predicates differ enough (constant-value vs. matching-predicate run extension; window-elevation vs. immediate-neighbor flank) that a unified helper needs three lambda parameters. The helper + two call sites total more lines than the two original loops. Finding's own recommendation: "defer if it complicates the helper for one caller" — applied.

### Step G — Cosmetic
- **#9** Hoist the two `dpToPx(gapDp.aboveDp)` / `dpToPx(gapDp.belowDp)` computations out of the inner direction loop in `calculateProbabilityLabelPlacements`.
- **#11** Add brief `// chosen by visual tuning` comments next to `SOFT_DIP_MAX_PROBABILITY`, `SOFT_DIP_MIN_ELEVATION`, `ELEVATED_PEAK_PROBABILITY_RANGE`, `ELEVATED_PEAK_MIN_DELTA`. Single-line each; no rationale beyond "tuned, not principled."
- **#12** Move `LabelPlacementDebug`, `DayLabelPlacementDebug`, `WatermarkPlacementDebug`, `NowLabelPlacementDebug` into a new top-level file `PrecipGraphDebug.kt` (or nest under a `Debug` object inside this file). Keep them in the same package so tests don't need import changes beyond a re-resolve.

### Final verification
1. `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.*"` — all unit tests pass.
2. `./gradlew installDebug` — APK builds.
3. Visual smoke test on the running emulator: rain widget shows probability labels, rain-amount text, NOW marker, day labels, and watermark in expected positions. Compare side-by-side with the pre-change build if anything looks off.
