# Night rain labels: redesign placement as one scored search over measured ink

Supersedes `plans/261116-night-rain-labels-centre-by-dropping.md`, which is not implemented.
The fixes in `plans/261116-night-rain-labels-ink-slide-and-floor-drop.md` stay in place until the
switch-over (phase 4).

User, 2026-11-16: "Should we delete the way to low rule? Or should we completely redesign the label
code? Start over?" → agreed to redesign the night-label resolver only ("yes").

## Why

1. **Patch chain.** `DailyRainLabelPlanner` (848 lines, 20 functions) and `DailyRainLabelLayout`
   (616 lines) place a night label with a least-move search followed by about 12 corrective steps:
   recentre, ink drop/slide, ° drop/slide, floor ease, floor ° drop, floor right drop, own-low tuck,
   side slide, pocket, shrink retry. There are 40 `NIGHT_*` constants. The planner changed in 17
   commits since 2026-10-15 across 7 night-label plans.
2. **Guessed ink.** Obstacles are text line boxes trimmed by fixed fractions
   (`LOW_BLANK_ASCENT_FRACTION` 20 %, `DIGIT_BLANK_DESCENT_FRACTION` 22 %, ° = top 50 %), measured on one
   Fold screenshot. The label's own blank space was not trimmed (today's "94%" 4 dp slide). Icons are
   full bitmap boxes, transparent padding included.
3. **Wrong goal.** The search minimises the move from home, not how the result looks. Tue→Wed
   ".51in" (19 screen px right of centre) hops right of Tue's sun icon. A clear centred spot under
   Tue's ° is never considered.
4. **Order dependence.** Each greedy step only moves forward, so a fix in one step shifts labels that
   were already approved. Many past plans needed before/after diffs to catch this.

## Design

### 1. Measured ink (hosts)

`Host` gains `inkBounds(text, kind, fontScale): Rect`: the drawn glyph bounds relative to the
baseline origin.

- Android: `Paint.getTextBounds`.
- Desktop: the existing off-screen raster ink scan (`DailyForecastGraph.kt` ~line 1091), extended to
  columns as well as rows, and cached per text/size.

Obstacles become `InkObstacle(rect, kind = ICON | DIGITS | DEGREE | DAY_NAME | NIGHT_LABEL)`:

- Each low label is measured as two pieces: the digits' ink and the °'s ink.
- Icons use their opaque-pixel bounds, computed once per drawable and cached.
- The day-name row contributes its ink top as the floor.

### 2. Scored search (`:shared` `NightLabelPlacer`)

1. **Candidates:** a grid of label positions, 0.5 dp steps. Horizontally within ±30 % of the column
   width around the gap centre (`NIGHT_NUDGE_MAX_SHIFT_FRACTION`, kept). Vertically from the tuck
   under the higher low down to the day-name ink floor.
2. **Hard constraints** (a candidate failing any one is discarded):
   - label ink ≥ `CLEARANCE` (1 dp) from every obstacle's ink;
   - label ink bottom ≤ day-name ink top − 1 dp;
   - inside the canvas edge margin.
3. **Score** (lower wins; weights in one data class so a tuning change is one line):
   - `wCentre × |inkCentreX − gapCentreX|` — centred between columns. This is the main term.
   - `wHeight × max(0, inkTop − preferredTop)` — the "not way too low" preference (2026-06,
     2026-10-31): `preferredTop` is the tuck under the higher low. Below it costs per dp; never a
     hard rule.
   - `wCrowd × Σ max(0, comfort − gap)` — mild preference for comfortable air (comfort ≈ 2.5 dp) over
     the bare 1 dp.
   - Equal scores are broken by the smaller distance from home.
4. **Fallbacks, in order:** retry at `NIGHT_REDUCED_SCALE`; then allow a slight overlap (≤ 1.5 dp
   deep, never into a ° or digit ink); then centre under its own low (`NIGHT_CENTERED`, as today).
5. **Neighbours:** keep the two-pass layout. Lay out each night label, then
   `separateNightLabels`, rewritten as a joint re-score of the overlapping pair. It replaces the
   fixed right/rise/drop order with the cheapest clear pair.
6. **Trace:** one line per label, `nightRainLabelScored`, giving the chosen centre/top, each score
   term, the runner-up, and which constraint rejected the home spot.

### 3. What stays and what goes

- **Stays:**
  - day-label layout (`layoutDay`);
  - measuring and drawing on both platforms;
  - home/gap-centre computation;
  - the font scales;
  - `DailyBottomStack`;
  - log tag `DailyRainLabelLayout`.
- **Goes after the switch-over:**
  - `placeNightLabel`'s least-move search and every post-step listed under Why (1);
  - the blank-fraction constants;
  - `Box.isLowDigits` / `isDegreeSign` as layout hints.

## Delivery (one change; no shadow mode)

User, 2026-11-16: "Is the redesign much simpler? Why not just go with it?" → shadow mode dropped. The
before/after comparison comes from `resolved=` logs of the old build vs the new build per device, as
in `plans/261116-night-rain-labels-ink-slide-and-floor-drop.md`, not from running both code paths.

1. Hosts: `inkBounds` (Android `getTextBounds`, desktop raster scan) and icon opaque bounds.
2. `:shared` `NightLabelPlacer`; `layoutNight` and `separateNightLabels` use it. Delete the old steps,
   blank fractions and night constants.
3. Outcome tests from logged fixtures.
4. Install per serial and restart desktop. Report every label that moved by more than 1 dp, with a
   before/after screenshot crop. Tune the weights with the user.

## Tests

- Pure JVM `:shared` tests for `NightLabelPlacer`, one per historical case. The fixtures come from
  the traces cited in the night-label plans and memory (Fold "48%", "40%", "15%", ".45in", "97%",
  "88%"; emulator "4%", "22%", "87%"; desktop "30%", "37%", "38%"), plus a property test: for random
  obstacle layouts the result never overlaps ink when a clear spot exists.
- Instrumented `NightRainLabelSeparationInstrumentedTest` keeps the real-font check. It adds a
  real-font `getTextBounds` vs raster ink check for the host.
- Desktop: ink-scan columns test.

## Verification

Each phase: install per serial (Fold, Pixel, emulator) and restart the desktop app. Phase 2–4:
shadow-diff review per device. Phase 4: screenshots of every source on the Fold and desktop.

## Docs

- Update the night-label memory and `arch/` (add a section on daily rain-label placement to
  `ARCHITECTURE.md` or a new `arch/daily-rain-labels.md`).
- Mark the dated night-label plans as historical in the new doc.

## Follow-up: ink as row bands (approved 2026-11-16)

User: "What do you think about Fold-4 33% label more centered?" → "yes".

**Evidence.** Fold 11:12. "33%" (Thu→Fri) is 3.3 dp left of centre and shrunk. Its log
`obstacles` show the blocker is Fri's "42" digit ink box (x 404.2–438.2), not "94%" (ink ends at
x 360). Zoomed screenshot: the "4" is open below its crossbar, with only the stem on the right. That
leaves 4–5 dp of free space where the bounding box says there is ink.

**Change.**
1. `:shared` `InkScan.rowBands(height, fromX, toX, bandPx, isInk)`: for each band of rows about
   1 dp tall, the x-extent of the ink in that band.
2. Low labels and icons become band boxes on both platforms. Android rasterizes the low label
   (cached, outline included) the way it already does icons; desktop extends its raster scan.
3. The separate digits/° boxes go away: below the °'s bands there is no ink.
4. Placer unchanged. `leftLowInk` / `rightLowInk` stay as the union (they set the preferred row).

**Check.** Unit tests: `rowBands`, and a label centring into a "4"-shaped notch. Device: Fold +
emulator screenshots of every label that moves by more than 1 dp. Desktop restart.
