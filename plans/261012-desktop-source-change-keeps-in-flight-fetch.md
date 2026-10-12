# Desktop: a source change does not cancel the fetch already in flight

## Problem

On 2026-10-12 the NWS "yesterday's forecast" bar on today's column drew **dashed**. Its low comes
from the newest NWS daily fetch before 2026-10-11 06:00, which was 2026-10-09 22:19, 31.7 h earlier.
NWS fetches *were* started on 10-10, but every one was cancelled within 1–2 s by clicking on to the
next source:

```
10-10 07:08:40 REFRESH_ENTER reason=launch:source_change source=NWS
10-10 07:08:41 CONFIG_SAVE   NWS -> GOOGLE_WEATHER
10-10 07:08:41 REFRESH_CANCELLED reason=launch:source_change source=NWS
10-10 14:50:57 … 14:50:59 REFRESH_CANCELLED source=NWS
10-10 14:52:08 … 14:52:10 REFRESH_CANCELLED source=NWS
```

Cycling through sources to reach one therefore never refreshes the sources passed on the way.

## Root cause

`DaemonRuntime.startFetchLoops(reason)` begins with `fetchJob?.cancel()`. The launch refresh
(`runLaunchRefresh`) runs as a **child** of `fetchJob` (step "1. Startup refresh"). A source change
is a refresh-relevant config change (`daemonFetchRestartReason` → `SOURCE_CYCLE_REASON`), so it
restarts the loops and cancels the previous source's launch refresh mid-request.

Android does not have this problem: a toggle enqueues its forced sync with `ExistingWorkPolicy.KEEP`
(`RefreshScheduler`), which never cancels a running sync. **No Android change.**

## Fix (desktop only)

1. **Launch refresh in its own job.** `startFetchLoops` launches `runLaunchRefresh` as
   `launchRefreshJob` in `daemonScope`, beside `fetchJob` instead of inside it.
   - On a restart with `reason == SOURCE_CYCLE_REASON`, the previous launch refresh is **left
     running** (detached).
   - Every other restart reason (location change, resume / network client reset, config null)
     cancels it as today. Those fetches are for a site or client that no longer applies; the user
     asked only about source changes.
2. **A detached refresh finishes its fetch but does not paint.** It still calls
   `refreshWithOutcome`, which writes the `forecasts` and hourly rows and logs `REFRESH`.
   - Every UI step checks `currentConfig?.displaySource == config.displaySource` first: the
     publishes to `panelPublisher` / `forecastState` / `dataStatusState` and `checkDominantTempWatch`.
   - The post-fetch observation-window refresh and climate normals belong to the displayed source,
     so they are skipped when superseded.
   - Logged as `LAUNCH_REFRESH_DETACHED source=… outcome=stored|failed published=false`.
   - If the user has cycled back to that source by the time it finishes, the check passes and it
     paints as usual.
3. **One in-flight launch refresh per source.** An in-flight set keyed by source id. A
   source-change launch refresh for a source whose detached refresh is still running skips the
   fetch (`LAUNCH_REFRESH_CHECK … action=IN_FLIGHT`) and lets that one finish and paint by rule 2.
   This stops A→B→A→B cycling from stacking duplicate requests.
4. **Old client closes after its detached refresh.** The replaced `DesktopWeatherService` is closed
   once `REPLACED_CLIENT_CLOSE_GRACE_MS` has passed **and** the detached job has completed (`join`).
   The grace alone (60 s) could cut a slow fetch off with "client closed".

## Tests

| # | Kind | Class | Asserts |
|---|---|---|---|
| 1 | Unit | `LaunchRefreshJobsTest` (pure helper `launchRefreshSurvivesRestart(reason)`) | true only for `SOURCE_CYCLE_REASON`; false for location change, `resume:*`, `network:restored`, startup |
| 2 | Unit | `LaunchRefreshJobsTest` (pure `launchRefreshPublishes(fetched, current)`) | publishes only when source and site match |
| 3 | Integration | `DaemonRuntime` + `DesktopWeatherRepository` (fake slow service) | Start a launch refresh for NWS that suspends mid-fetch; switch the config to GOOGLE_WEATHER; the NWS fetch completes, its `forecasts` rows are stored, `REFRESH_CANCELLED` is **not** logged, and `forecastState` still shows Google |
| 4 | Integration | same | Same, but the switch is a **location** change → NWS refresh cancelled (`REFRESH_CANCELLED`), unchanged behaviour |
| 5 | Integration | same | NWS → Google → NWS while the first NWS fetch is in flight: one NWS service request total; when it completes it paints (NWS is displayed) |
| 6 | Integration | same | Replaced service is not closed until the detached refresh completes, even past `REPLACED_CLIENT_CLOSE_GRACE_MS` (virtual time) |
| 7 | Manual | running desktop app | Cycle NWS→Google quickly; expect `REFRESH source=NWS daily=8` in app_logs and no `REFRESH_CANCELLED` |

## Verification

2026-10-12.

- Implemented as `LaunchRefreshJobs` (desktop), which owns the launch-refresh jobs, the per-source
  in-flight set and the old-client close. `DaemonRuntime.startFetchLoops` / `runLaunchRefresh` use it.
- `LaunchRefreshJobsTest` (8) covers tests 1–6, against a real `DesktopWeatherRepository` and DB:
  - a source change leaves the held fetch running: it stores `REFRESH` and logs no `REFRESH_CANCELLED`;
  - a location change cancels it;
  - cycling A→B→A makes one request;
  - a cancelled owner does not block the next fetch;
  - the client is closed only after the detached fetch finishes (virtual time).
- Full `:desktop:test` (575) green. Desktop rebuilt and restarted. Test 7 (manual cycle) is
  pending: expect `REFRESH source=NWS daily=…` and, if the popup has moved on,
  `LAUNCH_REFRESH_DETACHED … outcome=stored`.
