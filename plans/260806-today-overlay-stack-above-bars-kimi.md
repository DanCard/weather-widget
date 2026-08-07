# Today-column overlay: stack all rows above the forecast bars when space allows

Date: 2026-08-06
Status: DRAFT — awaiting approval

## Problem

In Daily view with the large-Today overlay enabled (all 3 info rows toggled on), the Today
column's optional text is **not** using the free space above the forecast bars:

1. The forecast-delta row (`+1.8 fcst` / `-0.1 fcst`) is placed in the ABOVE zone — good.
2. The dominant-station temp + reading-age rows (`65.4°`, `0m`) are placed **ON_COLUMN**,
   drawn on top of the forecast bars, even though there is visibly plenty of empty space
   above the bars in the Today column.

## Evidence (collected 2026-08-06 ~20:33)

1. Emulator (`emulator-5554`, widget id 59, Meteo display source) screenshot: today column
   shows `+1.8 fcst` up high, then a large empty gap, then `65.4°` / `0m` overlapping the
   yellow forecast bars.
2. Emulator logcat, tag `TodayColumnOverlay` (repeats every render):

   ```
   attempt blocks=[delta, dominant_temp_age]
     lines=[delta:68.12x26.15, dominant_temp_age:50.0x53.61]
     column=126.27..205.19 graph=51.20..359.60 bars=173.15..290.52 obstacles=4
   placement key=delta            zone=ABOVE     bounds=131.67,85.32,199.79,111.47 score=26.25
   placement key=dominant_temp_age zone=ON_COLUMN bounds=140.73,204.65,190.73,258.26 score=-976.375
   ```

   The -976 score = clearance (~23.6) minus the 1000 ON_COLUMN bar-overlap penalty — the
   planner *knows* it is a bad placement but found no ABOVE candidate.
3. Desktop app (running binary, Meteo source) screenshot: identical symptom — `-0.1 fcst`
   above the bars, `62.8°` / `0m` overlapping the Today bars despite a large empty band above.

## Root cause

`TodayColumnOverlayPlanner.place()` (shared) places each block **independently**:

1. `delta` is placed first and lands ABOVE (top of the band), becoming an obstacle.
2. The ABOVE band's bottom is then blocked by Today's high-temp label (`74.6°`), which sits
   just above the bar tops and is a hard obstacle inside the ABOVE band.
3. The 2-row `dominant_temp_age` block (~53.6px tall) fits neither above the delta nor
   between the delta and the high label, so no ABOVE candidate survives; it falls back to
   ON_COLUMN with the 1000-point bar penalty.

The "combine all rows into one narrow stack" fallback already exists in **both** renderers,
but it only triggers when a block was **dropped entirely**
(`placements.size < specs.size`). Here both blocks got placed (one badly), so the fallback
never runs. A single 3-row stack (~83px) *does* fit the ABOVE band above the high label.

Both platforms duplicate this fallback logic:
- Android: `TodayColumnOverlayRenderer.draw()` lines ~139-153
- Desktop: `DailyForecastGraph.drawDesktopTodayOverlay()` (`placements.size < measured.size && measured.size > 1`)

## Proposed fix (minimal, shared)

1. **`shared/.../graph/TodayColumnOverlayPlanner.kt`** — add a pure selection function, e.g.

   ```kotlin
   fun choosePlacements(
       perBlock: List<Placement>,
       requestedBlockCount: Int,
       combined: List<Placement>,
   ): List<Placement>
   ```

   Rule:
   1. Keep per-block when it placed every requested block **and** none landed in
      `Zone.ON_COLUMN` (current good behavior unchanged).
   2. Otherwise, if the combined stack placed successfully and is **not** ON_COLUMN, use it
      (new: upgrades the "temp/age on the bars" case to a clean stack above the bars).
   3. Otherwise, if per-block dropped a block and combined placed, use combined (existing
      fallback behavior, preserved).
   4. Otherwise keep per-block.

2. **Android `TodayColumnOverlayRenderer`** — when the per-block result is imperfect (any
   ON_COLUMN placement or a dropped block), also compute the combined placement and pass
   both through `choosePlacements`. (Combined placement is only computed lazily in this
   case; rendering path otherwise unchanged.)

3. **Desktop `DailyForecastGraph.drawDesktopTodayOverlay`** — same change, using the same
   shared chooser (dual-platform parity per AGENTS.md).

No changes to `TodayColumnOverlayBlocks`, style constants, scoring, or zone preferences.

## Tests

1. `shared/.../graph/TodayColumnOverlayPlannerTest.kt` — add cases for `choosePlacements`:
   1. per-block complete + no ON_COLUMN → per-block kept.
   2. per-block has ON_COLUMN, combined ABOVE → combined chosen.
   3. per-block incomplete, combined placed (any zone) → combined chosen (legacy fallback).
   4. per-block ON_COLUMN, combined also ON_COLUMN/absent → per-block kept.
   5. Geometry regression mirroring the emulator log numbers (delta at top of ABOVE band +
      high-label obstacle at band bottom): combined stack must land ABOVE.
2. All new tests get the required `@Category` (Short) annotations.
3. Existing suites must stay green: `:shared:testShortShared`,
   `:app:testShortDebugUnitTest` (incl. `TodayColumnOverlayPlannerTest`,
   `DailyLargeTodayLayoutRoboTest`, `TodayColumnOverlayBlocksTest`), `:desktop:testShortDesktop`.

## Verification (Evidence-First)

1. `./gradlew :app:installDebug`, trigger a widget refresh on `emulator-5554`, then:
   - logcat: `TodayColumnOverlay` should show a single `combined` (or all-ABOVE) placement,
     no ON_COLUMN placement with a large negative score.
   - screenshot: all 3 rows (`+1.8 fcst`, temp, age) stacked above the Today bars, nothing
     drawn over the bars.
2. `./gradlew :desktop:createDistributable`, relaunch desktop app, screenshot the window:
   same expectation.
3. Confirm the toggles still behave: with only temp or only age enabled, the single block
   still renders (covered by existing blocks tests).

## Out of scope

- Changing font sizes, paddings, or planner scoring weights.
- Behavior when space genuinely does not fit the stack (ON_COLUMN remains the last resort).
