# Today-column overlay: header-aware ABOVE ceiling (Samsung fold shows nothing above)

Date: 2026-08-07
Device: Samsung fold `RFCT71FR9NT` (SM-F936U1), NWS, daily view, APK built 10:13 (HEAD + the
uncommitted `HEADER_BAND_RECLAIM_FRACTION = 0.2`).

## Problem

On the fold **all three overlay rows render across the forecast bars** — `-2.5 fcst`, `66.2°`, `5m`
are all `ON_COLUMN` — while a clearly empty band sits above the column. User: "seems like one row of
text above column could fit easily."

The emulator, on the same code, already puts one row (`-1.8 fcst`) above; this is a fold-specific
shortfall, not a general regression.

## Measurements (logcat `TodayColumnOverlay`, bitmap px)

Fold, `placements=[delta:ON_COLUMN, dominant_temp_age:ON_COLUMN]`:

| quantity | value |
|---|---|
| `graphTop` (reserved header band) | 51.55 |
| `aboveCeiling` = `graphTop × (1 − 0.2)` | 41.24 |
| today `1%` rain chip (box) | 57.89 – 66.26 |
| today `82°` high label (box) | 63.17 – 102.39 |
| **free run ABOVE** | **41.24 – 57.89 = 16.65** |
| one overlay row: box / ink | 30.19 / ≈20.2 |
| delta + temp (2 rows, ink) | ≈51.9 |
| all three rows (ink) | ≈81.6 |

So one row misses by ~3.6 px. Three independent causes, in order of size:

1. **The ceiling is a blind global fraction.** It reclaims a fixed quarter (currently a fifth) of the
   header band regardless of whether anything is drawn overhead. Over today's column on this layout
   the header draws *almost* nothing: the left cluster (`☀ 66.7° -1.3`) ends at x ≈ 134 against a
   stack spanning x 125.1 – 196.7, and `Fri 7` is centred far to the right. Header ink bottoms out
   around y ≈ 24, so ~17 px of genuinely empty band is fenced off by the constant.
2. **Obstacles are still font boxes, not ink.** Commit `276bec49` taught the overlay to measure
   *itself* on ink, but `DailyTemperatureLabelRenderer` (`baselineY + fontAscent … fontDescent`) and
   `DailyForecastRainLabelRenderer` (`baseline + ascent … descent`) still hand over boxes. The `1%`
   chip's box starts 1.5 px above its glyphs; the `82°` label's box starts ~7 px above its digits.
   This is the *other half* of the double-count the ink fix addressed.
3. **`ABOVE` hugs the top of its run.** Correct when the run was barely taller than the stack, wrong
   once the ceiling opens up: it would fling the text to the widget's top edge, level with the header
   row — the look the 0.5 reclaim experiment was rejected for.

## What changes

Simplified after user review 2026-08-07 ("what is meant by ~17 px reserved against a phantom? Can we
just get rid of that?"). The original draft resolved the header as three per-column rects and opened
the ceiling to the bitmap top. Measuring the header's ink bottom as a single full-width band gets the
row with far less machinery, and is safe by construction: a band spanning the whole width cannot be
dodged sideways into a collision, so no obstacle geometry is needed at all.

1. **`DailyForecastHeaderRenderer.resolveHeaderInkBottom(header, widthPx, layout, xLeft, xRight)`**
   (new, `internal`). The lowest ink any drawn header item reaches **within the column's x-range** —
   icon, current temp, delta and its caption, precip, date, API label, gear — computed from the same
   paints, `labelScale`, `upOffset` and cursor walk that `drawHeader` uses. Resolved from the
   *pre-suppression* `headerData`, which is a superset: over-reporting can only push the overlay
   down, never into a collision.

   **The x-range is not a refinement, it is the fix.** This shipped full-width first and measured
   30.62 px on the fold — the 24dp weather icon at x ≈ -6..27, against a Today column at x 122..199 —
   which left the ABOVE run 0.3 px short of a row: still broken, having reserved space for an item
   nowhere near the column. Scoped to the column it measures 22.49 px (the current temperature) and
   the row fits with ~5 px to spare.
2. **`DailyForecastGraphRenderer`** passes it to `TodayColumnOverlayRenderer.draw`, which uses it as
   `aboveCeiling`. `HEADER_BAND_RECLAIM_FRACTION` survives only as the fallback when `headerData` is
   null (no header measured), and is restored to `0.25` — the uncommitted `0.2` moved the ceiling
   the wrong way.
3. **`TodayColumnOverlayPlanner.layOut`**: `ABOVE` hugs the *bottom* of its run less `Input.padding`
   of clearance, instead of the top. Reverses point 3 of `276bec49` deliberately — that rule existed
   to gain distance from the bar cap when the run was ~3 px roomier than the stack; with a taller run
   it maximises distance from the column the text annotates, and would slide the emulator's delta up
   under the header. `BELOW` and `ON_COLUMN` unchanged.

Deferred (not needed once the ceiling is measured): trimming the high-label and rain-chip obstacle
bounds to ink, and per-column header rects that would also free the space *beside* the header text.

## Expected result

| | before | after |
|---|---|---|
| fold ABOVE run | 41.24 – 57.89 (16.7) | ≈24.7 – 57.89 (33.2) |
| fold placement | all three rows on bars | `-2.5 fcst` ABOVE, `66.2°`/`5m` on bars |
| emulator ABOVE run | 38.40 – 67.79 (29.4) | ≈25 – 67.79 (42.8) |
| emulator placement | delta ABOVE, temp/age on bars | unchanged (bottom-hugging holds it in place) |

Two rows above the fold's column needs ≈51.9 px against ≈35.4 available — not reachable without
suppressing the `1%` chip. Out of scope: the ask is one row.

## Not doing (and why)

- **Raising the reclaim fraction alone** (0.2 → ~0.5). Same result on this layout, one line, but
  blind: the fold's header left cluster reaches x ≈ 134 and the stack starts at x 125.1, so a 0.5
  ceiling puts the delta row *into* `-1.3`. The collision it risks is on the very device being fixed.
- **Splitting `dominant_temp_age` into two placeable lines** so `66.2°` could join the delta above.
  Real (it is what would let the emulator put two rows up), but it separates the reading from its
  age, and the ask is one row. Follow-up.
- **Font shrinking** — removed at user request; not revisiting.

## Verification

- `TodayColumnOverlayPlannerLayoutTest`: new fold fixture from the logcat numbers above asserting
  `delta` lands `ABOVE`, plus a paired control at the old ceiling asserting it still reproduces the
  all-`ON_COLUMN` split, so the positive test cannot pass vacuously (same discipline as `276bec49`).
- New `layOut` alignment tests: `ABOVE` bottom-hugs with `padding` clearance; `BELOW`/`ON_COLUMN`
  unchanged.
- New `DailyForecastHeaderRendererTest` cases for `resolveHeaderObstacles`: left cluster with and
  without the delta caption, date present/absent, right cluster.
- Robolectric `DailyLargeTodayLayoutRoboTest` still green (no font engine → leading 0).
- Device check: install on the fold, screenshot, confirm `-2.5 fcst` is above the column and the
  `TodayColumnOverlay` log reports `delta:ABOVE`. Re-check the emulator for no regression.
- Desktop: shared planner change only (alignment). Desktop keeps `aboveCeiling = graphTop` — it has
  no header obstacles — and still packs boxes, so its behaviour changes only by the ABOVE alignment.

## Status

**Implemented and verified on device, 2026-08-07.** Uncommitted.

Measured after the change (logcat, same fold widget):

```
before  headerInkBottom=—         aboveCeiling=41.24  placements=[delta:ON_COLUMN, dominant_temp_age:ON_COLUMN]
full-w  headerInkBottom=30.62     aboveCeiling=39.72  placements=[delta:ON_COLUMN, dominant_temp_age:ON_COLUMN]
final   headerInkBottom=22.49     aboveCeiling=31.59  placements=[delta:ABOVE,     dominant_temp_age:ON_COLUMN]
```

Emulator regression check: `headerInkBottom=22.34`, `aboveCeiling=30.22`, still
`[delta:ABOVE, dominant_temp_age:ON_COLUMN]`, delta box top 33.70 → 31.88 — bottom-hugging held it
in place as intended.

Tests: `:shared` suite green (4 new — fold ceiling fixture, its paired old-ceiling control, open-
ceiling bottom-hug, and slack-smaller-than-padding); `:app:testDebugUnitTest` green (1 new
Robolectric case pinning the x-scoped icon arithmetic); `:desktop` compiles and tests green.

Two existing tests were updated rather than deleted, both asserting the old top-hugging:
`trimmed leading hangs outside the run…` now asserts the blank DESCENT hangs out at the bottom, and
`an outer zone hugs the edge away from the bars` now asserts ABOVE bottom-hugs with padding while
BELOW is unchanged.

Follow-ups, in the order they would pay: split `dominant_temp_age` into two placeable lines (would
put `66.2°` above on the emulator, which now has 42.8 px of run against 43.5 px of two-row ink —
0.7 px short); trim the high-label and rain-chip obstacles to ink (the `1%` chip caps the fold's run
at 57.89 with its box while its glyphs start at ≈59.4).
