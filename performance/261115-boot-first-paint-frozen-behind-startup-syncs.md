# Boot first paint took 102 s: startup syncs ran beside the paint, then the process was frozen

Status: plan approved 2026-11-15. Phases 1 and 2 implemented; the Pixel reboot check with stale data is pending for both. Phase 3 not started.

## Report

"Widget very slow on first draw on Pixel 7. Widget startup very slow after device startup."

Device: Pixel 7 Pro `2A191FDH300PPW`, debug build 26111201, booted 2026-11-15 11:19:39 with the
battery at 2 % (charging) and data 10.7 h old. Evidence: logcat saved 5 minutes after boot and
`backups/20261115_112533_pixel_7_pro_2A191FDH300PPW/databases/weather_database` (`app_logs`).

## What happened

| Time | Event | Source |
|---|---|---|
| 11:22:59.4 | `Start proc 6870:com.weatherwidget` for the widget provider broadcast | logcat |
| +2.3 s | first trigger delivered (`firstTriggerAgeMs=2276`) | `COLD_START_PERF` |
| 11:23:05.5 | `onUpdate_entry`; cache-first startup load begins | `WIDGET_LIFECYCLE` |
| 11:23:10.4 | deferred startup sync runs: 72 h observation backfill | `SYNC_START obsBackfillOnly=true` |
| 11:23:11.1 | 8 s watchdog releases the `onUpdate` broadcast | `CLICK_WATCHDOG` |
| 11:23:13.2 | resize-triggered current-temp sync and forced sync start; the forced sync fetches all 7 sources | `SYNC_START` ×2, `FETCH_PLAN` |
| 11:23:19 – 35 | three more resize broadcasts, each released by the watchdog at 8 s | `CLICK_WATCHDOG` ×3 |
| 11:23:50.4 | **`ActivityManager: freezing 6870 com.weatherwidget`** | logcat |
| 11:24:39.1 | **`sync unfroze 6870 com.weatherwidget for 6`**, as JobScheduler starts the app's jobs | logcat |
| 11:24:41.9 | startup load returns: `extremesMs=91260` | `WIDGET_STARTUP_PERF` |
| 11:24:43.0 | first paint, `firstPaintAgeMs=102276` | `COLD_START_PERF` |

The 100 s between trigger and paint splits into three parts:

1. **About 45 s of contention (11:23:05 – 11:23:50).** The startup load's last step, the daily
   actuals read, began at about 11:23:11, the same second three syncs started. That step takes
   0.4–0.7 s on a warm process (eight `WIDGET_STARTUP_PERF` rows earlier the same day). It had
   not finished 39 s later. The syncs were slowed the same way: one daily recompute logged
   `query=16162ms hourly=49704ms` for reads that normally take about 350 ms and 130 ms.
2. **48.8 s frozen (11:23:50 – 11:24:39).** No row was written by anything in the process. The
   last broadcast had been released at 11:23:35, so the process was cached, and the cached-app
   freezer suspended it. The syncs were running through WorkManager's in-process scheduler; logcat
   shows JobScheduler had not started them (`Sending onNetworkChanged for a job that isn't
   started`), so they gave the process no protection.
3. **3.9 s to finish** the read and render three widgets after the unfreeze.

## Root cause

`StartupCooldown` defers background sync work for a fixed 6 s from process start (extended 6 s
past the last user-facing trigger). It is a clock, not an ordering rule. On this boot the first
paint needed far longer than 6 s, so the cooldown lapsed with the paint still loading, and the
deferred syncs started beside it (`SYNC_DEFERRED_STARTUP processAgeMs=4313 delayMs=4172`).
That contention kept the paint unfinished past the point where the last broadcast was released,
which left an unpainted process eligible for freezing.

## Platform limit

A background app cannot reliably stop the freezer. A held broadcast is limited by the ANR
deadline (the reason for the 8 s watchdog), JobScheduler decides when a job starts, and a
foreground service cannot be started from the background. The dependable fix is to have the
first paint done while a broadcast is still held, not to hold the process awake longer.

## Proposed fix, in three phases

Scope agreed with the user 2026-11-15: paint-first ordering, a low-priority sync pool, and a
split of display-critical work from housekeeping. The phase order, thread count, cap and the
work classification below are Claude proposals. Each phase is built, tested and measured on the
Pixel before the next starts.

### Phase 1: background sync waits for the startup paint (Android)

Make the cooldown an ordering rule: **no background sync starts until the startup paint has
landed.**

1. `StartupCooldown` gains a "startup paint pending" state, set when `WidgetStartupCoordinator`
   claims the startup paints and cleared when they are pushed or fail (`WIDGET_STARTUP_BATCH`).
   While pending, `remainingMs` keeps deferring non-UI-only runs.
2. A cap (proposed 30 s from the claim) so a paint that never completes cannot hold syncs
   forever. The existing 6 s values are unchanged.
3. `SYNC_DEFERRED_STARTUP` logs `cause=paint_pending|cooldown`.
4. Android only: desktop has no startup cooldown or process freezer.

Tests (must fail on the current code):

- `StartupCooldownTest`: a pending paint defers past the 6 s clock; clears on paint done; the cap
  releases it; no effect before a user-facing trigger (the August regression guard stays).
- Worker deferral test: a non-UI-only run during a pending paint is deferred with
  `cause=paint_pending`; a UI-only run is not.

Device check: reboot the Pixel with stale data; every `SYNC_START` must follow
`WIDGET_STARTUP_BATCH`; record `COLD_START_PERF`.

#### Phase 1 result (2026-11-15)

Implemented in `StartupCooldown` (paint hold, 30 s cap, 3 s recheck), `WidgetStartupCoordinator`
(hold from claim to settle, released in `finally`) and `WeatherWidgetWorker`
(`SYNC_DEFERRED_STARTUP … cause=`). JVM suite: 7,049 passed.

- Emulator (`Medium_Phone_API_36`), reinstall with a forced sync sent at once: two runs deferred
  with `cause=paint_pending` at process age 0.9 s, first paint at 1.0 s, `WIDGET_STARTUP_BATCH`,
  then `SYNC_START force=true reason=debug_full_sync` 11 s later. The paint was short enough that
  the 6 s clock set the delay; a hold lasting past the clock is covered by unit tests only.
- Pixel 7 Pro, reinstall: first paint 13.8 s, no freeze. The data was 23 minutes old, so no sync
  was requested and the hold was not exercised.
- **Not yet done:** the reboot with stale data, which is the case this phase exists for.

Finding from the Pixel reinstall: `extremesMs=8203` with no sync running (0.4–0.7 s warm). The
daily-actuals step is the largest part of a cold paint on its own. This belongs with the
cold-start investigation named under "What this will and will not fix".

### Phase 2: low-priority sync pool (Android)

Sync, paints and tap handling share `Dispatchers.Default`/`IO` today and nothing sets a thread
priority. Give sync its own pool so paints and taps win the CPU.

1. A dedicated executor (proposed 2 threads) whose threads run at
   `Process.THREAD_PRIORITY_BACKGROUND`, exposed as a coroutine dispatcher.
2. Every non-UI-only `WeatherWidgetWorker` run (full sync, current-temp sync, observation
   backfill) runs on it. UI-only repaints, the startup paint and tap handlers stay where they are.
3. The repaint at the end of a sync hops back to the normal dispatcher, so a finished sync's paint
   is not itself slowed.
4. `SYNC_STAGE` already logs the thread name, which shows which pool ran each stage.

Known limits:

- It addresses CPU contention only. Room's shared query threads and single writer are untouched,
  so a tap's read can still queue behind a sync's.
- Sync itself gets slower. Before and after `SYNC_PERF` totals are part of the check.
- Android only. A JVM thread priority has little effect on Linux, so desktop is out of scope.

Tests: a dispatcher test (thread count, priority); a worker test that a non-UI-only run executes
on the sync pool and a UI-only run does not.

Device check: tap a widget during a forced sync (`debug.FULL_SYNC` on the emulator first);
compare `INTERACTION_E2E` and tap-to-`onReceive` latency with and without the pool, about three
runs each.

#### Phase 2 result (2026-11-15)

Started at the user's request before the phase 1 reboot check. Implemented as `SyncDispatcher`
(2 threads, `THREAD_PRIORITY_BACKGROUND`, named `sync-pool-N`); `WeatherWidgetWorker` runs every
non-UI-only branch on it; `WidgetPaintCoordinator.updateAllWidgets` and `refreshWidgetsFromCache`
hop back to `Dispatchers.Default`. JVM suite: 7,057 passed.

Pixel 7 Pro, warm process, screen on, one forced sync of all 7 sources with seven source-button
taps 3 s apart (`debug.FULL_SYNC`, `debug.TOGGLE_API`), `INTERACTION_E2E e2e`:

| Build | Sync total | Recompute | Taps during the sync (ms) | Taps after it (ms) |
|---|---|---|---|---|
| No pool | 17.9 s | 5.5 s | 1203, 2225, 848, 987 | 1143, 1154, 1106 |
| Pool | 15.8 s | 0.8 s | 606, 1993, 1048, 935 | 1025, 1088, 1325 |

- The pool works as built: `SYNC_STAGE … thread=sync-pool-1/2` on every stage of the second run.
- **No measurable change in tap latency.** On a warm process a tap costs about 1 s with or without
  a sync beside it, and each run had one tap near 2 s. One run per build is too few to separate
  them, and the two syncs were not equal work: the second came 99 s after the first, so its
  recompute was mostly skipped.
- The emulator cannot show this at all: its daily recompute takes about 0.5 s against the Pixel's
  12-18 s.
- What the pool is for, a cold process or a multi-day recompute beside a paint, was not measured.
  The reboot check is the first chance to see it.

### Phase 3: display-critical work now, housekeeping later (Android and desktop)

One sync pass does everything today. On this boot a resize-triggered sync that targeted NWS
fetched all seven sources because all were due, and a 72 h observation backfill ran beside it.

Proposed classification, defined once in `:shared` with a `Host` per platform:

| Class | Work | When |
|---|---|---|
| Display-critical | Forecast for sources a widget displays; current temperature; today's actuals; observation gap repair for a widget showing a graph | Screen-on, after the paint |
| Housekeeping | Due forecasts for sources no widget displays; past-day recomputes; deep observation backfill; daily-history upkeep | Device not in use |

Rules:

1. Housekeeping runs as a real JobScheduler job so the process is not cached while it works.
   Both incidents this weekend (the freeze and the excessive-CPU kill) hit a cached process.
2. **Bounded wait.** Housekeeping that has waited too long runs anyway, on the low-priority pool.
   Without this, a phone that stays off the charger never fetches non-displayed sources and
   forecast history and accuracy get gaps. This is the lesson of
   `plans/261101-reliable-hourly-prune-scheduling.md`.
3. A source that becomes displayed (source button, daily home button) is display-critical from
   that tap, as the tapped-source refresh already treats it.
4. Settings → Refresh data still refreshes every source immediately.

Open decisions for the user before phase 3 starts:

- What "not in use" means on Android: screen off, or charging and idle like the prune.
- The overdue bound (Claude proposal: 2 h past the source's normal due time).

Audit before implementation: desktop's refresh loop has not been read for this plan. Phase 3
starts with that audit, so the shared rule fits both hosts rather than Android alone.

Tests: shared classification tests; Android and desktop host tests; a pipeline test that a
resize-triggered sync with seven due sources fetches only the displayed ones and queues the rest.

### Not planned

Cooperative yielding (a running sync pausing while the user taps). The user is unsure about it;
revisit only if taps are still slow after phases 1 to 3.

## What this will and will not fix

- Phase 1 removes the contention, and with it the reason the paint was still unfinished when the
  process became freezable. Expected boot first paint: in the range of this phone's other cold
  starts rather than 102 s.
- No phase makes a cold start fast. 13 of the last 15 cold starts on this phone took 10–18 s to
  first paint with no boot and no freeze (`COLD_START_PERF`). Before any sync started on this
  boot, the startup reads were already slow (`hourlyMs=4977 currentTempMs=6743`). The phone runs
  a debuggable build, which ART will not AOT-compile. That is a separate investigation.
- Nothing here is verified until each phase's device check is run.

## Related finding, not in this fix

`PROC_EXIT reason=EXCESSIVE_RESOURCE_USAGE … excessive cpu` at 2026-11-14 22:01:39: Android
killed the app for background CPU use, which caused last night's cold start (`COLD_START_PERF
firstPaintAgeMs=305508 path=full`). `DAILY_RECOMPUTE_PERF` shows the daily blend's `extremes`
step costing 12–18 s of CPU per day on a warm process, four days per sync. Worth its own
investigation.
