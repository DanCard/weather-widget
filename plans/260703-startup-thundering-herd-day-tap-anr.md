# Startup Thundering Herd + Day-Tap ANR Mitigation

**Date:** 2026-07-03
**Status:** IMPLEMENTED (Phases 1, 3, 4; Phase 2 skipped — see Outcome)

## Outcome (2026-07-03)

Implemented:
- **Phase 1**: `launchAsync` in `WeatherWidgetProvider.kt` now takes `context` and races a
  `GO_ASYNC_WATCHDOG_MS = 8_000L` watchdog against the work coroutine; whichever finishes first
  calls the once-guarded `finishOnce()`. All 11 call sites updated. Watchdog firing logs
  `CLICK_WATCHDOG` (WARN, reaches Crashlytics).
- **Phase 3**: New pure `StartupFetchPolicy.kt` (`primaryFetchDelayMs`, jittered 45–90s normal /
  5–15s if data age ≥ 6h via `DataFreshness.getDataAgeMinutes`). Wired into
  `checkStalenessAndFetch`, replacing the old fixed `STARTUP_STALE_REFRESH_DELAY_MS = 1_500L`
  (removed).
- **Phase 4 (revised)**: `historyRepairDelayMs()` — jittered **10–20s**, not the originally-planned
  5–6min. Discovered `maybeEnqueueHourlyObservationBackfill` and
  `enqueueForecastCoverageRefresh` are shared with the interactive missing-hourly-data day-tap
  banner flow (`NO_HOURLY_MESSAGE_DURATION_MS = 8_000L`), which already tolerates a short wait but
  would read as broken after minutes of delay. Applied via `setInitialDelay` on
  `HourlyObservationBackfill.kt`'s work request and a new `initialDelayMs` param on
  `RefreshScheduler.enqueueForcedRefresh` (default 0, so `manual_refresh` callers are unaffected).
- Added `StartupFetchPolicyTest.kt` (8 cases); full `testDebugUnitTest` suite green;
  `installDebug` verified live on emulator — day-tap flow still switches views correctly
  (`CLICK_DAILY` → `TEMP_PIPELINE_PERF` totalMs=509 → paint), no `CLICK_WATCHDOG` on a warm
  process, no crashes.
- Added `WeatherWidgetProviderLaunchAsyncWatchdogTest.kt` (Robolectric + `StandardTestDispatcher`,
  same pattern as `WeatherWidgetProviderNoHourlyRoboTest`): widened `launchAsync` from `private` to
  `@VisibleForTesting internal` and `GO_ASYNC_WATCHDOG_MS` from `private const val` to `const val`
  (matching the existing `finishPendingResultSafely`/`NO_HOURLY_MESSAGE_DURATION_MS` precedent) so
  the watchdog can be driven directly with a synthetic slow `block()` under virtual time, instead
  of needing the full click-handling DB/render pipeline to actually hang for 8s. Three cases:
  watchdog fires + block still completes after early release; watchdog does not fire for a fast
  block; watchdog-then-completion doesn't double-`finish()`. Checked for existing
  `HourlyObservationBackfill`/`RefreshScheduler` delay tests — none exist; only the pure
  `buildRefreshScheduleDecision` is tested (`WidgetIntentRouterRobolectricTest.kt`). Verifying the
  actual `setInitialDelay` value on the enqueued `WorkRequest` would need new
  `WorkManagerTestInitHelper` scaffolding with no precedent in this repo — left as a possible
  future addition rather than introduced speculatively; the delay *values* are covered by
  `StartupFetchPolicyTest`.

Skipped:
- **Phase 2** (widen `STARTUP_DEBOUNCE_MS` / coalesce triggers): investigation showed the 4×
  `SYNC_START` burst wasn't duplicate calls to the *same* debounced path — it was several
  *independent* unique-work lanes (periodic schedule, `on_update_stale`, charging-loop heartbeat,
  coverage refresh) each firing their own first run near T+0. Widening the onUpdate debounce
  wouldn't touch that; Phases 3–4 address the actual independent lanes directly. Not revisited.
- Charging-loop scheduler (`CurrentTempUpdateScheduler`) and per-source throttle
  (`CurrentTempRepository`) left untouched — both are already-tuned steady-state systems; the
  "all 4 sources fetch on first-ever run" behavior comes from no prior per-source throttle state
  on a fresh install/emulator, not a bug in the herd sense. Flagged as a possible future refinement
  but out of scope for this pass to avoid destabilizing tuned battery/throttle logic.
- Interaction yield gate: still deferred per original plan, pending evidence it's needed after the
  above.

## Original plan (for reference)

**Status:** PROPOSED

## Problem

On a freshly-booted emulator, tapping the today column in daily view took 15+ seconds to
"register." Investigation (logcat + DropBox ANR trace + `app_logs`) showed the tap registered in
17ms — but the `goAsync()` render coroutine couldn't call `PendingResult.finish()` within the
**10-second foreground-broadcast deadline** (widget PendingIntents carry
`FLAG_RECEIVER_FOREGROUND`; `goAsync()` does NOT extend the deadline). Android declared an ANR,
killed the process, cold-started it again (~6s), and the view switched ~32s after the tap.

The render was slow because the process had just started during emulator boot and was competing
with its own startup burst, all in the same process / `Dispatchers.IO` pool / SQLite file:

- 4× `SYNC_START` within ~1s (onUpdate, charging loop, startup repaint each enqueued work)
- 4-source current-temp fetch (`CURR_FETCH_START targets=NWS, OPEN_METEO, SILURIAN, TOMORROW_IO`)
- Observation backfills (428 rows/station inserts) + coverage-gap refresh
- JIT compiling everything (debuggable builds can't AOT) + boot-storm paging (4,365 major faults)

Two-part fix: **cap the worst case** (never let a slow click become an ANR kill) and **shrink the
herd** (defer/stagger low-priority startup work). Widget paint-from-cache is untouched — it's
already decoupled from fetching and was never the problem.

## Evidence anchors

- ANR trace: `dumpsys dropbox --print data_app_anr` (emulator `/data/anr` not readable without root)
- `app_logs`: `CLICK_DAILY` 17:46:50 → `PROC_EXIT reason=ANR` → paint 17:47:22
- Warm renders are fine: `TEMP_PIPELINE_PERF totalMs=2700` (second attempt), 651ms later

## Phase 1 — goAsync watchdog (the backstop)

**File:** `WeatherWidgetProvider.kt` — `launchAsync()` (~line 953)

- Wrap the `PendingResult` in a once-only finisher (guard flag; double `finish()` throws;
  build on existing `finishPendingResultSafely`, line ~1154).
- Alongside the work coroutine, launch a watchdog: `delay(8_000)` then finish-once with
  reason `watchdog` (logged). Work coroutine's `finally` cancels the watchdog and
  finish-onces with reason `completed`.
- The work coroutine lives in the provider's process-wide scope (line 96) and continues after
  early finish; the render paints late instead of the process being killed.
- Covers ALL interactions (day click, nav, toggles, zoom) since they funnel through `launchAsync`.
- Log the watchdog firing at INFO to `app_logs` (tag `CLICK_WATCHDOG`) — permanent, low-frequency.
- NOT in scope: `UIUpdateReceiver` / `ScreenOnReceiver` goAsync paths (system broadcasts on the
  background queue, 60s deadline). Note for a follow-up if we ever see ANRs there.

**Trade-off accepted:** after early `finish()` the process loses foreground-broadcast priority
and could be frozen mid-render under memory pressure. Current behavior in that case is a
guaranteed ANR kill, so this is strictly better.

## Phase 2 — coalesce startup triggers

**File:** `WeatherWidgetProvider.kt` — `STARTUP_DEBOUNCE_MS` (line ~1004)

- Widen the per-widget startup debounce from 500ms to ~5s **for fetch-triggering paths only**
  (UI repaints stay debounced at 500ms — cheap and user-visible).
- Audit the first-minute triggers (onUpdate staleness check, charging loop, opportunistic job)
  so one process start produces ONE fetch worker run, not four. All enqueues keep
  `KEEP`/`APPEND_OR_REPLACE` — never `REPLACE` (cancels RUNNING worker → native SIGSEGV, see
  memory `samsung_widget_dead_native_sigsegv`).

## Phase 3 — staged + jittered startup fetch delays

**Files:** `WeatherWidgetProvider.kt` (`STARTUP_STALE_REFRESH_DELAY_MS = 1_500L`, line ~1047;
`triggerImmediateUpdate` already plumbs `initialDelayMs` → `setInitialDelay`),
`CurrentTempRepository.kt` (`targetSources` fan-out, line ~171)

- **Displayed source** (per `getActiveDisplaySourceIds()`): startup catch-up fetch delayed to
  T+45–90s (random jitter per enqueue). Replaces the current 1.5s deferral.
- **Non-displayed sources:** no startup fetch at all — they wait for the next periodic tick
  (they're already throttled in steady state; startup shouldn't be their fast lane).
- **Staleness tier:** if displayed-source data is very stale (> 6h — phone off overnight),
  use a short delay (~10s + jitter) instead of the T+45–90s lane. "Stale" (>30min) is not urgent;
  "very stale" is.
- **Exemptions — always immediate:** user tap on refresh, screen-unlock-while-charging fetch,
  and anything reason-tagged as user-initiated. Delay applies ONLY to automatic startup catch-up.
- Delays via WorkManager `initialDelay` (survives process death/Doze), never coroutine `delay()`.
- Policy decisions (delay windows, tier threshold, jitter) live in a pure function
  (e.g. `StartupFetchPolicy.kt` next to `ForecastFetchPolicy`) — unit-testable without mocks,
  per project testing strategy.

## Phase 4 — defer history-repair work

**Files:** `handlers/HourlyObservationBackfill.kt` (~line 133), `handlers/RefreshScheduler.kt`
(coverage refresh, ~line 141)

- Observation backfills and coverage-gap refresh triggered within the first minutes of process
  start get `initialDelay` ≥ 5min (+ jitter). They repair the past; nothing on screen needs them.
- These were the biggest SQLite-write contenders in the storm (428 rows/station).
- Keep unique-work names + `KEEP` so repeated startup triggers don't stack duplicates.

## Later / out of scope (recorded, not planned)

- **Interaction yield gate** (cooperative "user interaction in flight" flag checked at natural
  checkpoints in backfill/fetch loops). Revisit only if contention persists after Phases 1–4.
  Must be yield-based, never cancellation (memory `current_temp_battery_cancel_race`).

## Verification

1. Unit tests for `StartupFetchPolicy` (delay lanes, staleness tiers, exemptions) and the
   once-only finisher — pure functions, no mocking framework.
2. Emulator repro: cold-boot AVD (not snapshot), tap today column within the first minute.
   Expect: no `am_anr` in `logcat -b events`, `CLICK_WATCHDOG` may fire, view switches without
   process kill; `app_logs` shows single `SYNC_START` and delayed `CURR_FETCH_START`
   (displayed source only).
3. Steady-state regression: warm device/emulator — confirm user refresh + unlock-while-charging
   fetches remain immediate, periodic cadence unchanged.
4. `./scripts/emulator-tests.sh` (never `connectedDebugAndroidTest`).

## Decisions to confirm with user

- Delay windows: displayed T+45–90s, non-displayed = next periodic tick, backfills T+5min — OK?
- Very-stale fast-lane threshold: 6h?
- Watchdog deadline: 8s (2s margin under the 10s ANR limit)?
