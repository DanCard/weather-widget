# Today-column overlay: joint placement search (maximal fix)

Date: 2026-08-06
Status: DRAFT — awaiting approval
Supersedes/relates-to: `plans/260806-today-overlay-stack-above-bars.md` (minimal-fix variant;
implement ONE of the two, not both)

## Problem (same as minimal plan)

In Daily view with the large-Today overlay enabled, the Today column's optional text rows
(`+1.8 fcst` delta, `65.4°` dominant-station temp, `0m` reading age) should all stack in the
empty space above the forecast bars. Instead, the temp/age block is drawn ON the bars.

Evidence (2026-08-06 ~20:33, Meteo source on both):

1. Emulator (`emulator-5554`, widget 59) logcat, tag `TodayColumnOverlay`:

   ```
   attempt blocks=[delta, dominant_temp_age]
     lines=[delta:68.12x26.15, dominant_temp_age:50.0x53.61]
     column=126.27..205.19 graph=51.20..359.60 bars=173.15..290.52 obstacles=4
   placement key=delta             zone=ABOVE     score=26.25   (bounds 85.32..111.47)
   placement key=dominant_temp_age zone=ON_COLUMN score=-976.375
   ```

2. Emulator + desktop screenshots: temp/age overlap the bars despite a visibly free band above.

## Root cause (deeper than the minimal plan's framing)

`TodayColumnOverlayPlanner.place()` is a **greedy sequential** search: each block picks its
own score-maximizing spot, then becomes an obstacle for the next block. Scoring rewards
*clearance* (distance to band edges/obstacles), so the first block (`delta`) settles into the
**center** of the ABOVE band — fragmenting it into two leftovers (~26px above, ~54px below,
with the Today high-temp label obstacle at the band bottom) that the 53.6px temp/age block
can no longer use. It falls back to ON_COLUMN (score −976 after the 1000 bar penalty).

The space above the bars fits **both** blocks (26.15 + 53.61 ≈ 80px in a ~88px usable band) —
but only if `delta` slides to the top of the band, sacrificing some of its own clearance. No
amount of per-block tuning fixes this; the algorithm never considers assignments jointly.

## Maximal fix: joint placement search

Replace the greedy core of `TodayColumnOverlayPlanner` with a **joint assignment search with
backtracking**, keeping the existing bands, obstacles, clearance scoring, and bar penalty.

### Design

1. **Candidate generation (per block, unchanged rules):** for each of the 3 bands
   (ABOVE/BELOW/ON_COLUMN), enumerate `candidateTops`, reject obstacle intersections, score
   `clearance − barPenalty`. Cap each block's candidate list per zone (keep best ~8 by score)
   to bound the search.
2. **Joint search:** recursive assignment over blocks; a candidate is admissible if it does
   not intersect any already-assigned block's bounds. Objective, lexicographic:
   1. maximize number of blocks placed (never drop a block that could fit somewhere);
   2. minimize count of ON_COLUMN placements (bar overlap is the worst outcome);
   3. maximize the **minimum** score across placed blocks (maximin — forces delta to yield
      the band center when the space is needed);
   4. tie-break: maximize total score, then prefer the assignment closest to today's greedy
      output (stability).
   With ≤3 blocks and capped candidate lists this is a few hundred comparisons — negligible
   versus the bitmap render it feeds.
3. **Graceful degradation:** if no complete assignment exists, return the best partial one
   (same "place what fits" behavior as today); ON_COLUMN remains a legal last resort.
4. **API:** replace the internals of `TodayColumnOverlayPlanner.place(lines, input)` —
   signature unchanged, both renderers (`TodayColumnOverlayRenderer`,
   `DailyForecastGraph.drawDesktopTodayOverlay`) keep calling it untouched. For a single line
   or non-conflicting lines, joint search returns exactly today's results.
5. **Delete the combined-stack special case:** the per-renderer "merge all rows into one
   `combined` block" fallback (Android `TodayColumnOverlayRenderer` ~lines 139-153; desktop
   `drawDesktopTodayOverlay` `placements.size < measured.size` branch) becomes dead logic —
   joint search finds the stacked-ABOVE layout on its own, as *separate* blocks, which also
   renders identically. Remove it from both platforms (parity per AGENTS.md).

### Why this is safe for existing layouts

- One block, or blocks whose greedy placements never interact → joint optimum = greedy
  optimum (nothing changes).
- Only conflicting multi-block cases change, and only toward fewer ON_COLUMN placements /
  higher minimum clearance.
- `TodayColumnOverlayBlocks` (block/row selection, toggles) is untouched.

### Explicitly out of scope (possible future tier)

- Font-size step-down before accepting ON_COLUMN.
- Horizontal placement freedom (side-by-side blocks).

## Tests

1. `shared/.../graph/TodayColumnOverlayPlannerTest.kt`:
   1. Keep all existing cases green (single-line ABOVE/BELOW/ON_COLUMN selection).
   2. New: emulator-regression geometry (delta 68x26, temp_age 50x54, band ~59..165 with a
      high-label obstacle at the band bottom) → **both** blocks ABOVE, no overlap, no
      ON_COLUMN.
   3. New: fragmentation case — first block must slide to band edge so the second fits
      (proves joint, not greedy).
   4. New: genuinely-unfittable second block → still placed ON_COLUMN (last resort), first
      block keeps its greedy optimum.
   5. New: three-block packing sanity (delta + temp_age + synthetic third block).
2. `app/.../widget/TodayColumnOverlayPlannerTest.kt` (Robolectric-adjacent duplicate of the
   shared suite): keep green; add the two-lines-fit-above case if geometry differs.
3. `DailyLargeTodayLayoutRoboTest` and `TodayColumnOverlayBlocksTest`: must stay green
   (blocks/toggles unchanged).
4. All new tests carry the required `@Category(ShortDuration::class)`.
5. Run: `:shared:testShortShared`, `:app:testShortDebugUnitTest`, `:desktop:testShortDesktop`.

## Verification (Evidence-First)

1. `./gradlew :app:installDebug`, refresh widget 59 on `emulator-5554`:
   - logcat `TodayColumnOverlay`: both blocks `zone=ABOVE`, no negative-score placement;
   - screenshot: `+1.8 fcst`, temp, age all stacked above the Today bars, none over the bars.
2. `./gradlew :desktop:createDistributable`, relaunch, screenshot: same expectation (Meteo).
3. Spot-check a 1-row text-mode widget and a narrow (2-3 col) graph widget for regressions.

## Risks

1. Scoring/tie-break choices could subtly move overlays in currently-good layouts → mitigated
   by tie-break rule 4 (stay close to greedy) and by keeping existing tests as pins.
2. Slightly more planner complexity → contained in one shared object, fully unit-tested.
