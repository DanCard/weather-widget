# Shared daily rain labels — summary (2026-10-29)

Plan: [plans/261029-shared-daily-rain-labels.md](../plans/261029-shared-daily-rain-labels.md)

## User prompts

1. "What do you think of moving night time or all rain labels on daily forecast view to shared code?
   Take screenshots of desktop and fold-4. I prefer how the night time rain label is done on desktop.
   Fold-4 for history tue - wed draws it underneath, while desktop draws it in between. I prefer in
   between."
2. "slight overlap ok, include day labels too, go ahead"
3. (side note) List every rule that was unified, not only the ones that moved. Write the final report
   to `summaries/`.
4. "Fold-4 rain labels look good now"

## Root cause

The collision search (`DailyRainLabelPlanner.placeNightLabel`) was already shared, but each platform
built its inputs separately. On Android, the night label's bottom limit was `heightPx − 6 dp`, which is
**inside the day-name row**. The tuck therefore read 24.6 dp of room below Tue's `48°`, went "roomy"
(2.5 dp down, no tuck-up), and started below the top of Wed's `45.7°`. The search then dropped the
label 10 dp, into "Tue Wed".

Fold logcat, before: `date=2026-10-27 text="10%" homeTop=386.97 placement=down offsetDp=(0.0,10.0)`

Fold logcat, after: `date=2026-10-27 text="10%" roomBelowDp=12.9 home=(49.9,371.3) placement=home overlapDp=1.0`

With the real limit (the top of the day-name row), the room below is 12.9 dp. The tuck is tight, so
the label starts in the pocket between `48°` (bottom 379.6) and `45.7°` (top 383.7) and needs no
nudge.

## What changed

- **New `:shared` runner `DailyRainLabelLayout`**: `layoutDay` places the day % and peak hour;
  `layoutNight` places the night %. It owns the font scale, anchors, gaps, ceiling, edge fit,
  collision search, bottom limit and trace. Each platform's `Host` only measures text; the platform
  draws the returned boxes.
- **Android** `DailyForecastRainLabelRenderer` is now a thin Paint host (≈450 → ≈190 lines). The
  **desktop** `DailyForecastGraph` rain code is now two runner calls plus a TextMeasurer host.
- **Trace** moves to the `DailyRainLabelLayout` tag with `platform=`. The night line now lists
  `tuckTop`, `maxBottom`, `dayLabelTop` and every obstacle box.

## Every rule unified

Rare cases are marked *(rare)*. Day-label placement in normal layouts did not change on either
platform.

| # | Rule | Was: Android | Was: desktop | Now (both) |
|---|---|---|---|---|
| 1 | Night label bottom limit | `heightPx − 6 dp` (inside the day-name row) | top of the day-name row | top of the day-name row (`DailyBottomStack.dayLabelTop`) |
| 2 | Night "room below" for the tuck | computed baseline → `heightPx − 6 dp` | label bottom → day-name row | higher low label's bottom → day-name row |
| 3 | Night anchor | higher low's **computed** baseline + paint descent | higher low's top + the **left** label's height | bottom of the higher **drawn** low label |
| 4 | **New:** "in between" cap | — | — | the night label's bottom never goes below the lower low label's bottom |
| 5 | **New:** lows on the day-name row (coldest days) *(rare)* | drawn over the day names | skipped | rises until it clears the row, level with the lows in the gap between them |
| 6 | Night edge margin | 4 dp | 2 px × scale | 4 dp (`RAIN_LABEL_EDGE_MARGIN_DP`) |
| 7 | Night label centred on its column (no room in the gap) | not kept inside the canvas | kept inside the canvas | kept inside the canvas |
| 8 | Day % above the label ceiling *(rare)* | drawn above it ("forced" log) | clamped to the ceiling | clamped to the ceiling (Android ceiling is never above the bitmap top); top-stack headroom uses the unclamped top |
| 9 | Peak hour vs the ceiling | dropped above the bitmap top (0) | dropped above the label ceiling | dropped above the label ceiling (Android: never above the bitmap top) |
| 10 | Peak hour in top-stack headroom (Android) *(rare)* | missing when the peak was dropped | included | included, through the day %'s unclamped stack top |
| 11 | No high label drawn *(rare)* | day % skipped | day % drawn at `graphTop + 10` | day % skipped |

These were already identical and are now enforced in one place: the font scale (probability and
distance, `NIGHT_SCALE`, peak scale, the 0.85 shrink to fit), the gaps (`RAIN_HIGH_TEMP_GAP_DP`,
`RAIN_PEAK_GAP_DP`), the snapshot-bar clearance, the tuck curve, and the collision search.

## Tests

- `:shared` `DailyRainLabelLayoutTest` (9 tests). It includes the Fold's real Tue→Wed boxes from
  logcat. `night label never sinks below the lower low` was checked against the code without the
  cap: it **fails** there (bottom 121.6 > 110) and passes with it.
- New two-platform contract `DailyRainLabelHostContract` (required by `HostContractCoverageTest`):
  the baseline sits inside the box, longer text measures wider, the font scale scales size, and the
  peak shares the day base. It is run by `AndroidDailyRainLabelHostContractTest` (Robolectric with
  native graphics, so real font metrics) and `DesktopDailyRainLabelHostContractTest`.
- Removed 3 app tests of a deleted wrapper; the same function is covered by `DailyRainLabelPlannerTest`.
  The peak-paint colour test now uses `createRainPaint`.
- Full JVM gate (`scripts/unit-tests.sh`): **6,327 passed**, 2 skipped (both pre-existing).
  The first run caught two Robolectric night-label tests (equal lows at the floor were skipped),
  which led to rule 5, plus the missing host contract.
- Instrumented/emulator tests were **not** run.

## Device verification

- Fold 4: reinstalled twice. Tue→Wed "10%" is drawn at `home` between `48°` and `45.7°`. The user
  confirmed it looks good. The second build (rule 5) logs the identical placement.
- Desktop: rebuilt and restarted twice. Tue→Wed "10%" is still between `48.0°` and `45.8°`. Some night
  labels shifted by a few px from the unified anchor and edge rules.
