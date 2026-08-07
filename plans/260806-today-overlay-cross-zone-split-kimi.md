# Today-column overlay: split rows across zones instead of dropping them

Date: 2026-08-06
Status: DRAFT — awaiting approval

## Problem

In Daily view with the large-Today overlay enabled (all 3 optional rows on: forecast delta,
dominant-station temp, reading age), the desktop graph shows **only 1 row** (`-1.2 fcst`) in a
very common geometry. The user expects all 3 rows: rows that cannot fit above the bars should be
**split across the available zones** (e.g. delta above the bars, temp/age on the column) — the
way the Samsung widget renders it ("all the text is overlaying the column"), not silently dropped.

The planner's current answer to "the stack doesn't fit in any single zone" is the degradation
ladder (`TodayColumnOverlayBlocks.variants`): drop the age row, then the temp row, and show what
fits cleanly. Dropping content is the wrong trade-off here — the user would rather see every row,
split if needed.

## Evidence (desktop runtime logs, 2026-08-06 21:55, `~/.local/state/weather-widget/autostart-20260806-215501.log`)

1. Content is fully present — the resolver returns all three fields:

   ```
   D/DesktopDailyModel: todayOverlay resolve obsCount=21177 enabled=true
     flags=delta:true,temp:true,age:true deltaText=-1.2 dominantTemp=64.4° dominantAge=5m
   ```

2. The planner picks the poorest variant and places only the delta row:

   ```
   V/DailyForecastGraph: todayOverlay layout variant=2/3
     content=delta:-1.2,temp:64.4°,age:5m
     variants=[[delta, dominant_temp_age], [delta, dominant_temp_age], [delta]]
     lines=[delta:108x49]
     column=255.47..415.13 graph=6.0..813.5 bars=171.70..648.06
     obstacles=[287.8,225.7,382.8,278.7,  302.8,100.7,367.8,156.7,  297.0,629.7,373.6,706.4,
               286.8,712.4,383.8,766.4,  289.3,818.0,381.3,863.0,  408.8,762.1,420.8,773.1]
     prevZones={} placements=[delta:ABOVE]
   ```

3. Geometry breakdown (stack heights: delta 49px, temp/age block ~100px, full stack ~152px):
   1. **ABOVE band** (~9..168.7, ~160px) is split by today's **rain label** (y 100.7..156.7) into
      free runs of **91.7px** and 12px. The full stack and the temp/age block fit neither run;
      only delta (49px) fits.
   2. **BELOW band** (~651..810.5) is shredded by today's icon (629.7..706.4), low label
      (712.4..766.4), and a neighbour's night-rain label (762.1..773.1, which overlaps the widened
      Today column's right edge) into free runs of **6px and 37px**. Nothing fits.
   3. **ON_COLUMN band** (174.7..645.1, ~470px) minus today's high label (225.7..278.7) leaves runs
      of 51px and **366px** — the full stack fits here easily.
4. Cost order makes it worse: rank 1 "does not draw over the bars" outranks rank 2 "fewest content
   rows dropped", so the clean 1-row `[delta] ABOVE` placement beats the full 3-row stack
   ON_COLUMN. (This ranking is currently pinned by the test `dropping a row is preferred over
   drawing across the bars`.)
5. Samsung (user observation): shows all 3 rows overlaying the column. Same shared code — the
   smaller phone graph leaves no clean pocket at all, so the planner falls through to the
   whole-stack ON_COLUMN candidate. Desktop's tall graph leaves a pocket that fits *exactly one
   row*, so the cost order prefers that one clean row over full content on the bars.

## Root cause

1. **No cross-zone split candidates.** `fitStack(lines, zone, groups, input)` splits the stack
   into at most 2 contiguous groups, but both groups must live in free runs of the **same** zone's
   band. There is no candidate that places the head group ABOVE and the tail group ON_COLUMN (or
   BELOW). When no single zone fits the whole stack, the only full-content option is whole-stack
   ON_COLUMN — which loses to clean-but-degraded variants under the current cost order.
2. **(Secondary) Last-resort zones become sticky.** When even ON_COLUMN fails, `lastResort()`
   places individual blocks anywhere they fit, and the renderers feed those zones back into
   `previousZones` (desktop `overlayZoneMemo` via `onZonesResolved`; Android `overlayZones` map in
   `DailyGraphRenderer`). Hysteresis then pins those emergency zones while they remain available at
   the same strength, so a block that once landed somewhere via the emergency path can stay stuck
   there after clean room returns.

## Proposed fix (shared planner, minimal renderers touch)

### 1. Cross-zone 2-group split candidates (`shared/.../graph/TodayColumnOverlayPlanner.kt`)

New `splitAcrossZones(lines, input): List<Placement>?`:

1. For each seam (`1 until lines.size`): head = `lines[0..seam)`, tail = `lines[seam..]`.
2. For each zone pair in preference order — restricted to pairs where the head zone is vertically
   above the tail zone so the stack still reads top-to-bottom:
   1. `(ABOVE, BELOW)` — fully clean split
   2. `(ABOVE, ON_COLUMN)` — head clean above the bars, tail on the column
   3. `(ON_COLUMN, BELOW)` — tail clean below the bars
3. Lay each group out with the existing single-group fitting (largest fitting free run in that
   zone's band, centred — reuse the `groups == 1` path of `fitStack` extracted as
   `fitGroupInZone(lines, zone, input)`).
4. Seam preference when several split: the split whose ON_COLUMN group is smallest (fewer rows on
   the bars); ties break toward the natural seam order.

### 2. Candidate order — content completeness first

Per variant (richest first — unchanged), emit in this order:

1. Same-zone clean: `ABOVE` 1-group, `BELOW` 1-group, `ABOVE` 2-group, `BELOW` 2-group (current
   iteration, unchanged).
2. Cross-zone clean split `(ABOVE, BELOW)`.
3. Cross-zone split `(ABOVE, ON_COLUMN)` — head stays off the bars.
4. Cross-zone split `(ON_COLUMN, BELOW)` — tail stays off the bars.
5. Same-zone ON_COLUMN, 1-group then 2-group (whole stack on the bars — the Samsung look — now the
   last resort before degrading content).
6. Next variant (drop a row and repeat). `lastResort` stays the final floor.

`Candidate` changes: replace the single `zone` field with `onBars: Boolean` (any placement in
ON_COLUMN) so strength comparison keeps working for mixed-zone candidates; `sameStrengthAs` keeps
comparing variant index, group count, and the bars flag. Hysteresis (`reproduces()`) is unchanged —
placements carry their own per-block zones.

Result for the logged desktop geometry: variant 0, candidate (3) — `delta` ABOVE in the 91.7px run,
`dominant_temp_age` ON_COLUMN in the 366px run → **all 3 rows shown**, matching the user's
expectation. Roomy geometries are unaffected (candidate 1 still wins).

### 3. Don't memorise last-resort zones

Add `fromLastResort: Boolean` to `Layout` (false for search-produced layouts, true from
`lastResort`). Renderers skip feeding zones back when set:

1. Desktop `DailyForecastGraph.drawDesktopTodayOverlay`: guard `onZonesResolved(...)` with
   `!result.fromLastResort`.
2. Android: `TodayColumnOverlayRenderer.draw` returns placements whose zones are written into
   `DailyGraphRenderer.overlayZones` — thread the flag through (e.g. on
   `TodayOverlayPlacementDebug` or the return value) and skip the map write when set.

### 4. KDoc updates

Rewrite the planner's cost-order list: 1. fewest content rows dropped (per-variant ladder),
2. clean single-zone, 3. clean split, 4. split with bars, 5. whole stack on bars; hysteresis still
only overrides the weak terms.

## Files to change

1. `shared/src/main/kotlin/com/weatherwidget/shared/graph/TodayColumnOverlayPlanner.kt` — split
   candidates, candidate order, `Candidate.onBars`, `Layout.fromLastResort`, KDoc.
2. `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt` — guard
   `onZonesResolved` on `fromLastResort`.
3. `app/src/main/java/com/weatherwidget/widget/TodayColumnOverlayRenderer.kt` +
   `app/src/main/java/com/weatherwidget/widget/handlers/DailyGraphRenderer.kt` — thread and guard
   the last-resort flag on the zone-memo write.
4. `shared/src/test/kotlin/com/weatherwidget/shared/graph/TodayColumnOverlayPlannerLayoutTest.kt` —
   new/updated cases below.

## Tests (shared planner)

1. **Desktop-geometry regression**: ABOVE runs 91.7/12px, BELOW runs 6/37px, stack 49+100 →
   placements `[delta:ABOVE, dominant_temp_age:ON_COLUMN]`, all rows present.
2. `(ABOVE, BELOW)` fully-clean split is preferred over `(ABOVE, ON_COLUMN)` when both fit.
3. Whole-stack clean single-zone still beats any split (no splitting when unnecessary).
4. When no seam/zone-pair fits, the search falls through to the next variant, then ON_COLUMN,
   matching the existing ladder semantics.
5. Inverted pairs are never emitted (head zone is always vertically above tail zone).
6. Hysteresis retains split zones across sub-pixel obstacle jitter at the same strength.
7. `fromLastResort` is true only for last-resort layouts.
8. Existing tests keep passing; adjust `dropping a row is preferred over drawing across the bars`
   only if the new order changes its expectation (its geometry fits the full stack nowhere, so it
   should stand).

## Verification

1. `./gradlew :shared:test :desktop:test` and `./gradlew :app:testDebugUnitTest` — green.
2. Run the desktop app (`./gradlew :desktop:run`), Daily view, large-Today overlay on: confirm 3
   rows (delta above the bars, temp/age on the column) in the geometry that previously showed 1.
3. Pan left/right and zoom: rows must stay visible and stable (hysteresis), no per-scroll
   appear/disappear; check the `todayOverlay layout` VERBOSE log lines for stable
   variant/zones across pans.
4. Android: install debug on emulator, confirm the Today overlay still renders and splits the same
   way (shared planner) — screenshot before/after on the same forecast day.
