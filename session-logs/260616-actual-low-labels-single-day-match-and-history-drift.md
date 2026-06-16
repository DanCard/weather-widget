# Session log — Actual-low labels, daily↔hourly match, history drift freeze

**Date:** 2026-06-16
**Branch:** main
**Theme:** Three temperature-graph issues raised live from the emulator/Samsung: (1) the absolute
actual low sometimes isn't labeled, (2) the daily bar and the hourly graph disagree on a past day's
high/low, (3) a history hourly view drifts forward on its own over time.

---

## Overview

All three were diagnosed against live device data (logcat + pulled `weather_database`), not just code
reading. Each turned out to be a different mechanism than first assumed. Issue 1 is committed; issues 2
and 3 are implemented + tested + (issue 2) user-verified on Samsung, still uncommitted.

Relevant memories written: `daily_vs_hourly_actual_extrema_mismatch`.
Plans: `plans/260616-label-absolute-low.md`, `plans/260616-hourly-single-day-actual-extrema-match-daily.md`.

---

## Issue 1 — Absolute actual low not labeled (committed `d9d64b04`)

**Symptom:** intermittent — the observed (pink) line's absolute low label appears in some views, missing
in others; reproduced on emulator and Samsung.

**Root cause:** not crowding/thinning. The observed low was being dropped by **three independent
suppression gates**, depending on exactly where it landed relative to the right edge:
1. `resolveExtremaRole` tested `hours.lastIndex -> END` **before** `ACTUAL_LOW`, so a low *at* the edge
   index resolved to `END` (previous-day case).
2. `checkFetchDotSuppression` relabeled any edge fetch-dot index to `START/END` before the role
   mattered — clobbered the low *at* the NOW edge (current-day/emulator case).
3. `checkEndpointSuppression` edge-declutter dropped `ACTUAL_LOW` when it sat *near* (≤~5 of) the edge —
   Samsung, where a short forecast tail pushed the low just inside the edge window.

**Fix** (`shared/.../graph/TemperatureLabelResolver.kt`): actual extrema win over `START/END` in
`resolveExtremaRole` (kept below `HIGH/LOW` to preserve the dual-label injection); exempt
`ACTUAL_HIGH/ACTUAL_LOW` from the fetch-dot override; drop `ACTUAL_*` from the edge-suppressed roles
(mirroring `HIGH/LOW`). Three new regression tests in `TemperatureLabelSuppressionTest`. Emulator log
confirmed `LabelAccepted: role=ACTUAL_LOW idx=288`.

## Issue 2 — Daily bar vs hourly graph disagree on a past day (uncommitted, Samsung-verified)

**Symptom:** Samsung 2026-06-14 NWS: daily bar low 60.9 / high 73.7, hourly graph 61.0 / 73.0.

**Investigation:** pulled `weather_database`; for 06-14 NWS the three sources gave three different lows
(`daily_extremes` 60.9, `hourly_forecasts` 60.0, `observations` 57.0 — the 57° was a far station, not
the IDW-weighted line). Established it is **not** rounding, **not** smoothing (the actual line is raw —
`TemperatureExtrema.kt:44`), **not** single-station (NWS blends all stations).

**Root cause:** both views call the same `ActualTemperatureSeriesBuilder.blendObservationSeries`, which
**dedup-thins output to one point per 5 min** (`DEDUP_MS`, greedy over the whole input). The two callers
feed different obs sets + windows, so the thinning keeps different representative samples → different
max/min. Same window **and** same obs input ⇒ identical thinning ⇒ identical extrema (the user chose:
match the daily bar exactly).

**Fix:** single-day mode for the clicked-day hourly view.
- `shared/.../ActualTemperatureSeriesBuilder.build(singleDayDate)` bounds the blend window + its
  observation input to that calendar day (mirrors `ActualsAggregator.blendDailyExtremesViaSeries`), and
  bounds the display grid. New `:shared` test proves `build(singleDayDate)` reproduces
  `ActualsAggregator.aggregate` high/low exactly (even when thinning clips the true peak — both clip
  identically).
- App plumbing: `WidgetStateManager` single-day-date state (set on a **past**-day bar click, cleared on
  scroll/zoom), threaded `TemperatureStateResolver` → `TemperatureHourDataBuilder.buildHourDataResult`
  (forecast grid + actual series both day-bounded).
- Side benefit: single-day views are inherently drift-free (window overridden regardless of `now`).

## Issue 3 — History hourly view drifts forward over time (uncommitted)

**Symptom:** while viewing history, an automatic refresh advances the window a little. Should only
advance when the current day / now point / fetch dot is in view. **Arrows must work exactly as before.**

**Root cause:** every render computes `centerTime = now.plusHours(hourlyOffset)` (`WidgetRenderer.kt:108`),
so periodic UI ticks and post-fetch redraws re-derive the center from a newer `now` → history drifts.

**Fix:** anchor-based center resolution in `WidgetStateManager`.
- `setHourlyOffset` (the single navigation chokepoint) records an absolute `graphAnchorMs = now+offset`.
- `resolveHourlyCenterTime(widgetId, now, zoom)`: if the window still includes the now point
  (`offset in -forwardHours..backHours`) return live `now+offset` (advances); else return the frozen
  anchor. Used at `WidgetRenderer.kt:108` (auto/full render, covers all view modes + post-fetch) and
  `WidgetIntentRouter.kt:807` (`refreshGraphView`). The data-window fetch check at
  `WidgetIntentRouter.kt:344` stays on live `now`.
- Arrows unaffected: each press rewrites the anchor at `now+offset`, so the jump is identical to today;
  only the *background* refresh stops creeping. Two Robolectric tests (history frozen across simulated
  time; live view advances).
- Open question flagged to user: with WIDE (±12h), a small scroll-back keeps the now point in view so it
  still advances by the stated rule; offer a one-line gate change if they want any non-zero offset to
  freeze.

---

## Build / test / verify notes

- `:shared:test` green; new tests in `TemperatureLabelSuppressionTest`, `ActualTemperatureSeriesBuilderTest`,
  `WidgetStateManagerTest`.
- Device gotcha: method-body-only `:shared` edits leave the app dex `UP-TO-DATE` (Kotlin compile
  avoidance); had to `:app:installDebug --rerun-tasks` to actually ship a shared change to the device.
- Verified live via `adb logcat` (`TempExtrema`/`TempLabelResolver` breadcrumbs) and a pulled
  `weather_database` queried locally with `sqlite3`.

## Still open

- Commit issues 2 and 3 (issue 1 already committed).
- Confirm issue-3 boundary behavior (small scroll-back) with the user.
