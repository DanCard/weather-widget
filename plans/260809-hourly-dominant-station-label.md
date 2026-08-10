# Hourly graph: dominant-station reading in empty space

**Date:** 2026-08-09
**Status:** implemented

## Goal

On the hourly temperature graph, print the station that dominates the observation blend and what it
actually read — e.g. `knuq 73.4°` — dropped into whatever empty space the plot has left. If no empty
space fits, draw nothing. Skip it entirely in the 3-day zoom view.

## Why this is nearly free

Every piece already exists; nothing new has to be computed or queried.

- **The number.** `ActualTemperatureSeriesBuilder.blendObservationSeries` already knows the dominant
  contribution behind an emitted blend point — it captures it on demand via
  `captureLatestDominantAtOrBeforeMs`, which the large-daily "today overlay" render path uses today
  (`TodayColumnOverlayContentResolver` → `DominantBlend.contribution`). The hourly path already runs
  that same blend on every render (`ActualTemperatureSeriesBuilder.build`); it just never asks for
  the dominant. Turning the flag on adds one `maxByOrNull` over the per-point weight array.
- **The empty space.** `ForecastDeltaLabel.place` is already a general "find a clear band in the
  plot" search: it walks a list of x-anchors, steps candidate boxes down a vertical band, rejects
  boxes intersecting any already-drawn obstacle, and scores the survivors by distance to the visible
  curve. Only its gate/format/color are delta-specific.
- **The 3-day skip.** `ForecastDeltaLabel.DELTA_LABEL_MAX_HOURS_SPAN` (25 h) already draws exactly
  the line the user asked for: it admits WIDE (24 h) and NARROW, and excludes THREE_DAY (72 h).
  Desktop's continuous zoom reaches 30 days back and is covered by the same span test.

## What changes

### 0. Shared — the collision bug the first cut exposed

The label shipped drawn **through the forecast dashes** on both Samsung and the emulator. Not a
placement bug — a sampling bug in the code the delta label had been using all along.

`ForecastDeltaLabel.place` took `curveYAt: (Float) -> Float?`, **one** y per x, and Android's
`sampleVisibleCurveY` answered it by modelling the graph as a single curve that switches from observed
to forecast at the fetch dot. But those are two lines drawn at the same time over overlapping x ranges.
Left of the fetch dot the sampler reported only the pink observed line, so a box up at the forecast
line's altitude scored a huge clearance and was drawn straight onto it. Desktop had the mirror defect:
`getCurveYAtX` samples the forecast curve only, so the observed line was the invisible one there.

The fix is in the shared contract, not the callers: `curveYAt` → **`curveYsAt: (Float) -> List<Float>`**,
every line drawn at that x, clearance taken as the minimum across them and vetoed if any passes through
the box. Both platforms grew a real multi-curve sampler (forecast always; observed up to the fetch dot;
the ghost line from the fetch dot rightwards, and only when its gate says it is actually painted). This
fixes the pre-existing delta label too, which was placed by the same blind search.

### 1. Shared — extract the empty-space search (new `GraphEmptySpaceFinder.kt`)

Pull the anchor/step/clearance search out of `ForecastDeltaLabel.place` into a reusable pure object:

```kotlin
object GraphEmptySpaceFinder {
    data class Metrics(width, ascent, descent) { val height get() = descent - ascent }
    data class Slot(centerX, baselineY, box: GraphRect)
    fun find(plot, drawnBounds, curveYsAt, metrics, padPx, xFractions, verticalSteps): Slot?
}
```

`ForecastDeltaLabel.place` keeps its `Metrics`, `Placement`, `X_FRACTIONS` and its gates, and delegates
the search (its `curveYAt` parameter becomes `curveYsAt` per section 0). Behavior is unchanged — `ForecastDeltaLabelTest` is the regression
net for the extraction. Doing this rather than copying the ~40-line search also keeps `cpdCheck`
green.

### 2. Shared — `DominantStationLabel.kt` (new)

- `format(stationId, rawTemp, useCelsius)` → `"knuq 73.4°"`. Station id **lowercased** (matches the
  user's example and reads quieter than `KNUQ` at 9sp); temperature via `TempUtils.formatTemp`, the
  same formatter `BlendTableFormatter.formatDominantTempAgeRows` uses for the daily overlay's
  station row, so the two views can never disagree by a rounding rule. `rawTemp` (the station's own
  reading), not `resolvedTemp` — again matching the daily overlay.
- `MAX_HOURS_SPAN = 25L`, its own constant with a comment tying it to the 3-day exclusion.
- `place(text, …)` → `Placement?`: null when there is nothing to say, when the span exceeds the gate,
  or when `GraphEmptySpaceFinder` finds no clear slot. It takes the finished string rather than
  id + temperature because the two platforms format at different layers (see section 4).
- **X-anchor preference is edge-first** — `listOf(0.22f, 0.78f, 0.35f, 0.65f, 0.5f)` — the mirror of
  the delta label's center-first list. The delta is placed first and registers itself as an
  obstacle, so the two would never overlap anyway; preferring opposite ends just stops them landing
  shoulder-to-shoulder when the plot is mostly empty.

### 3. Data plumbing — dominant station out of the hourly blend

- `ActualTemperatureSeriesResult` gains `latestDominantContribution: DominantBlend? = null`.
- `ActualTemperatureSeriesBuilder.build(...)` gains `captureLatestDominantAtOrBeforeMs: Long? = null`,
  forwards it to `blendObservationSeries`, and surfaces the result. Default null = every existing
  caller is byte-identical.

### 4. Android

- `TemperatureHourDataBuilder.buildHourDataResult` passes `captureLatestDominantAtOrBeforeMs = nowMs`
  and returns `dominantStation: DominantBlend?` on `BuildHourDataResult` — raw, not formatted: that
  builder deals only in canonical °F, and `useCelsius` is a required display-path param (see the
  celsius memory), so formatting belongs to the layer that holds the preference.
- `GraphLoadOutcome.Loaded` carries it to `TemperatureStateResolver`, which formats via
  `DominantStationLabel.format` and passes a new `renderGraph(dominantStationText = …)` string.
- `TemperatureGraphAnnotationRenderer.placeDominantStationLabel` draws it with
  `paints.stalenessTextPaint` **unrecolored** — that paint is already `COLOR_ACTUAL_LINE`, so the
  label reads in the observed-line color, which is exactly what it describes. (The delta label
  recolors that same paint to the thermostat gradient; leaving this one alone keeps the two
  visually distinct.)
- New obstacle type `DOMINANT_STATION`, registered after drawing so the ghost-line labels placed
  afterward route around it.
- Call site sits after `placeForecastDeltaLabel` and before `placeGhostLineLabel`: the delta gets
  first pick of the space, the station label second, ghost labels last.

### 5. Desktop

- `TemperatureGraph.kt` passes `captureLatestDominantAtOrBeforeMs = now` to its existing `build(...)`
  call and draws the label right after the delta label, same 9sp + shadow style, `COLOR_ACTUAL`,
  adding its box to `drawnLabels`.

### Deliberately not doing

- **No Settings toggle.** The daily today-column overlay has one per row
  (`show_today_overlay_dominant_temp`); this was not asked for and the label already self-suppresses
  when there is no room.
- **No age row.** The request was the temperature only.

## Verification

### Unit tests — `DominantStationLabelTest` (new)

1. `format` lowercases the id: `("KNUQ", 73.4f)` → `"knuq 73.4°"`.
2. `format` drops the decimal on a whole degree: `73.0f` → `"knuq 73°"`.
3. `format` converts under `useCelsius = true`.
4. Null dominant contribution → `place` returns null.
5. `spanHours = 72` (THREE_DAY) → null; `spanHours = 24` (WIDE) → non-null. **The 3-day requirement.**
6. `spanHours = 25` (boundary, inclusive) → non-null; `26` → null.
7. Plot narrower than the text plus padding → null.
8. A single obstacle covering the whole plot → null.
9. A curve threaded through every candidate box (`curveYsAt` returning mid-plot everywhere) → null.
10. Free plot → lands on the `0.22f` edge anchor, not the center (the anchor-order contract).
11. An obstacle over the left edge pushes it to the next anchor rather than returning null.
12. Returned `box` clears `padPx` from the curve at every sample.
13. `baselineY == box.top - ascent` (the Android/Compose dual-convention contract).

### Unit tests — `GraphEmptySpaceFinderTest` (new)

14. Prefers the earliest anchor in `xFractions` that has any room.
15. Within one anchor, picks the candidate with the greatest curve clearance, not the first fit.
16. A box straddling the curve is rejected; boxes wholly above and wholly below both report a gap.
17. Anchors are clamped so the box never leaves the plot horizontally.
18. **A second curve vetoes a slot the first leaves open** — the shipped bug, pinned directly.
19. **A second curve threading the only candidate band blocks placement entirely.**
20. Clearance under `padPx` is rejected even though the box clears the curve.

Two earlier drafts of tests 16 and 20 passed for the wrong reason: the plot was shorter than
`box + 2 * pad`, so `find` bailed before ever sampling the curve. Both now use a plot exactly
`box + 2 * pad` tall (one candidate box, curve through it) and assert the sampler was actually called.

### Regression

21. `ForecastDeltaLabelTest` passes with only its sampler lambdas retyped — the proof the extraction
    was behavior-neutral. ✅
22. `./gradlew :shared:test` ✅ · `./gradlew :app:testDebugUnitTest` — 1893 tests, 1 failure, and it is
    the pre-existing `LocaleResourceParityTest` (the Tip Jar `support_development_*` strings from
    a772b12b are untranslated). Unrelated to this change.
23. `./gradlew cpdCheck` ✅ — the extraction exists partly to keep this green.
24. The span gate was verified to be load-bearing: stubbing `if (spanHours > maxSpanHours) return null`
    to a no-op fails 2 tests.

### Manual

25. `./gradlew installDebug` on the Samsung Fold, the Pixel and the emulator. Before the sampler fix,
    `knuq 73.4°` sat on the forecast dashes on both the Fold and the emulator; after, it lands in the
    clear band under the observed line on both. Screenshots captured on each. ✅
26. `scripts/buildStart-desktop.sh` — built and restarted through the repo autostart launcher. ✅
27. Still to check by hand: cycle WIDE → NARROW → THREE_DAY and confirm the label retires in the 3-day
    view (the gate is unit-tested, but has not been eyeballed on-device); wheel-zoom desktop past ~25 h;
    cross-check the printed station and temperature against Observations → Blend's top-weight row.

## Follow-ups

- If the label proves too easy to hide behind a busy curve, consider letting it fall back to the
  footer strip rather than vanishing.
- `GhostLineLabel` still uses the old single-answer `sampleVisibleCurveY`. Its labels sit right of the
  fetch dot where that model is roughly right, and it has its own tuned placement, so it was left
  alone deliberately — but it is the last caller of the blind sampler and worth a look.
