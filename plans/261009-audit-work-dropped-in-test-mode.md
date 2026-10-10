# Audit: which queued work is lost when a test process (or an early return) drops it

Follow-up to `plans/261009-observation-backfill-cooldown-starts-when-it-runs.md`. Audit only — no
behaviour changed.

## How work gets dropped
WorkManager itself re-runs stopped or killed work (constraint loss, process death mid-run). What is
actually lost is work our own code finishes as `Result.success()` without doing it:

| Path | Where | Reaches production? |
|---|---|---|
| `WeatherDatabase.isTestingMode()` | `WeatherWidgetWorker.doWork`, `HourlyOnDemandWorker.doWork` | No — only while an instrumented test owns the app process (dev devices / emulator) |
| `KEY_ENQUEUED_IN_TESTING` | same | No — only work a test enqueued |
| Startup-cooldown deferral | `WeatherWidgetWorker.deferForStartupCooldown` | Yes, but the run is re-queued in `WORK_NAME_STARTUP_DEFERRED`, not lost. Hidden from per-name pending checks: fixed for observation backfill (`observation_backfill` tag) |
| Backfill with no location | `handleObservationBackfillWork` | Yes, deliberate (`OBS_HOURLY_BACKFILL_SKIP cause=no_location`) |

## Per work type: what restores it if a run is dropped

| Work | Recovery | Gap if dropped |
|---|---|---|
| Periodic sync | next periodic tick | ≤ one tick |
| Charging current-temp loop | re-armed (inspect-and-keep) from `WidgetRefreshCoordinator`, `UIUpdateReceiver`, `PowerConnectedRefresh`, `WidgetLoopScheduler`, `ScreenOnReceiver` | until the next of those (minutes) |
| Non-primary observation loop | same entry points | minutes |
| UI update alarm | `AlarmManager`, not WorkManager | none |
| Observation backfill | `HourlyBackfillGate` re-requests a dropped request on the next gap check | next paint |
| Location-change **banner** (cached site) | `FetchBanner.MAX_SHOW_MS` = 120 s expiry on the transient message; any later paint drops it | ≤ 120 s + next paint |
| Location-change **interstitial** (first-ever location, nothing cached) | `pendingLocationFetch` has **no expiry**; cleared only by a render with rows or the sync's failure branch | until the next fetch that renders rows (periodic tick: up to hours off-charger) |
| On-demand hourly ("Fetching hourly forecast for {day}…") | same `FetchBanner` 120 s cap | ≤ 120 s |
| Failure-banner stage repaint | next paint recomputes the stage from `bannerSinceMs` | next paint |

## Finding
Only the **first-ever-location interstitial** can stick, and only when its forced sync is dropped —
which in practice means an instrumented test run during first-time setup on a dev device. Production
cannot drop it: no early-return path applies to a location-change sync outside test mode.

## Option (not implemented, needs approval)
Give `pendingLocationFetch` the same safety cap as the banner (store the start time; a paint past
`FetchBanner.MAX_SHOW_MS` with no rows swaps in the "Tap to refresh" fallback via
`renderPendingLocationFetchFailure("expired")`). Small, and makes the interstitial obey the
"never a dead end" rule even when its sync never reports back. Recommended only if dev-device test
runs during setup are a real workflow; otherwise leave as is.
