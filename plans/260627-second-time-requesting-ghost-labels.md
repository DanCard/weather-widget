# Plan: Multiple ghost-line labels on the zoomed hourly graph

## Context

On the zoomed-in hourly temperature graph, the **ghost line** (forecast + observed delta,
drawn faint/dashed right of the fetch dot) currently gets **one** temperature label. The user
sees a single label at **noon** and wants the ghost line labeled at **1 PM as well** — and more
generally: *"Where there is space, put it. Don't think singular. Can be multiple ghost labels."*

Nothing is broken. `GhostLineLabel.place()` is **designed** to return a single best `Placement`
(the "picks the emptiest hour" heuristic). The change is to label **every future hour mark that
has room** instead of just the emptiest one — placing labels left-to-right and avoiding overlap
with the curve, existing labels, and each other.

The user also asked to "add logging if not easy to figure out." The cause is understood (singular
by design), so logging isn't needed to diagnose — but per the standing preference
([[feedback_permanent_debug_logging]]) I'll add a permanent logcat-only `Log.v` decision trace in
the Android caller so future ghost-label placement questions are answerable from logs.

## Current behavior (traced)

- Shared `GhostLineLabel.place(...)` → `Placement?`: filters candidates to the right half, prefers
  hours with a footer label, tries each candidate **above then below** the ghost line, and returns
  the single placement with the **max curve clearance**.
  `shared/src/main/kotlin/com/weatherwidget/shared/graph/GhostLineLabel.kt`
- Android caller `placeGhostLineLabel(ctx, hours)` builds `Candidate`s for hours **right of the
  fetch dot** (`x > fetchDotX + tol`), calls `place(...)`, draws the one label, records its box.
  `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt:553`
  (called unconditionally at `:931`, after all other labels populate `ctx.drawnLabelBounds`).
- Desktop mirror builds the same `Candidate`s, calls `place(...)`, draws the one label.
  `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt:679-724`
- The ghost line itself is drawn clipped to `clipRect(fetchDotX, …)` — future region only
  (`TemperatureGraphRenderer.kt:330-333`). The label shares the ghost line's exact visibility gate.

## Approach

### 1. Shared `GhostLineLabel.kt` — add `placeAll(...) : List<Placement>`
- Extract the existing per-candidate "try above then below, keep max-clearance" body into a private
  `tryPlaceAt(candidate, plot, obstacles, curveYAt, metrics, padPx, gapPx): Placement?`.
- `placeAll`: same up-front gates as `place` (span ≤ `MAX_HOURS_SPAN`, valid metrics, non-empty,
  fits width). Eligible = candidates with `x >= rightCutoff` **and** `hasHourLabel == true`
  (snap to footer hour ticks — important on desktop where candidates are dense sampled points),
  **sorted by x ascending** (noon → 1 PM → 2 PM). If no labeled hour qualifies, fall back to all
  right-half candidates (mirrors today's labeled→all tier fallback).
  Walk eligible left-to-right with a **running obstacle list** seeded from `drawnBounds`; for each,
  `tryPlaceAt` against the growing obstacles, and on success append the placement and add its box to
  the obstacles so later ghost labels don't overlap earlier ones. Return all placements.
- Replace `place` with `placeAll` (no production caller needs the single-best variant). Keep the
  object pure / free of android+compose types and of `Log`.

### 2. Android caller — `TemperatureGraphRenderer.placeGhostLineLabel`
- Call `placeAll(...)`; `for (p in placements) { canvas.drawText(...); ctx.drawnLabelBounds.add(box) }`.
- Add a permanent `Log.v` trace (logcat-only, no "remove later"): the candidate hours right of the
  fetch dot (x, hour, temp, hasLabel), and which hours got a label vs were skipped. Keep the shared
  function pure — the trace lives here where `android.util.Log` is already available.

### 3. Desktop caller — `TemperatureGraph.kt`
- Call `placeAll(...)`; loop the returned placements: `drawShadowedText(...)` each and
  `drawnLabels.add(Rect(...))` each (parity per [[feedback_share_android_desktop_logic]] /
  [[ghost_line_label]]). Same shared logic, no desktop-specific placement math.

### 4. Tests — shared `GhostLineLabelTest.kt`
- Keep `format` test. Migrate the `place` tests to `placeAll` and add coverage for the new behavior:
  - multiple clear labeled right-half hours → **one placement per hour** (e.g. noon + 1 PM + 2 PM).
  - two adjacent hours whose boxes would overlap → the second is **skipped** (mutual-collision).
  - span past `MAX_HOURS_SPAN` → empty list; left-half-only candidate → empty list.
  - an existing `drawnBounds` blocker over one hour → that hour skipped, the other(s) still placed.

## Critical files
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/GhostLineLabel.kt` — `placeAll` + `tryPlaceAt`.
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — loop + `Log.v` trace.
- `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt` — loop over placements.
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/GhostLineLabelTest.kt` — multi-label tests.

## Verification
- Unit: `./gradlew :shared:testDebugUnitTest --tests "*GhostLineLabelTest*"` (or `:shared:test`).
- Android: `./gradlew installDebug` on `emulator-5554`, then screenshot the zoomed hourly view
  (`adb exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg`) and confirm the ghost
  line carries labels at **noon and 1 PM** (and 2 PM if it clears the forecast end label). Pull
  `adb logcat | grep -i ghost` to see the new placement trace.
- Desktop: `./gradlew :desktop:test` then `scripts/buildStart.sh` to rebuild + restart the running
  app ([[feedback_auto_restart_desktop]]); open the popup, zoom into the hourly graph, confirm the
  same multi-label behavior.
