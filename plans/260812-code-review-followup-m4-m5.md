# Code Review follow-up — M4 (per-source cooldown) and M5 (FullSyncPipeline extraction)

**Source:** `plans/260812-code-review-refresh-coordination.md` (refresh/update-coordination subsystem review).

The remaining "watch" findings after M1/M2/M3 (see `plans/260812-code-review-followup-m1-m2-m3.md`).

## M4 — remove the whole-worker global cooldown

`WeatherWidgetWorker.doWork` skipped any non-forced full sync whose global
`lastFullFetchAgeSeconds` fell in `0..300`. The age comes from a single global
`FetchMetadata.getLastFullFetchTime` timestamp, so a fetch of one source suppressed a genuinely
stale *different* source for up to 5 minutes.

Per-source freshness already exists one layer down — `ForecastRepository.getWeatherData` calls
`ForecastFetchCoordinator.requiresNetworkFetch` (per-source `isStale`) before touching the network,
and it is gated by the same `fetchContext` the worker builds. So the correct gate is already there;
the worker's global pre-empt is redundant and coarser.

**Fix:** delete the `SYNC_SKIP` cooldown block. The per-source decision moves to
`ForecastFetchCoordinator`, which has the granularity the review points to. (The repository's own
`MIN_NETWORK_INTERVAL_MS` full-fetch backstop is left in place — it's a deliberate 10-minute
rate-limit that periodic syncs of 60–480 min never reach; documented as a related observation.)

## M5 — extract `FullSyncPipeline`

`WeatherWidgetWorker.handleFullSyncWork` is the largest single method in the subsystem (~250 lines)
and mixes GPS resample, location promotion, fetch, backfill, actuals recompute, repaint, and
scheduling. The review asks for a `FullSyncPipeline` (fetch → promote → backfill → actuals → render)
with its own timing/logging, leaving the worker a thin dispatcher.

Three extractions, each behaviour-preserving (pure code motion):

1. **`WidgetPaintCoordinator`** — owns painting + no-location + cache repaint:
   `lastRenderMs`, `updateAllWidgets`, `resolveEffectiveActuals`, `shouldSkipWidgetRender`,
   `renderNoLocationAndFinish`, `refreshWidgetsFromCache`, `MIN_RENDER_INTERVAL_MS`. Shared by the
   worker's current-temp/backfill modes and the pipeline.

2. **`WidgetLoopScheduler`** — owns the two post-run loop managers:
   `manageCurrentTempLoopAfterRun`, `manageNonPrimaryLoopAfterRun`. Shared the same way.

3. **`FullSyncPipeline`** — owns `run(input, device, stopReason)` (the former `handleFullSyncWork`
   body) plus its private `maybeScheduleDebugFastRefresh`, `broadcastNoHourlyRefreshComplete`, and
   `logStage`. It takes the two collaborators above and the loaders.

`WeatherWidgetWorker` keeps `doWork` dispatch, the legacy-migration step, the observation-backfill /
current-temp / non-primary handlers, `measureDeviceContext`, `isScreenInteractive`, `getLocationName`,
and the input-key constants — and delegates full sync to `FullSyncPipeline.run`.

## Verification

1. `./gradlew :app:assembleDebug` (compile the app + Hilt wiring)
2. `./gradlew :app:testShortDebugUnitTest` (unit tests; the touched code has no dedicated worker test,
   but the refactor is pure motion and must not break compilation or existing policy tests)
3. `./gradlew :desktop:compileKotlin` unaffected but confirms no cross-module break
