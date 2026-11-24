# Targeted forecast syncs: paint, then one debounced actuals tail (Android + desktop)

Plan: [`plans/261123-shared-targeted-sync-paint-then-tail.md`](../plans/261123-shared-targeted-sync-paint-then-tail.md)

## Prompts

1. "What do you think of: plans/261123-fast-targeted-forecast-sync-release-worker.md ?"
2. "create new plan with your suggestions"
3. "Does the plan maximize shared code? Android and desktop should work the same"
4. "proceed"

## What happened

1. Reviewed the drop-the-tail plan: it would have skipped `DailyHistoryRun.afterFetch` (the rain-chance
   freeze) and the only reliable METAR turn, and it matched on reason strings that do not exist on Android.
2. A first revision kept everything in `:app`; the user asked for maximum sharing, so the rule moved to
   `:shared` `TargetedForecastSyncRun`, with `AndroidTargetedSyncHost` and `DesktopTargetedSyncHost`.
3. While implementing, replaced the planned `REPLACE` debounce with keep-and-settle so no platform ever
   replaces or cancels work (Rule 6).

## What changed

1. `:shared`: `TargetedForecastSyncRun` (fast path, debounce, yield, repaint gate, logs);
   `BackfillDeferralPolicy.userSyncActive()`; `TargetedForecastSyncHostContract` test fixture.
2. `:app`: `FullSyncPipeline.onTargetedFetchSuccess` replaces the inline post-paint tail;
   `weather_widget_post_sync_actuals` work (`WidgetWorkScheduler.enqueuePostSyncActuals`), worker dispatch
   (`KEY_POST_SYNC_ACTUALS`), `AndroidTargetedSyncHost`.
3. `:desktop`: `refreshWithOutcome(deferActuals)`, `dailyHistoryAfterFetch`, `recomputeActualsTail`,
   `actualsSignature`; `DesktopPostSyncTail` + `DesktopTargetedSyncHost`; `DaemonRuntime.refreshTargeted`
   for viewing/single-source requests, the source-switch launch refresh, and source retries.
4. Docs: `arch/forecast-and-observation-cadence.md#targeted-forecast-syncs` (canonical),
   `arch/ARCHITECTURE.md`, `arch/operations.md`.
5. Tests: `TargetedForecastSyncRunTest` (12), `TargetedSyncPostActualsIntegrationTest` (5),
   `DesktopTargetedSyncIntegrationTest` (3), contract suites on both platforms (5 + 5).

## Verification

See the plan's "Verification Results": the gate passed; Pixel and desktop logs show paint → tail request
with no observation work in between, and one tail per burst.

## Open items

1. With the screen off, Android held the 12 s tail for minutes (JobScheduler batching); actuals for a
   toggle made with the screen off now land when the device next runs jobs.
2. The tail's observation feeds still differ by platform (Android METAR/Synoptic refreshers vs desktop
   `refreshObservations`).
