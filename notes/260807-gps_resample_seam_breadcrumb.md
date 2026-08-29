---
name: gps-resample-seam-breadcrumb
description: "Location auto-heal in GpsResampler (injectable seams); background passive-only (Samsung warning) with ONE foreground exception (ConfigActivity precise button); LocationMode pin → skipped_pinned; GPS_RESAMPLE app_logs answer \"is location used\""
metadata: 
  node_type: memory
  type: project
  originSessionId: 802d3b44-9d46-45bd-9510-5e2bf1fc42ce
  modified: 2026-08-07T21:08:08.429Z
---

Location auto-heal (background worker + MainActivity foreground) is centralized in
`app/src/main/java/com/weatherwidget/widget/GpsResampler.kt` (extracted 2026-07-06 from
`WeatherWidgetWorker.sampleGpsAndMaybeUpdateLocation`). Three injectable seams make it unit-testable:
`locationProvider` (fused `lastLocation`), `permissionChecker`, `applyHeal` (defaults to
`LocationUpdater.applyToAllWidgets`, which takes an explicit `ids: IntArray` so tests never touch
real widgets).

- **Background = passive-only; ONE foreground exception (2026-07-07, user-approved)**: background
  paths (GpsResampler, MainActivity heal) NEVER call `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` —
  Samsung One UI warns on active background fixes. The single exception is ConfigActivity's
  "Use precise device location" button (foreground, explicit tap; user chose this after initially
  banning all active fixes). The former battery/charging gate and background-permission
  active/passive choice were deleted.
- **Location pinning**: `LocationMode` (`weather_prefs` key `location_mode`, absent=follow_device).
  Search/coords choices in ConfigActivity set `fixed`; both heal paths gate on it FIRST and log
  `outcome=skipped_pinned`. "Use precise device location" resets to `follow_device`. This is also
  the emulator remedy — pin a location there instead of emulator-specific GPS code.
- **"Is location being used?" is a DB query**: `SELECT * FROM app_logs WHERE tag='GPS_RESAMPLE'` —
  one row per attempt with `outcome=skipped_no_permission|skipped_pinned|no_fix|same_site|healed`
  (healed=INFO, rest=DEBUG). `mode=` token is always `last_location` now. Every outcome carries
  `trigger=` as of 2026-08-07.
- **Four triggers** (2026-08-07): `worker` (only from `WeatherWidgetWorker.handleFullSyncWork`, behind
  a gate excluding `uiOnlyRefresh`/`currentTempOnly`/`nonPrimaryCurrentTempOnly`/
  `observationBackfillMode`/`candidateLocationRefresh`), `foreground` (MainActivity),
  and `power_connected` + `user_present` (`ScreenOnReceiver`). The last two were added because the
  refreshes those events enqueue are *exactly* the excluded kinds, so plug-in and unlock never
  resampled and a stale location survived until the next full sync — 60 min plugged, up to 480 min
  on low battery (observed: 41 min of wrong data, see [[location_move_collapses_today_actuals]]).
- **`trigger` also decides `enqueueRefresh`** (`trigger != "worker"`): the worker is mid-sync and
  fetches the new location itself; an event-driven caller is not, so it must enqueue one or the new
  site gains no data at all. Any new trigger must NOT be named `"worker"`.
  *(Updated 2026-08-28: this used to read "or the candidate never gains the data
  `evaluateCandidateUsability` requires for promotion". There is no candidate and no promotion any
  more — a detected move is applied immediately; see
  `plans/260828-remove-the-location-handoff-policy.md`. The rule about the trigger name is
  unchanged, and now matters more: nothing else will fetch for the new site until the next full
  sync.)*
- **`ScreenOnReceiver` is deliberately NOT `@AndroidEntryPoint`** — `ScreenOnReceiverTest` constructs
  it directly under a plain Robolectric application, and Hilt's generated `onReceive` would throw.
  It resolves `GpsResampler` through `RepositoryEntryPoint` (`EntryPointAccessors`) at call time,
  behind the `resampleLocation` test seam.
- **Simulating the events over adb does not work**: `dumpsys battery unplug` / `set ac 1` did not
  deliver `ACTION_POWER_CONNECTED` to the app (no `POWER_CONNECTED_EVENT` row), and a scripted
  lock/swipe produced no `ACTION_USER_PRESENT` (no `UNLOCK_REFRESH_POLICY` row — that one is
  pre-existing code, so its absence proves delivery failed, not the new code). Verify these paths
  with unit tests plus a real cable/lock, not adb.
- **Why seams, not end-to-end GPS tests**: `doWork()` early-returns in testing mode, and the emulator's
  FusedLocationProviderClient does NOT consume `adb emu geo fix`. Don't chase a live
  `healed` repro on the emulator; unit tests cover the pipeline, `LocationUpdaterIntegrationTest`
  covers propagation (real WorkManager is safe because the enqueued worker no-ops in test mode).

Related: [[widget-fetch-location-decoupled]], [[shared-location-match-predicate]],
[[shared-prefs-test-default-suffix]].
