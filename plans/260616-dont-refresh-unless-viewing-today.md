# Freeze automatic drift of history hourly-graph views (keep arrows/interaction unchanged)

## Context

On the temperature hourly graph, when viewing **history**, the graph drifts forward on its own: after
some time an automatic refresh advances the window a little. This is unwanted. The view should only
advance over time when it includes the live region — the **current day / now point / fetch dot**.

User constraint (explicit): **arrows and all user interaction must work exactly as before.** Only the
*automatic* (non-interactive) refresh of a history view should stop drifting.

### Root cause

Every render computes the center from live `now`:
`WidgetRenderer.kt:108` → `val centerTime = now.plusHours(hourlyOffset.toLong())`. `hourlyOffset` is
relative to `now`, so as periodic UI updates (opportunistic current-temp) and post-fetch redraws fire,
`now` advances and the history window slides forward (by ~1h each time the aligned hour ticks over).
`WidgetRenderer.kt:108` feeds `centerTime` to all view handlers (temp/precip/cloud), so the drift is
shared. The tap/interaction render path computes its own center at `WidgetIntentRouter.kt:807`.

Note: single-day (past-day-click) views are already drift-free — `TemperatureHourDataBuilder`/`build()`
override the window to the clicked day regardless of `centerTime`. This plan covers the remaining case:
scrolled-back (arrow) history and any other non-live window.

## Approach

Anchor history views to a fixed absolute center captured at navigation time; let live views keep
tracking `now`. Arrows are untouched because each arrow press already writes a new offset — at that
instant `anchor == now+offset == live`, so the rendered position is identical to today; only subsequent
*automatic* refreshes read the frozen anchor instead of re-deriving from a newer `now`.

1. **Capture anchor on navigation.** In `WidgetStateManager.setHourlyOffset` (the single chokepoint all
   navigation goes through — `navigateHourlyLeft/Right`, `handleSetView`, resets), also store
   `graphAnchorMs = (LocalDateTime.now() + offset)` as epoch ms under the new
   `KEY_GRAPH_ANCHOR_MS_PREFIX` (constant already added). Add `getGraphAnchorMs(widgetId): Long?` and
   include the key in `clearWidgetState`.

2. **Resolve center with a freeze gate.** Add
   `resolveHourlyCenterTime(widgetId, now, zoom): LocalDateTime` to `WidgetStateManager`:
   - `liveCenter = now.plusHours(offset)`
   - `includesNow = offset in -zoom.forwardHours..zoom.backHours` (the now/fetch-dot point is inside the
     visible window — `ZoomLevel` exposes `backHours`/`forwardHours`, already used in
     `TemperatureHourDataBuilder.kt:164`).
   - return `liveCenter` when `includesNow` (live view advances, as desired); otherwise return the stored
     anchor (`Instant.ofEpochMilli(anchorMs)…`), falling back to `liveCenter` if no anchor.

3. **Use the resolver at the render sites.** Replace `now.plusHours(hourlyOffset)` with
   `stateManager.resolveHourlyCenterTime(appWidgetId, now, zoom)` at:
   - `WidgetRenderer.kt:108` — the automatic/full render (periodic UI + post-fetch); this is the
     essential fix and covers all view modes since it passes `centerTime` down to every handler.
   - `WidgetIntentRouter.kt:807` — the tap render, for consistency (a tap on a history view shouldn't
     jump). Leave the `currentGraphCenterTime` data-window check at `WidgetIntentRouter.kt:344` on live
     `now` (it gates fetching, not display).

### Why arrows are unaffected
Arrow nav calls `setHourlyOffset`, which writes a fresh anchor at the press instant, and the press
renders at `liveCenter == anchor`. So the discrete jump is identical to current behavior. The only
behavioral change is that a *background* refresh of a window that excludes `now` now re-uses that frozen
anchor instead of drifting.

## Files
- `app/.../widget/WidgetStateManager.kt` — `KEY_GRAPH_ANCHOR_MS_PREFIX` (added), `getGraphAnchorMs`,
  anchor write inside `setHourlyOffset`, `resolveHourlyCenterTime`, `clearWidgetState` removal line.
- `app/.../widget/WidgetRenderer.kt` (`:108`) — use the resolver.
- `app/.../widget/handlers/WidgetIntentRouter.kt` (`:807`) — use the resolver.

## Verification
1. Unit test (`app` Robolectric/JVM where `WidgetStateManager` is testable, mirroring existing state
   tests): with a fixed `now`, assert `resolveHourlyCenterTime` returns the frozen anchor when
   `offset` is outside `[-forwardHours, backHours]`, and a `now`-tracking center when inside; and that a
   later `now` does not move the history result but does move the live result.
2. On device (Samsung/emulator): scroll the temperature hourly graph back into history with the arrows;
   leave it idle across a current-temp UI tick / trigger `ACTION_REFRESH`; confirm via screenshot + the
   `buildHourDataList … visualWindow=` log that the window does **not** advance. Then view the current
   day (offset 0) idle across a tick and confirm it **does** advance. Confirm arrow presses still jump
   exactly as before.

## Status of other work in this branch (already committed / verified)
- Issue 1 (actual-low label suppression) committed `d9d64b04`, emulator-verified.
- Issue 2 (single-day click → actual extrema match daily bar) implemented + `:shared` test green +
  **user-verified on Samsung**. Uncommitted. Plan: `plans/260616-hourly-single-day-actual-extrema-match-daily.md`.
