# Code review follow-up — M4 (per-source cooldown) and M5 (FullSyncPipeline extraction)

**Date:** 2026-08-12 · **Plan:** [plans/260812-code-review-followup-m4-m5.md](../plans/260812-code-review-followup-m4-m5.md)
**Target:** M4, M5 from `plans/260812-code-review-refresh-coordination.md`.

The final two review findings. M4 was a correctness/battery tradeoff; M5 was a maintainability
structural item. Both done as behaviour-preserving changes.

---

## What shipped

| # | Finding | Change |
|---|---|---|
| 1 | M4 — whole-worker cooldown on a single global timestamp | Removed the `doWork` `lastFullFetchAgeSeconds in 0..300` gate |
| 2 | M5 — `WeatherWidgetWorker` was the largest, most branchy unit (892 LOC) | Extracted `FullSyncPipeline`, `WidgetPaintCoordinator`, `WidgetLoopScheduler`; worker is now a thin dispatcher |

**Verified:** `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:testShortDebugUnitTest`, plus the
four worker/scheduler test classes (`WeatherWidgetProviderEnqueuePolicyTest`,
`WeatherWidgetProviderNoHourlyRoboTest`, `WidgetWorkSchedulerCollisionTest`,
`CurrentTempUpdateSchedulerTest`) — all green. Changes are **uncommitted**.

---

## M4 — the cooldown was redundant with a per-source check already in place

The worker skipped any non-forced full sync whose global `lastFullFetchAgeSeconds` fell in `0..300`.
The age came from a single `FetchMetadata.getLastFullFetchTime` timestamp, so fetching one source
suppressed a genuinely stale *different* source for up to 5 minutes.

Per-source freshness already exists one layer down: `ForecastRepository.getWeatherData` calls
`ForecastFetchCoordinator.requiresNetworkFetch` (per-source `isStale` against the same
`fetchContext`) before touching the network. So the worker's global pre-empt was redundant and
coarser. **Fix:** delete the `SYNC_SKIP` block and let the per-source decision in the coordinator be
the gate.

One related observation, left unchanged: `ForecastRepository` also has a `MIN_NETWORK_INTERVAL_MS`
(10-minute) full-fetch backstop inside its sync mutex. It is a deliberate rate-limit that periodic
syncs of 60–480 min never reach; touching it is out of M4's scope but worth knowing about if the
"defer a stale source" symptom ever recurs.

## M5 — the worker is now a thin dispatcher

Three pure code-motion extractions, no behaviour change:

| File | Lines | Owns |
|---|---|---|
| `FullSyncPipeline` | 333 | `run(input, device, stopReason)` — the former `handleFullSyncWork` body (GPS resample → location resolution/promotion → fetch → backfill → actuals → render → schedule), plus its own `logStage`/`SYNC_PERF` timing and `broadcastNoHourlyRefreshComplete`/`maybeScheduleDebugFastRefresh` |
| `WidgetPaintCoordinator` | 191 | `lastRenderMs`, `updateAllWidgets`, `resolveEffectiveActuals`, `shouldSkipWidgetRender`, `renderNoLocationAndFinish`, `refreshWidgetsFromCache` |
| `WidgetLoopScheduler` | 60 | `manageCurrentTempLoopAfterRun`, `manageNonPrimaryLoopAfterRun` |

`WeatherWidgetWorker` shrank from **892 → 403 LOC** and keeps `doWork` dispatch, the legacy-location
migration step, the observation-backfill / current-temp / non-primary handlers, `measureDeviceContext`,
`isScreenInteractive`, `getLocationName`, and the input-key constants. It delegates full sync via
`fullSyncPipeline.run(input, device, stopReason)` and shares the painter/loop-scheduler with its
lightweight modes.

The only subtlety was a `Result` type resolution: `WeatherWidgetWorker` could use bare `Result`
because `ListenableWorker.Result` is inherited through `CoroutineWorker`; the standalone
`FullSyncPipeline` had to qualify it as `ListenableWorker.Result` (bare `Result` resolves to
`kotlin.Result<out T>` in a non-worker class). Also fixed one stale KDoc reference in
`ScreenOnReceiver` that pointed at the now-moved `handleFullSyncWork`.

---

## Test changes

None required — the refactor is pure code motion and the worker had no dedicated harness. The
`KEY_*` constants and `WORK_NAME_LOCATION_CANDIDATE` stayed on `WeatherWidgetWorker` so the existing
enqueue-policy and no-hourly tests continued to compile and pass unchanged.

---

## Still open (deliberately deferred)

M5's suggestion to add a dedicated *timing type* (rather than the inline `SystemClock.elapsedRealtime()`
checkpoints that moved into `FullSyncPipeline` verbatim) was intentionally not done — the timing logic
now lives in one place, which was the core ask. A `SyncStopwatch` helper would be a cosmetic
follow-up. `ForecastRepository.MIN_NETWORK_INTERVAL_MS` (see M4) is the remaining global throttle.
