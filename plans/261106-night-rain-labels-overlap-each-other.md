# Night rain labels: keep neighbouring night labels apart

## Evidence (emulator, Silurian, 2026-11-06 19:27, canvas 559 px wide)

Fri→Sat night `.24in` and Sat's night `100%` overlap (screenshot: "55°.24in100%").
`DailyRainLabelLayout` trace (x = centre):

| Label | fit | centre, top | size | box |
|---|---|---|---|---|
| Fri `.24in` | NIGHT_SHIFTED_LEFT | 513.4, 299.7 | 27.0×15.2 | 499.9–526.9 × 299.7–314.9 |
| Sat `100%` | NIGHT_CENTERED | 536.0, 297.5 | 30.0×15.2 | 521.0–551.0 × 297.5–312.7 |

The overlap is about 5.9 px across, at almost the same height. Sat is the last column, so its night
label has no gap to its right and is centred on Sat's own column, right under Fri's label.

## Root cause

`layoutNight` places each night label independently. Its obstacles are the two neighbouring
columns' icons and low labels, never another night label. Both platforms draw each label as soon as
it is placed (Android `DailyForecastRainLabelRenderer.drawNightRainLabels`, desktop
`DailyForecastGraph` `deferredNightLabels`), so an earlier label can't be moved afterwards.

## Fix (user: nudge 100% right, raise it, and if that isn't enough lower .24in)

1. `:shared` `DailyRainLabelLayout.separateNightLabels(placed: List<Pair<NightInput, Placed>>)`:
   for each adjacent pair (left L, right R) whose boxes come within 1 dp of each other:
   1. Slide R right, up to the canvas edge margin and not into R's obstacles, until it is 1 dp clear
      of L horizontally.
   2. Still overlapping: raise R, up to 1 dp below the ink of its obstacles (digit line box minus
      `DIGIT_BLANK_DESCENT_FRACTION`; the ° box covers its top half only), until R's bottom is 1 dp
      above L's top.
   3. Still overlapping: lower L to 1 dp below R's bottom, capped at `dayLabelTop` and not into L's
      obstacles.
   Nothing moves for a pair that doesn't overlap, so every other placement stays as it is.
   Trace: `nightLabelSeparation date=… rightDp=… raiseDp=… lowerLeftDp=… clear=…`
   (VERBOSE, like the existing night trace).
2. Hosts lay out every night label first, call `separateNightLabels`, then draw: Android
   `drawNightRainLabels`, desktop `deferredNightLabels`.
3. Tests:
   - `:shared` unit test using the emulator geometry above: the two labels come out clear; R moved
     right, then up, and L moved down by only what's left. A pair that doesn't overlap is left alone.
     It must fail on the current code.
   - Integration test: the Android renderer (`DailyForecastGraphRenderer.renderGraph`, Robolectric)
     with two neighbouring night labels like these, asserting from `onRainLabelDrawn` that the drawn
     night boxes don't overlap. Desktop: the same check through `DailyForecastGraph`'s text-measurer
     host, if its placements can be captured; otherwise the shared test covers the rule.
4. Verify: installDebug on the emulator and take a screenshot of Fri/Sat; check desktop (rebuild and
   restart); check the Fold. Per the night-label memory, judge the result from the pixels.

## Addendum (user, Pixel 7 Pro): raise the 100% closer to the 57°

Pixel 7 Pro 19:36 (canvas 517 px, 2.63 px/dp): separation slid Sat "100%" right 4.1 dp and that
was enough, but the label sat 6 dp under the ink of "57°". The tuck measures from the low's line
box, whose bottom 22 % is blank, plus the roomy drop. A label centred under its own low
(`NIGHT_CENTERED` only: the last column, or a label too wide for its gap) now rises to 1 dp under
that low's digit ink, stopping short of other ink (`ownLowTuckDp=` in the trace). Labels in the
gaps are unchanged. Unit tests use the Pixel geometry; the emulator instrumented test still
checks the pair stays apart.
