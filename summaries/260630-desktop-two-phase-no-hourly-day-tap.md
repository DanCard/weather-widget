# Desktop two-phase no-hourly day-tap flow

## Problem

Clicking a far-future daily column (e.g. Tuesday next week, past the NWS hourly horizon) on the
desktop switched to the hourly graph, which rendered as a **black screen with only a header** because
that day has no hourly data. The desired behavior (parity with Android) is to stay on the daily view
and show a two-phase message: a pending banner on tap, then a result banner after a refresh.

## What was implemented

Two-phase no-hourly day-tap flow on the desktop, matching Android's behavior:

| Phase | Behavior |
|-------|----------|
| **1 — On tap** | `handleDayClick` detects no hourly data → shows *"Hourly data missing for Tue Jul 7 / A refresh will be triggered"* and stays on the daily view |
| **2 — Refresh** | `onNeedHourlyRefresh` lambda runs `ensureForecastDays` → reloads forecast → always fires its completion callback |
| **3 — Result** | Banner replaces with *"Results of refresh: Hourly data now available…"* or *"…No hourly data for Tue Jul 7 — data ends Mon Jul 6 at 4 PM"* |

## Files changed

- **`shared/…/NoHourlyChecker.kt`** — added `buildPendingMessage` + `buildResultMessage` (pure, shared)
- **`shared/…/NoHourlyCheckerTest.kt`** — 4 new unit tests for the message builders
- **`desktop/…/Main.kt`** — new `onNeedHourlyRefresh` callback param + lambda in `runApp()`; rewrote
  `handleDayClick` for the two-phase flow; added `day_tab_<date>` testTags; **removed the
  over-aggressive second-layer guard** that was breaking nav tests
- **`desktop/…/DesktopNoHourlyDayClickTest.kt`** — 3 new Compose integration tests
- **`desktop/…/PanelIpcServerTest.kt`** — fixed stale `buildStart.sh` assertion (script renamed to
  `buildStart-desktop.sh`)

## Test results

- `NoHourlyCheckerTest`: all pass
- `DesktopNoHourlyDayClickTest`: 3/3 pass
- Full `:desktop:test` + `:shared:test`: all green
- Android `:app` compiles clean

## Notable course-correction

The integration tests caught a real regression: a **second-layer guard** from a prior task
auto-redirected the hourly view back to daily whenever the visible window had fewer than 2 points.
That broke the existing `testHourlyNavigation*` tests (which deliberately pan into empty regions and
expect the nav arrows to remain). Since the first-layer `handleDayClick` check already fully prevents
the reported black-screen bug, the redundant second layer was removed — a case of integration tests
surfacing an interaction bug that unit tests wouldn't have.

## Design notes

- `WidgetPopup` gained `onNeedHourlyRefresh: (days, (List<HourlyForecast>)->Unit)->Unit = { _,_ -> }`.
  The default keeps all existing callers/tests compiling; the UI state machine lives entirely inside
  `WidgetPopup`, while `runApp()` just plumbs data.
- `onNeedHourlyRefresh` always invokes its completion callback (even on failure / no-new-data) so the
  banner never strands on the pending message.
- Shared message builders mean Android and desktop stay phrase-consistent; Android's
  `NoHourlyDayClickCoordinator` delegates `formatDayLabel`/`formatEndLabel` to `NoHourlyChecker`.
