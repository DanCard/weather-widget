# Dominant-Station Reading on the Hourly Graph (and the Collision Bug It Exposed)

**Date:** August 9, 2026
**Devices:** Samsung Galaxy Z Fold (SM-F936U1), Pixel 7 Pro, emulator-5554, desktop
**Status:** Implemented, unit-tested, installed on all three Android targets and running on desktop (uncommitted)
**Plan:** [plans/260809-hourly-dominant-station-label.md](../plans/260809-hourly-dominant-station-label.md)

---

## 1. Request

> Find some empty space in the hourly temperature graph to state the dominant station temperature.
> If no space found don't state it. Skip it for the 3 day zoom view. Maybe just state it as
> "knuq 73.4" or something like that.

The observed line is an IDW blend of every nearby station, so on a normal day it matches no single
thermometer. Naming the station that dominates the mix is the difference between "the app is wrong"
and "the app is averaging in a station two towns over."

## 2. Why it was nearly free

Every piece already existed and only needed wiring together.

- **The number.** `ActualTemperatureSeriesBuilder.blendObservationSeries` already captures the
  dominant contribution on demand via `captureLatestDominantAtOrBeforeMs` — the large-daily
  today-column overlay uses it today. The hourly path already runs that same blend on every render;
  it just never asked. Turning the flag on costs one `maxByOrNull` over the per-point weight array.
- **The empty space.** `ForecastDeltaLabel.place` was already a general "find a clear band in the
  plot" search, with only its gate/format/color delta-specific.
- **The 3-day skip.** `DELTA_LABEL_MAX_HOURS_SPAN` (25 h) already drew exactly the requested line:
  admits WIDE (24 h) and NARROW, excludes THREE_DAY (72 h). It also covers desktop's continuous
  zoom, which has no discrete stage to test against.

## 3. The bug the first cut exposed

The label shipped drawn **through the forecast dashes** on both the Fold and the emulator.

Not a placement bug — a *sampling* bug, in code the delta label had been using all along.
`ForecastDeltaLabel.place` took `curveYAt: (Float) -> Float?`: **one** y per x. Android's
`sampleVisibleCurveY` answers it by modelling the graph as a single curve that switches from
observed to forecast at the fetch dot. But the hourly graph paints up to **three** temperature lines
over overlapping x ranges:

| line | x range |
|---|---|
| forecast (grey/orange dashed) | whole window |
| observed (pink) | up to the fetch dot |
| ghost/expected (faint dotted) | fetch dot rightwards, and only when its gate passes |

Left of the fetch dot the sampler reported the pink line only, so a box up at the forecast line's
altitude scored a huge clearance and was drawn straight onto it. Desktop had the **mirror** defect:
`getCurveYAtX` samples `coords` (forecast) only, so the observed line was the invisible one there.

Same bug, opposite blind spot — which is why the fix went into the shared contract rather than
patching either caller. This was a pre-existing defect in the delta label too; the new label just
happened to land somewhere that exposed it.

## 4. What changed

| File | Change |
|---|---|
| `shared/graph/GraphEmptySpaceFinder.kt` (new) | Empty-space band search extracted out of `ForecastDeltaLabel.place`; takes `curveYsAt: (Float) -> List<Float>` and vetoes a slot if **any** line passes through it |
| `shared/graph/DominantStationLabel.kt` (new) | `knuq 73.4°` format, 25 h span gate, edge-first x-anchors |
| `shared/graph/ForecastDeltaLabel.kt` | Delegates the search; `curveYAt` → `curveYsAt` |
| `shared/actuals/ActualTemperatureSeriesBuilder.kt` | `build()` gains opt-in `captureLatestDominantAtOrBeforeMs`; result gains `latestDominantContribution` |
| `widget/handlers/TemperatureHourDataBuilder.kt` | Opts in; returns `dominantStation: DominantBlend?` (raw °F — `useCelsius` is a display-path concern) |
| `widget/handlers/TemperatureStateResolver.kt` | Formats with the unit preference, passes `dominantStationText` to `renderGraph` |
| `widget/TemperatureGraphAnnotationRenderer.kt` | New `placeDominantStationLabel` + the multi-curve `visibleCurveYs` sampler both labels now use |
| `widget/TemperatureGraphObstacleRegistry.kt` | New `DOMINANT_STATION` obstacle type |
| `desktop/TemperatureGraph.kt` | Same label after the delta label; new `visibleCurveYsAt` sampler; ghost-line-drawn flag hoisted so the sampler knows whether that line is painted |

**Details worth keeping:**

- **Text** is the station's **raw** reading through `TempUtils.formatTemp` — the same value and
  formatter behind the daily overlay's station row, so the two surfaces can't disagree by a rounding
  rule. Raw, not the value fed to the blend: an extrapolated value is a forecast in disguise, and
  naming a station beside it would be a lie. Station id lowercased.
- **Color** is `stalenessTextPaint` **unrecolored** — that paint is already the observed-line color,
  which is exactly what the label explains. (The delta label recolors the same paint to the
  thermostat gradient; leaving this one alone keeps the two readable as different things.)
- **Anchor order** is edge-first (`0.22, 0.78, 0.35, 0.65, 0.5`), the mirror of the delta label's
  center-first list, so the two drift to opposite ends of an empty plot.
- **Draw order** is a priority ladder over the same free space: delta first, station label second,
  ghost labels last.
- **Not done, deliberately:** no Settings toggle (the daily overlay has one per row; not asked for,
  and the label already self-suppresses when there's no room) and no age row.

## 5. Verification

**Automated**

- `DominantStationLabelTest` (18 cases) — format, the 3-day gate and its inclusive boundary, plot too
  narrow/short, obstacles, curve intrusion, sub-pad clearance, anchor order and fallthrough, the
  baseline/box dual-convention contract.
- `GraphEmptySpaceFinderTest` (15 cases) — including two that pin the shipped bug directly: a second
  curve vetoing a slot the first leaves open, and a second curve through the only candidate band
  blocking placement entirely.
- `ForecastDeltaLabelTest` passes with only its sampler lambdas retyped — the proof the extraction
  was behavior-neutral.
- `./gradlew :shared:test` ✅ · `./gradlew cpdCheck` ✅ (the extraction exists partly to keep this green)
- `./gradlew :app:testDebugUnitTest` — 1893 tests, **1 failure: pre-existing and unrelated.**
  `LocaleResourceParityTest` reports the Tip Jar `support_development_*` strings from a772b12b are
  untranslated in all 19 locales.
- The span gate was checked to be load-bearing: stubbing it to a no-op fails 2 tests.

**Test trap found along the way:** two draft cases passed for the wrong reason — the plot was shorter
than `boxHeight + 2*padPx`, so `find` bailed at the size check *before ever sampling the curve*. Both
now use a plot exactly `boxHeight + 2*padPx` tall (one candidate box, curve through it) and assert the
sampler was actually called.

**On-device**

- Fold + emulator: `knuq …°` sat on the forecast dashes before the sampler fix; after, it lands in the
  clear band under the observed line on both. Screenshots captured on each.
- Desktop rebuilt via `scripts/buildStart-desktop.sh` and restarted through the repo autostart launcher.
- Pixel 7 Pro screenshot came back all black (screen off), so it is **unverified** there.

**Still to eyeball**

- Cycle WIDE → NARROW → THREE_DAY on-device and watch the label retire in the 3-day view (the gate is
  unit-tested but has not been seen on a screen).
- Wheel-zoom desktop past ~25 h and confirm it drops out.
- Cross-check the printed station and temperature against Observations → Blend's top-weight row for
  the same minute.

## 6. Follow-ups

- `GhostLineLabel` is the last caller still on the single-answer `sampleVisibleCurveY`. Its labels sit
  right of the fetch dot where that model is roughly right, and it has its own tuned placement, so it
  was left alone deliberately — but it's worth a look.
- If the label proves too easy to hide behind a busy curve, consider falling back to the footer strip
  rather than vanishing.
- Memory written: `free-label-collision-needs-all-curves`.
