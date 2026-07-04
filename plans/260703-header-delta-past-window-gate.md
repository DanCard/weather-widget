# Plan: Keep Header Forecast-Delta Visible on Future Scroll (Hide Only When Scrolled Fully Into the Past), Shared with Desktop

## Context

Bug report (emulator, zoomed-in hourly graph): the header shows a forecast-bias delta
(e.g. `-2.1`, meaning last observed temp vs. what the forecast predicted for that hour —
`CurrentTemperatureResolver.appliedDelta`, `shared/src/main/kotlin/com/weatherwidget/widget/CurrentTemperatureResolver.kt:188-229`).
Pressing the right nav arrow (scrolling the zoomed graph into the future) makes the header
delta disappear, even though `appliedDelta` itself is still a valid, unchanged number (it's
computed from the real "now", independent of graph navigation).

Root cause: `TemperatureStateResolver.kt:222-224` gates the header's `deltaVisible` on
`isNowLineVisible = graphHours.any { it.isCurrentHour }` — true only when the literal current
hour is inside the currently-rendered window. Any right-arrow press in the narrow zoom
(`NARROW.navJump=2`, `backHours=2/forwardHours=2`, `shared/.../graph/ZoomStage.kt:27-29`) is
enough to push "now" out of the window, so the delta vanishes regardless of scroll direction.

This project already solved an analogous problem for the **ghost line** (the dashed "expected"
curve = forecast + `appliedDelta`, drawn in the graph body): a prior plan
(`plans/260629-ghost-line-extension-narrow-view.md`) relaxed its gate from
"now must be visible" to a shared `GhostLineGate.shouldProcess(...)`
(`shared/src/main/kotlin/com/weatherwidget/shared/graph/GhostLineGate.kt`), which — via the
`clipRect(fetchDotX.coerceAtLeast(0f), 0f, width, height)` geometry in
`TemperatureGraphRenderer.kt:350-354` — draws across the full width when scrolled into the
**future** (fetchDotX negative → clamps to 0 → full-width clip) but draws **nothing** when
scrolled into the **past** (fetchDotX becomes a positive value beyond the graph's right edge →
`clipRect(left>right, ...)` → empty clip region). That is: the ghost line is present exactly
when "now" is in view or ahead of the window, and absent exactly when the window has scrolled
fully into the past. Confirmed already implemented and live in both Android and desktop code —
this is not a proposal, it's the current behavior.

**Decision (per user): the header delta should follow the exact same rule as the ghost line** —
visible on today/now and all future scroll, hidden only once the window is scrolled entirely
into the past. Not simply "always visible" (that was my first, wrong suggestion) — the user
correctly pointed out the ghost line has no past-facing analog, and the header should match
that, not diverge into "always on."

**Desktop must get the same fix, sharing the decision logic** (not reimplemented ad hoc per
platform) — matching this project's established pattern of putting pure gating logic in
`:shared` (e.g. `GhostLineGate`, `YesterdayDeltaLabel`) and having each platform supply its own
geometry/time inputs.

## Current State Findings (both platforms) — read before implementing

**Android** — two independent call sites gate the delta on `isNowLineVisible`, not one:
1. `TemperatureStateResolver.kt:222-224` — initial header build (`deltaVisible`).
2. `TemperatureViewHandler.kt:413` — a **second, separate** partial-RemoteViews-update path
   (async "refined" resolution follow-up) that re-checks `params.isNowLineVisible` before
   showing `current_temp_delta`. Also used at `TemperatureViewHandler.kt:442,446` inside
   `shouldApplyRefinedHeaderUpdate(...)` to decide whether the delta "changed" between the quick
   and refined resolutions.
   Both are fed `resolutionResult.isNowLineVisible` / `params.isNowLineVisible`, sourced from the
   same `TemperatureStateResolver.kt:222` computation. **Both must be updated together** or the
   async refine path will silently re-hide the delta after the initial paint shows it.

`isNowLineVisible` has no other behavioral consumer — grepping the widget package, its only other
uses are debug-log strings (`TemperatureTextMode.kt:84,118`, `DailyHeaderBinder.kt:271`). It does
**not** gate the graph's actual vertical "NOW" line (that's `nowIndicatorVisible`, computed
independently inside `TemperatureGraphRenderer.kt:222`, `nowX in 0f..widthPx`). So there is no
risk of accidentally changing the graph's own now-line by touching this — but do **not** repurpose
the `isNowLineVisible` field itself for the new semantics, since its name and existing debug-log
meaning ("current hour literally in view") are still accurate and potentially useful. Add a
sibling boolean instead (see Implementation Outline).

**Desktop already has header delta UI — it is not missing, it is simply ungated:**
- `WidgetHeader` composable, `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt:1187-1257`.
  `deltaTemp = forecast.appliedDelta?.takeIf { abs(it) >= 0.1f }` (line 1211), rendered at
  lines 1248-1257. **No gating at all today** — it shows the delta regardless of `hourlyOffset`
  (i.e. regardless of graph scroll position, including all the way to `MAX_HOURLY_OFFSET = 720`
  hours in the past, `Main.kt:80`). This is the opposite problem from Android: Android over-hides
  (hides on any non-"now" view including future), desktop under-hides (never hides, including deep
  past). Neither matches the ghost line's actual behavior; the fix should bring both in line with it.
- Same value is duplicated to the XFCE panel/genmon text: `PanelIpcServer.kt:72-96`
  (`deltaText = forecast?.appliedDelta?.takeIf { abs(it) >= 0.1f }`). Same ungated pattern.
  This should get the same gate (or, if the panel has no navigable "window" concept at all since
  it's a static clock-style readout rather than a scrollable graph, it may be legitimate to leave
  it always-on — **flag this as a decision point below**, don't silently change panel behavior
  without confirming intent.
- Desktop already calls the shared `CurrentTemperatureResolver.resolve()` for this value —
  `DesktopWeatherRepository.kt:32-80` (`resolveForForecastResult`), matching Android's formula
  exactly (same shared class, `shared/src/main/kotlin/com/weatherwidget/widget/CurrentTemperatureResolver.kt`).
  Good: the number itself is already single-sourced; only the *visibility gate* needs unifying.
- Desktop already calls the shared `GhostLineGate.shouldProcess(...)` identically to Android —
  `TemperatureGraph.kt:340-351` (line draw) and `:694-702` (label placement) — so desktop's ghost
  line already has the exact "future yes / past no" behavior we're matching the header to. Good
  prior art already in place on desktop; we're just not yet using it (or its underlying time
  math) for the header.
- Desktop's hourly nav/window system is a direct analog of Android's:
  `DesktopConfig.hourlyOffset` (`DesktopConfig.kt:28`), left/right arrows (`Main.kt:900-919`),
  window built by `temperatureGraphHourWindow(centerMs, backHours, forwardHours, zoneId)`
  (`TemperatureGraph.kt:87-99`) — same `[alignedCenter - backHours, alignedCenter + forwardHours]`
  shape as Android's `TemperatureHourDataBuilder.buildHourDataResult`. `backHours`/`forwardHours`
  come from `DesktopGraphUtils.backHoursFor/forwardHoursFor(zoomFactor)` (`DesktopGraphUtils.kt:66,68`).
- **Gap**: `WidgetHeader` (`Main.kt:1187`) does not currently receive the graph's window bounds at
  all — it only gets `config` (has `hourlyOffset`, `zoomFactor`) and a rough `headerTime =
  LocalDateTime.now().plusHours(config.hourlyOffset.toLong())` (`Main.kt:775`), which is an
  hourlyOffset-only approximation, not the zoom-aware `[start,end]` the graph actually renders.
  Good enough to reconstruct a window-end estimate (`headerTime + forwardHoursFor(zoomFactor)`),
  but for exact parity with what's on screen, prefer computing via the same
  `temperatureGraphHourWindow(...)` helper the graph itself uses, with the same `centerMs`
  derivation, and pass the result (or just the boolean) down to `WidgetHeader`.

**Noted but out of scope for this task** (do not fix now, just don't let it block the plan):
Desktop's *ghost line curve* uses a separately, locally computed `appliedDelta` inside
`TemperatureGraph.kt:243-248` (diff of last plotted actual point vs. its forecast point) — a
different formula/window than the shared resolver's value used for the header. They're the same
concept but can diverge numerically. Unifying them would be a nice follow-up but is unrelated to
this visibility-gate fix and touches curve rendering, which the user has not asked to change.

## Recommended Approach

1. **New shared pure predicate**, same architectural slot as `GhostLineGate` (a small stateless
   object next to it): `shared/src/main/kotlin/com/weatherwidget/shared/graph/HeaderDeltaGate.kt`.

   ```kotlin
   object HeaderDeltaGate {
       /**
        * Mirrors GhostLineGate's future-yes/past-no behavior: visible when the graph window
        * includes "now" or extends into the future; hidden once the window has scrolled
        * entirely before "now".
        */
       fun isVisible(
           windowEndTime: LocalDateTime,
           now: LocalDateTime,
           appliedDelta: Float?,
           minAbsDelta: Float = 0.1f,
       ): Boolean {
           if (appliedDelta == null || abs(appliedDelta) < minAbsDelta) return false
           return !windowEndTime.isBefore(now.truncatedTo(ChronoUnit.HOURS))
       }
   }
   ```

   Each platform supplies its own `windowEndTime` (each already computes one during its own
   hour-window build) — this is the same shape as `GhostLineGate.shouldProcess` taking
   platform-supplied `fetchDotX`/`spanHours`/etc. Do not try to unify the window-builders
   themselves (`TemperatureHourDataBuilder.kt` vs. `temperatureGraphHourWindow` in
   `TemperatureGraph.kt`) — that's a larger, unrelated refactor; only the gate decision needs to
   be shared.

   Keep the `0.1f` threshold as a parameter with the existing default rather than introducing a
   second constant; Android already has this literal duplicated three times
   (`TemperatureViewHandler.kt:38`, `DailyHeaderBinder.kt:16`, `TemperatureStateResolver.kt:43`) —
   optionally point all three at one shared constant while touching this code, but treat that as
   a minor cleanup, not a requirement.

2. **Android integration**:
   - `TemperatureStateResolver.kt:222-224`: compute `graphWindowEndTime = graphHours.lastOrNull()?.dateTime`
     and a new `isDeltaWindowVisible = graphWindowEndTime != null && HeaderDeltaGate.isVisible(graphWindowEndTime, now, delta)`.
     Keep `isNowLineVisible` as-is (still used for debug logging). Use the new value for
     `deltaVisible` instead of `isNowLineVisible`.
   - Thread `isDeltaWindowVisible` alongside `isNowLineVisible` through whatever result/params
     types carry it to `TemperatureViewHandler.kt` (mirror the existing `isNowLineVisible` field
     wiring at `TemperatureStateResolver.kt:59,348,546,580` → `TemperatureViewHandler.kt:206,210,240,364`).
   - `TemperatureViewHandler.kt:413`: gate on the new field instead of `params.isNowLineVisible`.
   - `TemperatureViewHandler.kt:441-448` (`shouldApplyRefinedHeaderUpdate`): same swap for
     `quickDeltaVisible`/`refinedDeltaVisible` — these need the *new* windowEndTime evaluated per
     resolution (quick vs. refined may have different `now`/window if time has passed between
     passes; reuse whatever `graphWindowEndTime` was already resolved for the current graph state,
     not recomputed per resolution).
   - Double check `DailyHeaderBinder.kt` / `DailyViewHandler.kt` (`isNowLineVisible: Boolean?`
     nullable variants) — these appear to be the **daily** (not hourly) header path; confirm
     whether the daily view has its own delta display that should get the same treatment or is
     unaffected (the daily header may not navigate the same way). Read before assuming it's in
     scope; don't change it reflexively just because the field name matches.

3. **Desktop integration**:
   - Compute the graph's actual window end time once per render (reuse
     `temperatureGraphHourWindow(centerMs, backHours, forwardHours, zoneId)` with the same
     `centerMs`/`backHours`/`forwardHours` the graph composable already derives from
     `config.hourlyOffset`/`config.zoomFactor`/drag state), rather than the rougher
     `headerTime`-only approximation — for exact parity with what's drawn.
   - Pass the resulting `windowEndTime` (or a precomputed boolean) into `WidgetHeader(...)`
     (`Main.kt:767-777` call site, `:1187` signature) and use `HeaderDeltaGate.isVisible(...)` at
     line 1211 instead of the current unconditional `forecast.appliedDelta?.takeIf { abs >= 0.1f }`.
   - Decide on `PanelIpcServer.kt:72-96` (XFCE panel/genmon text): confirm with the user whether
     the panel — which has no scrollable graph of its own, just a clock-style readout — should
     also hide when the *popup's* graph is scrolled into the past, or whether it's a distinct
     "always current" surface that should stay always-on regardless of what the popup graph is
     showing. Recommend: leave the panel always-on (it's not "the hourly graph view", it's a
     glanceable summary) unless the user says otherwise — flagging this explicitly rather than
     changing it silently.

## Critical Files to Modify
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/HeaderDeltaGate.kt` (new)
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt` (`WidgetHeader` + call site)
- Possibly `desktop/src/main/kotlin/com/weatherwidget/desktop/PanelIpcServer.kt` (pending the
  panel decision above — may end up unchanged)

Read-only / reference:
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/GhostLineGate.kt` (pattern to mirror)
- `shared/src/main/kotlin/com/weatherwidget/widget/CurrentTemperatureResolver.kt` (source of `appliedDelta`)
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureHourDataBuilder.kt` (Android window math)
- `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt` (desktop window math + existing `GhostLineGate` usage)
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyHeaderBinder.kt`,
  `DailyViewHandler.kt` (confirm in/out of scope per above, don't touch unless confirmed relevant)

## Tests
- New shared plain-JUnit test for `HeaderDeltaGate` (no framework needed — pure function),
  covering: today/now-in-window → visible; scrolled future (window entirely after now) →
  visible; scrolled past (window entirely before now) → hidden; delta null → hidden; delta
  below threshold → hidden. Mirror the existing test style for `GhostLineGate` if one exists
  (check `shared/src/test`).
- Update/add Android unit tests around `TemperatureStateResolver`'s delta-visibility decision
  and `TemperatureViewHandler.shouldApplyRefinedHeaderUpdate` for the new field, replacing any
  existing assumption that future-scroll hides the delta.
- Desktop: check for existing Compose UI tests on `WidgetHeader` (`testTag("current_temp_toggle")`
  suggests some UI testing exists) and add a case for delta visibility across past/future scroll
  if the harness supports it; otherwise cover via a plain-JUnit test on the window-end computation
  feeding `HeaderDeltaGate`.

## Verification (end-to-end, per CLAUDE.md)
1. Unit tests green (`:shared:test`, `:app:testDebugUnitTest --tests "*TemperatureStateResolver*" --tests "*TemperatureViewHandler*"`, `:desktop:test` if applicable).
2. Emulator: `./gradlew installDebug`, add widget, zoom to narrow hourly view on today, confirm
   header delta shows; press right arrow repeatedly into the future — delta should **stay**
   visible (this is the reported bug, now fixed); press left arrow past today into history —
   delta should **disappear** once the window is fully in the past (matches ghost line). Pull
   logcat to confirm no crash and check the debug trace lines noted above. Screenshot before/after
   per the project's PNG→JPG conversion step.
3. Desktop: `scripts/buildStart-desktop.sh`, open popup, zoom hourly narrow, scroll future/past
   with the same expectations; check the XFCE panel text stays consistent with whatever the
   panel decision above lands on.
4. No regression on wide/3-day views, non-hourly (daily) header, zero-delta case, no-observation
   case (appliedDelta null).

## Open Decision for User (before implementing)
Should the XFCE panel/genmon delta text (`PanelIpcServer.kt`) follow the same past-scroll gate as
the popup header, or stay always-on since it's a separate, non-scrollable "current conditions"
surface? Recommend always-on (leave unchanged) unless you want strict parity everywhere.
