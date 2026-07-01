# Session log — Samsung widget "dead / taps not working" = native SIGSEGV from cancelling a running WorkManager worker

**Date:** 2026-07-01
**Branch:** main
**Status:** Fix implemented, installed, and verified stable on-device. **Uncommitted** at log write time.
**Devices:** Samsung SM-F936U1 (`RFCT71FR9NT`, always plugged in), Pixel 7 Pro (`2A191FDH300PPW`), emulator-5554.

---

## Overview

Reported symptom: "taps aren't working on the Samsung widget." It turned out taps were never
broken — the widget's **process was repeatedly dying from a native `SIGSEGV`**, leaving a frozen
widget whose taps landed on a dead process. The crash was invisible to the app's crash logging
(that only captures JVM exceptions, not native crashes), so it masqueraded as a battery/touch
problem.

1. **Initial wrong lead (sticky banner).** A code trace suspected the recent missing-hourly banner
   feature: `DailyViewHandler.bindTransientMessage` set `R.id.widget_message_banner` but the three
   graph-view handlers never reset it. Fixed anyway (real latent bug: banner never rendered/cleared
   in Temperature/Precip/CloudCover views) — but it was **not** the cause of the dead widget.
2. **Live diagnostics reframed it.** `adb` showed the process dead with no crash in the JVM crash
   buffer, yet `app_logs` showed taps working recently → the process was dying and not reviving.
3. **Added durable native-death logging.** New `ProcessExitLogger` reads
   `ActivityManager.getHistoricalProcessExitReasons()` (`ApplicationExitInfo`, API 30+) and mirrors
   each prior process death into `app_logs` as `PROC_EXIT` rows — the only in-app source that sees
   native/LMK/ANR/force-stop deaths. This immediately surfaced repeated `CRASH_NATIVE status=11`.
4. **User challenged the battery theory** (device was plugged in) — correctly. Not battery; not
   memory. Native crash.
5. **Repro loop + breadcrumbs.** Added debug-only `SYNC_STAGE` breadcrumbs in `doWork` and a
   `DEBUG_FAST_FULL_REFRESH_SECONDS` self-rescheduling loop to reproduce fast. It caught 6 crashes
   in ~40 min.
6. **Root cause pinned.** Every crash — pre-loop and under-loop — was immediately preceded by
   `SYNC_START` + `SYNC_CANCELLED stopReason=1`. Tombstones (`dumpsys dropbox`) showed
   `kotlinx.coroutines.DispatchedTask.run → BaseContinuationImpl.resumeWith →
   art::interpreter::ExecuteSwitchImplCpp(WeatherWidgetWorker.doWork)` — **the ART interpreter
   segfaults resuming a cancelled coroutine continuation.** Trigger: `ExistingWorkPolicy.REPLACE`
   cancelling an in-flight worker when overlapping refreshes fire. Debuggable builds (no AOT → all
   interpreter) are hit hardest.
7. **Fix: never cancel a running worker.** Converted `REPLACE` → `KEEP` / `APPEND_OR_REPLACE` across
   all enqueue sites (kept `REPLACE` only for delayed/not-running work). Periodic already used
   `ExistingPeriodicWorkPolicy.UPDATE` (safe).
8. **Crash-loop left a poisoned WorkManager queue** (persisted work kept retrying). Cleared it
   surgically without `pm clear`: `am force-stop` + `rm no_backup/androidx.work.workdb{,-shm,-wal}`
   (weather_database untouched). Clean launch after the fix = process stays alive, syncs complete,
   0 cancellations, 0 crashes.
9. **Swept remaining REPLACE sites** (no production exists) and updated tests.
10. **Documented** the trap in `AGENTS.md` and refreshed stale version/module facts there.

---

## All prompts (verbatim, in order)

1. `Taps aren't working on samsung widget.  Thoughts?`
2. [AskUserQuestion answers] Tap scope: **All taps**; Onset: **Just started, device specific**; Device access: **Yes, connected**.
3. `fix all three handlers` (plan mode)
4. [ExitPlanMode rejected] `Yes, and use auto mode, consider adding logging to make this easier to diagnose in the future`
5. `Are crashes logged to the db log?`
6. [AskUserQuestion answer, re: how to tackle] `I have doubts about diagnoses.  Device was plugged in.  Why would samsung kill a process for battery savings when the device is plugged in.  Can we add logging to help diagnose this in the future?`
7. [AskUserQuestion answer, re: root-cause approach] `Option 2 and 3.  Also can we change when full update occurs?  Make it much quicker to make this easier to debug?`
8. `/model` (set model to Opus 4.8), then: `samsung widget dead`
9. `It is dead again` (interrupted the scheduled wakeup)
10. [AskUserQuestion answer, re: fix approach] `Why are we cancelling workers?  Can we try to have a clean exit?  Tell me more about REPLACE→KEEP`
11. `by the way: widget is still dead`
12. `There is no production.  sweep the remaining REPLACE sites`
13. `608 long tests: 1 failed.\n  ✗ WidgetIntentRouterRobolectricTest > buildRefreshScheduleDecision uses replace for manual refresh`
14. `Please say something about the issue in agents.md`
15. `Update anything that looks stale in agents.md`
16. `Write a session log to session-logs/ dir , include all prompts`

---

## Root cause (confirmed)

- **What:** `SIGSEGV` (`SEGV_ACCERR`, `status=11`) / occasionally `SIGABRT`, on the
  `DefaultDispatch` thread, inside the ART interpreter resuming `WeatherWidgetWorker.doWork`'s
  coroutine continuation.
- **Trigger:** WorkManager cancels a **running** worker. Easiest via `ExistingWorkPolicy.REPLACE`
  enqueuing a new request under a unique name whose worker is already executing. Overlapping
  refreshes (`_ui` repaints, `charging_loop`, forced/coverage) made this frequent.
- **Why invisible:** native crashes never invoke the JVM `UncaughtExceptionHandler`, so no `CRASH`
  row in `app_logs`. Confirmed JVM crashes *are* logged (`CrashReporter.CRASH_TAG` via
  `WeatherWidgetApp.buildCrashHandler`), but native ones are not.
- **Signature to look for:** `SYNC_CANCELLED stopReason=1` immediately before
  `PROC_EXIT reason=CRASH_NATIVE`; tombstone top frames all `art::interpreter::*` with no JNI lib
  frame.

---

## Changes

### Fix — stop cancelling running workers (REPLACE → KEEP / APPEND_OR_REPLACE)

1. `WeatherWidgetProvider.triggerUiOnlyUpdate` — `_ui`: REPLACE → **APPEND_OR_REPLACE**
   (`_ui_delayed` stays REPLACE — delayed, not running).
2. `WeatherWidgetProvider` `_current_temp` (stale-refresh path): REPLACE → **KEEP**.
3. `UIUpdateReceiver` — `_ui` (same worker name): REPLACE → **APPEND_OR_REPLACE**.
4. `OpportunisticUpdateJobService` — `_ui`: REPLACE → **APPEND_OR_REPLACE**.
5. `RefreshScheduler.enqueueForcedRefresh` default + `buildRefreshScheduleDecision` `manual_refresh`:
   REPLACE → **KEEP**.
6. `CurrentTempUpdateScheduler`: charging-loop `REPLACE_IMMEDIATE` → **APPEND_OR_REPLACE**
   (`REPLACE_DELAYED` stays REPLACE); `enqueueImmediateUpdate` → **APPEND_OR_REPLACE**.
7. `NonPrimaryObservationScheduler`: `REPLACE_IMMEDIATE` → **APPEND_OR_REPLACE** (`REPLACE_DELAYED`
   stays).
8. `SettingsActivity` "Refresh now" (both `WORK_NAME_ONE_TIME` + `WORK_NAME_CURRENT_TEMP`): REPLACE →
   **APPEND_OR_REPLACE**.
9. `WeatherWidgetWorker` debug fast-refresh enqueue: REPLACE → **KEEP**.
10. Periodic `WORK_NAME` already `ExistingPeriodicWorkPolicy.UPDATE` — left as-is (safe).
11. Explicit `cancelUniqueWork(...)` (`*.cancel()` from `ScreenOnReceiver`, `onDisabled`) left as an
    accepted low-frequency residual (loop teardown at screen/power transitions).

### Diagnostics added (permanent value)

12. **`ProcessExitLogger.kt` (new)** — logs `ApplicationExitInfo` history to `app_logs` as
    `PROC_EXIT` rows (reason/importance/status/pss/rss/desc; ERROR for crashes, WARN for
    LMK/ANR/force-stop, INFO benign), de-duped across process lifetimes via a SharedPreferences
    timestamp cursor. Called once per process from `WeatherWidgetWorker.doWork`. Pure formatting
    extracted to a primitive overload for plain-JUnit (`ProcessExitLoggerTest.kt`, ShortDuration).
13. **`SYNC_STAGE` breadcrumbs** in `doWork` (debug-only): `weather_fetched` → `hourly_fetched` →
    `backfill_done` → `actuals_recompute_start`, complementing existing `SYNC_SUCCESS` /
    `worker_paint_start|done`.
14. **`DEBUG_FAST_FULL_REFRESH_SECONDS`** repro knob (0 = off) — self-reschedules a forced full
    refresh; scheduled at the *start* of `doWork` so the loop survives a crash.

### Sticky-banner fix (separate latent bug, kept)

15. `DailyViewHandler.bindTransientMessage` gained a `callerTag` + `Log.d`; now called by
    `TemperatureViewHandler`, `PrecipViewHandler`, `CloudCoverViewHandler` so the missing-hourly
    banner renders/clears in those views. Test call site updated
    (`DailyFutureDayNoHourlyClickIntegrationTest`).

### Tests

16. `UIUpdateReceiverTest`, `CurrentTempUpdateSchedulerTest`, `WidgetIntentRouterRobolectricTest`
    updated to expect the new non-cancelling policies. Full `testDebugUnitTest` green.
    Flake note: running many scheduler test classes together throws
    `FileSystemAlreadyExistsException` (shared jimfs) — run classes in isolation.

### Docs

17. `AGENTS.md`: added **"NEVER cancel a running WeatherWidgetWorker (native-crash trap)"** section;
    refreshed stale facts (Kotlin 2.3.10, SDK 35, Hilt 2.59.2, Room 2.7.0, serialization 1.7.3, AGP
    9.1.0; API clients + `WeatherSource` now in `:shared`; mocking-framework note corrected — mockk
    is used).

---

## Verification

1. Cleared the poisoned WorkManager queue: `am force-stop com.weatherwidget` +
   `run-as com.weatherwidget rm -f no_backup/androidx.work.workdb{,-shm,-wal}` (NOT `pm clear`;
   `weather_database` preserved).
2. Clean launch of the fully-swept build: process stays alive, repeated `SYNC_SUCCESS` +
   `worker_paint_done`, **0 `SYNC_CANCELLED`, 0 "was cancelled", 0 SIGSEGV/SIGABRT**. Fetch Logs show
   `charging_loop decision=keep`. App fetching live station data.
3. `./gradlew testDebugUnitTest` — all green.

---

## Follow-ups / residuals

1. Explicit `cancelUniqueWork` teardown paths can still cancel a running worker at screen/power
   transitions — low frequency, accepted; `PROC_EXIT` will flag if it recurs.
2. Release/AOT build is almost certainly immune (all-interpreter crash stack); user confirmed
   "there is no production," so not pursued.
3. Uncommitted — needs a commit covering code + tests + `AGENTS.md` + this log.
