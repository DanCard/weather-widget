# Session log — Hourly graph "Loading…" NaN crash → pin removal, 5-min blend window removal, and anchored-view re-render corruption

**Date:** 2026-06-17
**Branch:** main
**Status:** All work **uncommitted**. Several diagnostic logs left in place intentionally.
**Devices:** emulator-5554, Pixel 7 Pro (2A191FDH300PPW), Samsung SM-F936U1 (RFCT71FR9NT).

---

## Overview

Started from "hourly temperature graph not displayed" (stuck on the **"Today / --° / Loading…"**
placeholder) on all three devices. Root cause: a `renderGraph` crash. That unwound into a chain of
related work and several user-driven design decisions:

1. **Crash fix** — the single-day "pin" (commit e0ca3158) forced a rigid `00:00→24:00` hourly window;
   when a narrow data window was paired with it, trailing hours had `Float.NaN` forecasts →
   `roundToInt(NaN)` in the label engine → `renderGraph` threw → blank placeholder. **Removed the pin**
   (the rolling/anchor rule already covers the intended behavior) + added **NaN guards** + a regression
   test.
2. **Daily-bar vs hourly-graph high/low convergence** — removed the window-dependent **5-minute
   dedup-thinning** in `blendObservationSeries` so both views compute the same true extrema.
3. **Follow-up issues** from a live look:
   - *Issue 1:* daily bar (72.4) vs hourly graph (72.9) for yesterday still disagree — diagnosed
     (window is NOT the cause), **not yet fixed**.
   - *Issue 2:* an anchored past-day hourly view "gets messed up" on auto-refresh — diagnosed into
     two sub-symptoms; one **fixed** (Fix C), one **diagnosed but not fixed** (location fragmentation).

Plan files: `plans/remove-5min-blend-window.md` (focused Step-2 plan) and
`plans/dazzling-hopping-plum.md` (earlier combined draft).

---

## All prompts (verbatim, in order)

1. `Emulator: temperature hourly graph, not being displayed`
2. `That is not the issue, but you can try anyways.  Samsung also has same symptom.`
3. `Pixel 7 pro also has same problem.` *(interrupt)*
4. `Add logging if this isn't easy to diagnose` *(interrupt)*
5. `Issue is reproducible.  Reproduced on emulator and samsung` / `I reproduced on emulator and samsung`
6. `Can you explain pin.  Why pin?  Seems silly.`
7. `What do you think of getting rid of pin?  Rolling window should only happen when viewing today data, when now is visible or current observation dot`
8. `Why no guaranteed match?  Should be looking at same data.`
9. `I prefer to fix the reasons they diverge.  No reason to diverge , that I'm aware of.`
10. `So lets fix this in two steps: 1) lets get rid of the pin 2) try to fix diverge reasons`
11. `Why do we need a window?  Can we get rid of the window?`
12. `What do you think about the graph not using a 5 minute window?  Why is the window needed in the hourly graphs?`
13. `Nws stations don't report very often.  Can we just do a blend every time one of them reports?  Each one reports like in 15 minute intervals.`
14. `What do you think about writing a new plan that is specific / only about removing the 5 minute window?`
15. `Is this the updated plan or the old plan?  Can we stay focused on the 5 minute window removal, and not talk about step 1?`
16. `The plan you are presenting still has step 1`
17. `3 times you keep making the same mistake.  Should we start over?  You continue to present a plan with step 1 , when I've asked to focus on step 2.`
18. `/feedback` then `continue`
19. `2 issues:` … `1) Samsung : from daily forecast view, yesterday, high is 72.4. When I click on yesterday and taken to temperature hourly graph says high is 72.9. Should I expect them to match?` … `2) Emulator: Was looking at temperature hourly graph for today. There was an auto advance or refresh, and display gets messed up. Add logging if this isn't easy to fix.` … `Continue from where you left off.`
20. *(pasted `adb devices -l` output showing emulator back online)*
21. `What do you think if increasing the window size for calculating extremes that are used in daily forecast view?`
22. `add that diagnostic and report which it is.  For the high temperature shouldn't matter.  High temp occurs ruffly around 3 pm.  Far from midnight.  Station readings don't matter after some time, 4 hours?  For low that occurs ruffly around 5 am, same thing.  To far from midnight for window to influence.`
23. `waiting on issue 2 to reproduce.  Moving back to issue 1: What do you think if increasing the window size for calculating extremes that are used in daily forecast view?`
24. `Issue 2 is active on emulator, what me to repro for fresh logs?`
25. `reproduced on emulator`
26. `What messed up means:` `1) forecast line disappears for left 65% of screen.` `2) All labels on actual line disappear.` `What is causing a re-render?  Should we not re-render?`
27. `For A, should limit it to the graph and not the header.  Update of current temp should continue to happen.  Yes implement a + c.`
28. `Issue 2 still exists on emulator.  check logs`
29. `write session log to session-logs/ dir.  Include all prompts`

---

## Diagnosis method

Followed the project debug workflow throughout: `adb logcat`, pulled `weather_database` via
`run-as … cat`, queried with local `sqlite3`, and captured/converted screenshots. Added persistent
`app_logs` instrumentation so findings survive process freezes.

Key device evidence:
- Crash: `renderGraph failed: IllegalArgumentException: Cannot round NaN value` →
  `TemperatureLabelResolver.collectLabelCandidates` (roundToInt) ← `resolve widget=52
  hourlyForecastsRange=2026-06-16T19:00..2026-06-17T19:00` (narrow window + the today-pin's
  00:00→24:00 grid ⇒ NaN tail).
- Issue 1: `EXTREMA_WINDOW_DIAG date=2026-06-16 isolated=[hi=72.42@12:35 …] wide=[hi=72.42@12:35 …]`
  — identical both ways ⇒ window irrelevant (user's 3pm-peak/5am-trough reasoning was right; interp
  reach is only 3h). Hourly reports `hi=72.92@12:50, n=328` vs daily `hi=72.42@12:35, pts=316`.
- Issue 2: `HOURLY_CENTER_TRACE … offset=-22 includesNow=false branch=anchor(fixed)` ⇒ NOT drifting.
  `NAN_TEMP_INDICES [0..218]` (Tue 00:00–12:25) ⇒ forecast temps NaN on the left.
  After Fix C: `Deduplicated:[219,315,174,52,328,0]` with `ACTUAL_HIGH@174=72.92`,
  `ACTUAL_LOW@52=60.85` ⇒ actual labels restored.
  DB shows Tue forecast rows DO exist but morning rows are only at `37.4168434,-122.0889969` while
  afternoon rows + configured location are at `37.4168014,…` ⇒ **location fragmentation**; the
  render's exact-float location filter drops the morning rows.

---

## Changes (all uncommitted)

### Step 1 — remove single-day pin + NaN-safe rendering — DONE & VERIFIED
- `WeatherWidgetProvider.navigateToHourlyView`: dropped `setSingleDayDate(...)`; day-click navigates by
  offset only (rolling iff NOW in-window, else fixed anchor via `resolveHourlyCenterTime`).
- `WidgetStateManager`: removed `get/setSingleDayDate`, `KEY_SINGLE_DAY_EPOCH_PREFIX`, scroll/zoom
  clears; `clearWidgetState` keeps a legacy pref removal.
- Removed `singleDayDate` param from `ActualTemperatureSeriesBuilder.build`, `buildHourDataResult`,
  `TemperatureStateResolver`; desktop `hourlySingleDayEpoch` + day-click pin (TemperatureGraph/Main/
  DesktopConfig).
- NaN guards: `TemperatureExtrema.compute` picks high/low/forecast extrema only among finite temps.
- Regression test `shared/.../graph/TemperatureExtremaNaNTest.kt` (verified it fails without the guard).

### Step 2 — remove the 5-minute blend thinning — DONE & VERIFIED
- `ActualTemperatureSeriesBuilder.blendObservationSeries`: removed the `DEDUP_MS` skip so it blends at
  every distinct observation timestamp (data is sparse: NWS ~15 min, others hourly). Makes the emitted
  series window-independent. `dedupSkippedCount` kept as 0; `DEDUP_MS` const removed.
- Cross-pipeline regression test `ActualTemperatureSeriesBuilderTest.
  "hourly per-day extrema match the daily aggregate across a multi-day window"` — anchors on the TRUE
  off-5-min peak; verified it fails with the dedup restored.
- `:shared:test` + `testDebugUnitTest` green; Pixel rendered full graph, no crash.

### Issue 2 — Fix A (header-only on opportunistic UI updates for anchored views) — IMPLEMENTED
- `TemperatureViewHandler.updateWidget` gained `uiOnly: Boolean`. When `uiOnly` AND NOW is outside the
  visible window (anchored past/future view), it skips the graph re-render and does a header-only
  current-temp `partiallyUpdateAppWidget` (`updateAnchoredHeaderCurrentTemp`) — current temp keeps
  updating, graph bitmap is preserved. Threaded `uiOnly` through `WidgetRenderer.updateWidgetWithData`
  and `WeatherWidgetWorker.updateAllWidgets` (`uiOnly = uiOnlyRefresh`).
- Verified: anchored widget logs `state=header_only_anchored`; today widget renders fully.

### Issue 2 — Fix C (NaN label guard made value-aware) — IMPLEMENTED & VERIFIED
- `TemperatureLabelResolver.collectLabelCandidates`: introduced `effectiveTemps` = forecast temp
  unless NaN, then the observed actual value for actual points. The dense-filter / left-edge
  suppression / NaN candidate-drop now use `effectiveTemps`. Fixes "all actual labels vanish on a
  partial-forecast render" (a regression from the Step-1 forecast-only NaN filter) while leaving all
  finite-forecast behavior — and existing label tests — unchanged. Confirmed on-device: `ACTUAL_HIGH`/
  `ACTUAL_LOW` labels reappear.

### Diagnostic logging left in (trim later)
- `HOURLY_PAINT_TRACE` (startup paint lifecycle + cancellation), `resolve_EMPTY`/`resolve_NULL_BITMAP`
  in `TemperatureStateResolver` / `WeatherWidgetProvider`.
- `HOURLY_DAY_EXTREMA` (per-day actual hi/lo + timestamps from the rendered hours) in
  `TemperatureStateResolver`.
- `HOURLY_CENTER_TRACE` (offset / includesNow / anchor branch) in `WidgetStateManager`.
- `EXTREMA_WINDOW_DIAG` (day-isolated vs wide blend argmax) in `ObservationRepository`.

---

## Open items (NOT fixed)

1. **Issue 1 — daily vs hourly actual high/low still disagree** (72.42@12:35 vs 72.92@12:50; 316 vs 328
   points). Window proven irrelevant. Likely the same **location-fragmentation** root as Issue 2's
   symptom #1 (the hourly path includes obs/forecast rows at multiple lat/lon precisions the daily
   recompute does not). Needs confirmation.
2. **Issue 2 symptom #1 — forecast line missing on left 65%** of an anchored past-day view.
   **Root cause: location fragmentation.** `WidgetRenderer.updateWidgetWithData` pins to one
   `bestHourlyMatch` location and filters hourly rows by **exact float** lat/lon equality; same-place
   rows at a different GPS precision (e.g. `37.4168434` vs configured `37.4168014`) are dropped → NaN
   forecast → no curve. **Proposed fix (awaiting go-ahead):** replace the exact-equality filter with a
   small proximity tolerance (~100m) so sub-precision fragments merge while genuinely different markers
   (e.g. `37.422`) are still excluded; mirror in `WeatherWidgetWorker.fetchHourlyForecasts`' bestPair
   pin. See memories `desktop_coordinate_fragmentation`, `shared_location_match_predicate`.

---

## Memory updates this session
- `hourly_singleday_pin_nan_crash` (new) — the crash, pin removal, NaN guards; Step 2 status corrected
  to "removing dedup necessary but NOT sufficient for daily↔hourly convergence" once real-data testing
  showed they still differ.
- `daily_vs_hourly_actual_extrema_mismatch` — annotated; the dedup removal did not fully resolve it.
- `feedback_exitplanmode_fresh_content` (new) — don't reuse prior plan text in ExitPlanMode when asked
  to refocus a plan (root of the 4 rejected ExitPlanMode attempts).
