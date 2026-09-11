# Post-install cold start: no background work until a cooldown

**Date:** 2026-09-10
**Status:** implemented and verified on the emulator and the Pixel 7 Pro
**Scope:** Android worker/startup ordering; `:app`

## Symptom

Pixel 7 Pro (3 widgets, debuggable build, Android 17): after every `installDebug` the widgets take
~16 s to first paint and taps stay slow for over a minute.

## What the device recorded (18:03:50 install, `app_logs`)

| t | event | cost |
|---|---|---|
| 18:03:35 | old process: source toggle enqueues a forced full sync (`toggle_api_stale`, 38 s after a full fetch) | running when killed |
| 18:03:50 | install kills the process mid-recompute (`PROC_EXIT reason=PACKAGE_UPDATED`) | |
| 18:03:52 | `PackageReplacedReceiver` → `renderAllWidgetsFromCache` 78/79/88 | 14.9 s / 8.1 s / 12.5 s |
| 18:03:52 | opportunistic job: `fetch_enqueued processAgeMs=710 startupGrace=true` | |
| 18:04:00 | `onUpdate` startup batch, same 3 widgets | 9.3 s (`extremesMs=5153`) |
| 18:04:05 | WorkManager re-runs the killed forced sync, `lastFullFetch=26s ago` | **49.8 s** = Open-Meteo 9.2 s + synoptic 19.5 s + recompute 14.9 s + paints 2.8 s |
| 18:04:07 | first paint (`COLD_START_PERF firstPaintAgeMs=16311`) | |
| 18:04:16–56 | 6 user taps delivered exactly 8.03 s apart (`set-view-3307873847, …881875, …889900, …897935`) — the broadcast queue releases one per `BroadcastAsyncRunner.WATCHDOG_MS`; `receiveToComplete` 20.6 / 19.5 / 18.1 / 16.2 s | |
| 18:05:03 | first tap after the sync ends: `total=985ms receiveToComplete=1390ms` | normal |

Widget 88 was painted 17 times in 60 s. `TEMP_ACTUALS_PERF buildMs` 256 (old, warm process) →
4146 (new process): the debuggable APK runs interpreted with an empty JIT profile after every
reinstall, and everything above ran on it concurrently.

## Why the earlier 4-item list was insufficient

Persisting the signature cache, not re-running a killed forced sync as forced, one startup paint,
and deduping recomputes each remove *work*; none imposes *order*. The user's principle is the fix
for the concurrency: paint from cache, then no background queries until a cooldown.
`WidgetStartupCoordinator.checkStalenessAndFetch` already does exactly that for the `onUpdate`
trigger (`StartupFetchPolicy.primaryFetchDelayMs`) — but it is one of five enqueue sites, and the
WorkManager retry, the opportunistic job, the toggle-forced request and the package-replaced paint
all bypass it.

## The trap the gate must avoid

`opportunistic_job_startup_grace_self_defeating` (2026-08-19): a "process age < 15 s → skip" guard
killed the on-battery refresh because JobScheduler cold-starts the process *to run the job*, so
process age is ~100 ms on precisely those runs. The cooldown must key on **why the process
started**, not on age alone.

## Plan

### 0. `StartupCooldownPolicy` at the worker chokepoint

Pure policy (unit-testable, no Android):

```
decide(processAgeMs, firstTriggerKind, lastInteractionAgeMs, uiOnly) -> Run | Defer(delayMs)
```

- `uiOnly` → Run (that is the cache paint).
- `firstTriggerKind` ∈ {WORKER, JOB} → Run (nobody is watching; nothing else is running).
- `firstTriggerKind` ∈ {PROVIDER_UPDATE, ACTION, PACKAGE_REPLACED, ACTIVITY} and
  `processAgeMs < COOLDOWN_MS` → Defer(COOLDOWN_MS − processAgeMs).
- past the cooldown but `lastInteractionAgeMs < INTERACTION_QUIET_MS` → Defer(remaining quiet).
- Constants: `COOLDOWN_MS = 30_000`, `INTERACTION_QUIET_MS = 10_000` (user chose 30 s over 45/60:
  the fixed part only has to cover the cache paints and first JIT warm-up; the quiet rule covers
  the user's tapping; a longer fixed window only delays an explicit refresh).

Wiring:

- `WeatherWidgetWorker.doWork`, right after `SYNC_START` is composed: on Defer, re-enqueue the same
  `WorkInput` under `WORK_NAME_STARTUP_DELAYED` (KEEP) with `initialDelayMs = delay`, log
  `SYNC_DEFERRED_STARTUP reason=<input reason> trigger=<kind> ageMs=… delayMs=…`, return
  `Result.success()`.
- When a deferred run executes, `force` is re-evaluated against freshness: a forced request only
  stays forced if its target source is still stale. This absorbs the "killed forced sync re-runs in
  full" defect.
- `WeatherWidgetApp.logFirstTriggerOnce(via)` already records the first trigger; expose its kind.
  `WidgetActionReceiver.onReceive` stamps `lastInteractionElapsedMs`.
- The opportunistic job keeps enqueuing (per the 08-19 fix); the worker gate is what defers it.

### 1. One startup paint

`PackageReplacedReceiver.renderAllWidgetsFromCache` and the `onUpdate` startup batch both paint
every widget within 8 s of each other. The receiver yields when a startup batch token is in flight
(or the batch skips widgets the receiver already painted) — one cache paint per widget.

### 2. Persist the settled-day signature

`DailyActualsStore.reducedSignatures` is in-memory by design ("one redundant recompute on process
death"); measured, that one recompute was 12 s of cold CPU. Persist `date|lat|lon → signature` in a
small prefs file (bounded like the map). Independent of ordering; it also keeps the deferred sync
cheap when it finally runs.

### Not doing (now)

- Shortening the 8 s watchdog: this Pixel froze the process 12 s after its last broadcast finished;
  an early release can strand a paint. Once 0–2 land a cold tap should complete well under 8 s.
- Deduping the 5× same-day recompute at 18:05:05: those callers stop overlapping once the sync is
  deferred; re-measure first.
- Debuggable build: `installRelease` on the Pixel would remove most of the wall-clock on its own —
  outside code.

## Tests

| # | Kind | Asserts |
|---|------|---------|
| 1 | Unit, `StartupCooldownPolicyTest` | table: trigger kind × age × interaction quiet × uiOnly → Run/Defer with the exact delay; WORKER/JOB trigger never defers (guards the trap). |
| 2 | Unit, worker | in cooldown → re-enqueued under `WORK_NAME_STARTUP_DELAYED` with the remaining delay, `SYNC_DEFERRED_STARTUP` logged, no `SYNC_START`; deferred forced run with fresh target → runs unforced. |
| 3 | Unit, receiver | package-replaced with a startup batch in flight → zero paints from the receiver; without one → paints all. |
| 4 | Unit, `DailyActualsStore` | new store instance, unchanged signature → `DAILY_RECOMPUTE_SKIP`; changed → recompute; `force=true` always recomputes. |
| 5 | Device | reinstall on the Pixel, tap during the first 30 s: no `SYNC_START` (non-uiOnly) before 30 s; one `WIDGET_PAINT` per widget before the first tap; `SET_VIEW_E2E_TIMING` < ~3 s cold. |

## Implementation notes

- `StartupCooldown` (new, `widget/`): stateful but clock-injected. Ends at `processStart + 30 s`;
  each user-facing trigger *inside* the window pushes the end to at least `now + 10 s`; a trigger
  after it lapsed does nothing. `remainingMs` is 0 until a user-facing trigger has been seen, which
  is the trap guard. Hooked at `WeatherWidgetApp.logFirstTriggerOnce` — every user-facing entry
  (onUpdate, action receiver, package replaced, locale change) already calls it on every receive;
  no background entry does.
- `WeatherWidgetWorker.doWork`: `deferForStartupCooldown` before `SYNC_START`; UI-only runs pass.
  Deferred runs go to `WidgetWorkScheduler.enqueueStartupDeferred` — one lane
  (`weather_widget_startup_deferred`, APPEND_OR_REPLACE) so they replay serially, coalesced by a
  signature tag over the fields that change what the worker does (excluding the RUNNING deferrer
  itself). Logged `SYNC_DEFERRED_STARTUP … outcome=enqueued|coalesced`.
- `dropSatisfiedForce`: `enqueueFullSync` now stamps `KEY_REQUESTED_AT_MS`; a forced run whose
  target (per-source success time, or the global full-fetch time) succeeded after that stamp runs
  unforced, logged `FORCED_REFRESH_SATISFIED`. Applies to every forced run, not only deferred ones:
  it is what makes the killed-and-retried case cheap even when it is *not* in the cooldown.
- `StartupPaintClaims` (new): first startup trigger to claim a widget paints it; a second claim
  within 30 s is refused and logged `STARTUP_PAINT_SKIP`. Applied in `WidgetStartupCoordinator`
  before `loadStartupData` (the staleness check and periodic schedule still run for claimed
  widgets), with `claimVia` naming the caller.
- `PackageReplacedReceiver` now paints **through `WidgetStartupCoordinator.updateWidgets`**
  (one shared load, startup fast path) instead of `renderAllWidgetsFromCache`. The first Pixel run
  showed why: the interaction path painted 78/79/88 in 15.9 s / 9.7 s / 7.9 s *sequentially*
  (`dailyDataMs` 11198 / 6042 / 5478 cold, each widget rebuilding daily data — the 2 s
  `WidgetInteractionCache` TTL is shorter than one cold paint), so the third widget carried no
  PendingIntents until +36 s and every tap before that hit the launcher's placeholder. The batch
  painted all three by +15.6 s in the second run.
- `ReducedSignatureStore` (new) with `InMemoryReducedSignatureStore` (default; tests, legacy
  construction) and `PersistedReducedSignatureStore` (Hilt-bound; prefs file
  `daily_recompute_signatures`, memory-fronted). `DailyActualsStore` takes it as a constructor
  parameter.
- Not covered by a unit test: the `dropSatisfiedForce` path end-to-end (it proceeds into the full
  pipeline, which needs the real repository); the predicate is tested and the device check covers
  the rest.
- Known limitation: Activities do not call `logFirstTriggerOnce`, so a process started by opening
  Settings gets no cooldown. Not the reported problem; noted for later.

## Verification

**Unit:** `:app:testDebugUnitTest` 2174/0 before the rebind change and re-run after it (see
summary); `:app:ktlintCheck` clean. New: `StartupCooldownTest` (6), `StartupPaintClaimsTest` (3),
`ForcedRefreshSatisfactionTest` (3), `WeatherWidgetWorkerStartupCooldownTest` (4: deferred with
input intact + `SYNC_DEFERRED_STARTUP`, identical run coalesced, different run appended, UI-only
never deferred), `DailyActualsStoreSignaturePersistenceTest` (3: second instance skips, in-memory
control recomputes, new observations and `force` still recompute).

**Emulator** (18:41:31 reinstall, taps every 2 s from +2 s): package-replaced paint 963/732 ms,
`onUpdate` 1.7 s later logged `STARTUP_PAINT_SKIP … holder=package_replaced` for both widgets; ten
taps 225–1528 ms; no watchdog. No sync was due, so no deferral exercised there.

**Pixel 7 Pro, run 1** (18:53:05 reinstall, source toggled 7 s before so a forced sync was
running when the install killed it):
- `SYNC_DEFERRED_STARTUP reason=toggle_api_stale force=true processAgeMs=20808 delayMs=9210`;
  at +31 s `FORCED_REFRESH_SATISFIED … satisfiedAt > requestedAt` → `SYNC_START force=false`.
- `STARTUP_PAINT_SKIP` for 78/79/88 at +9.5 s (`onUpdate` yielded).
- But the package-replaced interaction paints took 15.9 / 9.7 / 7.9 s sequentially; widget 88 had
  no PendingIntents until +36 s and none of the taps registered. → rebind moved to the batch.
- Recompute of 09-08/09-09 still ran (8.4 s each): first process with the persisted store, nothing
  persisted yet.

**Pixel 7 Pro, run 2** (18:57:19 reinstall, same setup, taps from first paint):
- Batch first paint at **+13.8 s**, all three by +15.6 s (`WIDGET_STARTUP_PERF token=package-replaced
  … currentTempMs=2936 extremesMs=10121 totalMs=14657`).
- Ten `DAILY_NAV_TIMING` taps at +16…+35 s: **1007–1219 ms each**, delivered every 2 s. No
  `CLICK_WATCHDOG`.
- Deferrals: +19.3 s (delay 10.7 s → 30 s), +30.1 s (delay 8.3 s, tap-extended), +38.4 s (delay
  4.2 s); the sync ran at **+45 s, 10 s after the last tap** — the quiet rule.
  `FORCED_REFRESH_SATISFIED target=SILURIAN` → unforced.
- `DAILY_RECOMPUTE_SKIP` for 09-08 and 09-09 (persisted signatures): `recompute=357ms` vs
  14.9–18.8 s in every earlier cold run.
- Pixel's `onUpdate` arrived at +80 s (launcher's own schedule), past the 30 s claim window, and
  painted cheaply (`extremesMs=158`).

**Left on the table (out of scope, measured):** the cold batch paint is dominated by
`extremesMs=10121` (the daily-actuals build on the interpreter) and the deferred sync itself still
costs ~40 s (`synoptic=20752ms`, `hourly=16216ms`) even with fresh data — both now run after the
user, not alongside them. And the Pixel's APK is still `DEBUGGABLE`: `installRelease` would cut
every cold number here several-fold.
