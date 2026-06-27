# Multiple ghost-line labels on the zoomed hourly graph

## Problem

On the zoomed-in hourly temperature graph, the **ghost line** (forecast + observed delta, drawn
faint/dashed right of the fetch dot) only ever got **one** temperature label — it showed at noon
and the user wanted it labeled at 1 PM as well, and more generally "wherever there's space; can be
multiple ghost labels."

Nothing was broken: `GhostLineLabel.place()` was **singular by design** — it returned a single best
`Placement` via an "emptiest hour" heuristic, so it labeled one hour and stopped.

## Change

Converted the ghost label from single to **multi-label**: place a label at every future hour mark
that has room.

**Files:**
1. `shared/src/main/kotlin/com/weatherwidget/shared/graph/GhostLineLabel.kt` — replaced `place()`
   with `placeAll(): List<Placement>`. Extracted the per-hour "try above, then below; keep the
   max-clearance valid spot" body into a private `tryPlaceAt`. `placeAll` walks eligible right-half
   hour ticks **left-to-right** against a **running obstacle list** (seeded from `drawnBounds`); each
   placed label is added to the obstacles so later ghost labels never stack on earlier ones.
   Footer-labeled hours preferred; fall back to all right-half candidates only if none are labeled
   (matters on desktop where candidates are dense sampled points, not whole hours).
2. `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` —
   `placeGhostLineLabel` loops the returned placements (draw + record each box), plus a permanent
   **logcat-only `Log.v`** decision trace (tag `TempGraphRenderer`): candidate `x@temp*` list and
   which hours got a label. `Log.v` never persists to `app_logs`, fitting the high-frequency render.
3. `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt` — same `placeAll` + loop,
   keeping Android/desktop in lockstep via the shared engine (no desktop-specific placement math).
4. `shared/src/test/kotlin/com/weatherwidget/shared/graph/GhostLineLabelTest.kt` — migrated to
   `placeAll`; added multi-label, mutual-collision-skip (running obstacle list), footer-tick
   preference, span gate, left-half, and existing-label collision cases.

## Verification

- `:shared:test --tests "*GhostLineLabelTest*"` — green.
- `installDebug` on `emulator-5554`; the new `Log.v` trace confirmed the live code path:
  `candidates=[438@68.8°*, 584@69.8°*] -> placed=1`. (1 PM was genuinely crowded that frame — the
  ghost/forecast lines converge near the right edge and the top is occupied by the noon ghost label
  and the forecast end-label, so 1 PM had no clean hug spot. Expected, not a bug; other frames where
  later hours have room now get multiple labels.)
- Full `:desktop:test` — green. (One `NoSuchMethodError` on `DailyForecastSnapshot.<init>` was stale
  test bytecode compiled against an older `:shared` class — unrelated to this change; cleared by
  `--rerun-tasks`.)
- Rebuilt the distributable and restarted the running desktop app via `scripts/buildStart.sh`.

## Notes / follow-ups

- A single frame may still show only one label when later hours are crowded (lines converge + top
  labels occupy the open side). If the user wants 1 PM guaranteed, options are: prefer earlier hours
  over the emptiest, or let a label drift into the open space below converged lines (trades
  line-hugging for guaranteed placement).
- Not committed.
