# Android actuals-provider change refresh

## Status

Implemented and verified on `emulator-5554` on 2026-08-26.

## Reported behavior

While widget 59 displayed Open-Meteo, changing its actual-temperature provider to Synoptic did not
change the widget immediately. Synoptic appeared roughly a minute later, after a subsequent worker
sync.

## Runtime evidence

1. The emulator was verified as `emulator-5554`, Google `sdk_gphone64_x86_64`, API 36.
2. The provider choice was stored at about 13:42:46. The observations activity immediately loaded
   cached Synoptic stations, proving usable Synoptic rows were already present locally.
3. `WeatherObservationsActivity.refreshData()` began the network refresh immediately, but it ran in
   the activity's `lifecycleScope`.
4. The activity was closed at about 13:42:49. Its scope was cancelled as the Synoptic response
   completed at about 13:42:50, before the activity could finish persistence, set
   `widgetContentChanged`, or request the widget repaint.
5. A later worker sync stored 1,672 Synoptic rows and repainted the widget at about 13:43:56. The
   visible delay from provider selection was therefore about 70 seconds.

## Root cause

Provider selection changes durable application state, but its required follow-up work is owned by
the short-lived observations activity. Leaving that activity can cancel the refresh. The repaint is
also deferred until the activity reports that its refresh completed, so even already-cached rows do
not become visible immediately.

## Proposed change

1. Extract the provider-change follow-up into a small coordinator/testable seam.
2. After saving the selected provider, immediately enqueue a cache-only widget repaint with reason
   `actuals_provider_changed`. This makes existing rows for the newly selected provider visible
   without waiting for network I/O.
3. Enqueue a required forced sync targeted at the displayed forecast source, also with reason
   `actuals_provider_changed`. Use `WidgetWorkScheduler.enqueueRequiredImmediateSync`, whose
   `APPEND_OR_REPLACE` policy preserves the follow-up without cancelling a running worker.
4. Keep the observations screen responsive by reloading its local list, but do not make correctness
   depend on its `lifecycleScope` completing. Avoid launching a second activity-owned 24-hour
   provider fetch for this provider-change path.
5. Retain the existing manual refresh-button behavior as a separate operation unless implementation
   evidence shows it shares the same ownership bug.

## Tests

1. Add a focused Robolectric test proving a provider change requests both an immediate UI repaint
   and a required full sync targeted at the current display source.
2. Assert the full sync uses `APPEND_OR_REPLACE`, is forced, and carries the
   `actuals_provider_changed` reason.
3. Prove finishing/destroying the activity cannot discard either request.
4. Preserve existing `WeatherObservationsSupport` exit-refresh behavior for ordinary inspection and
   source changes.

## Verification

1. Run the focused Android unit/Robolectric tests and duration-category validation.
2. Run the relevant scheduler policy tests.
3. Build/install the debug APK on `emulator-5554`.
4. Reproduce Open-Meteo -> Synoptic on widget 59, then immediately close the observations screen.
5. Verify from screenshot, logcat, and `app_logs` that cached Synoptic data repaints promptly and the
   fresh Synoptic fetch persists afterward without lifecycle cancellation.

### Results

1. `ActualsProviderChangeCoordinatorTest`, `WeatherObservationsActivityRobolectricTest`, and
   `WeatherWidgetProviderEnqueuePolicyTest` passed (including duration-category validation).
2. `:app:assembleDebug` and `git diff --check` passed.
3. The debug APK was installed without clearing data on the verified Google
   `sdk_gphone64_x86_64` emulator (API 36).
4. The provider was first changed to METAR to establish a visible non-Synoptic baseline. Selecting
   Synoptic at 13:54:19.286 immediately enqueued both requests with reason
   `actuals_provider_changed`; the full sync used `APPEND_OR_REPLACE`, `force=true`, and target
   `OPEN_METEO`.
5. The activity was closed about 0.2 seconds after selection. The widget resolved and rendered
   `provider=SYNOPTIC` at 13:54:21.490, about 2.2 seconds after selection, using cached rows.
6. The lifecycle-independent full sync then stored 1,672 fresh Synoptic rows at 13:54:22.893,
   about 3.6 seconds after selection. No `JobCancellationException` occurred.
7. The final emulator screenshot visibly showed the pink Synoptic curve and
   `Actual temperature data from Synoptic` on the Open-Meteo widget.

## Scope note

This plan is Android-only because it addresses the observed Android activity-lifecycle failure. The
separate desktop source-toggle refresh work remains covered by
`plans/260826-desktop-source-toggle-actuals-refresh.md`.
