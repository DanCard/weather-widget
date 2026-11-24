# Retry missing climate normals on every routine refresh, both platforms

Follow-up to `plans/261122-climate-normals-warm-and-repaint-shared.md` (commit `e92e58bdb`).

## Question (user, 2026-11-22)

> Should fetching climate normals have some kind of retry strategy?
> Does android climate normals retry work the same way?
> create plan

A side-agent note started this. "Go parallel" removed desktop's blocking warm after each forecast
refresh, and with it desktop's only routine retry.

## Evidence

### Where the settle runs today (`e92e58bdb`)

| Platform | Settle call sites | Retried at |
|---|---|---|
| Android | `FullSyncPipeline.run`, network-allowed runs only | next full sync |
| Desktop | `DaemonRuntime.runLaunchRefresh` (launch, resume, network restored, location change, source change) | next wake / location / source change |

Android's `WeatherWidgetWorker` sends `currentTempOnly`, `nonPrimaryCurrentTempOnly` and
`observationBackfillMode` runs to their own handlers (`WeatherWidgetWorker.kt` ≈ 118–137). Those runs
never reach `FullSyncPipeline`, so they never settle.

### Cadence measured

**Android:** phone RFCT70G352Y, `backups/20261122_012441_sm-f936u1_RFCT70G352Y`, `app_logs` since
2026-11-19 00:32. These rows predate the fix (installed 01:21), so they show how often each kind of run
happens, not retries that actually ran.

| Run (log line) | Count | Median gap | p90 | Largest gaps |
|---|---|---|---|---|
| Full sync (`WIDGET_LIFECYCLE phase=worker_paint_start uiOnly=false`) | 93 | 6 min | — | 442 min (11-21 01:06), 416, 300 (11-20 18:59), 256 (11-20 14:41), 251 |
| Current temp (`CURR_FETCH_START`) | 217 | 12 min | 38 min | 215 min (11-21 20:31), 103, 102, 98, 90 |

Current-temp reasons: `charging_loop` 153, `charging_screen_on_catchup` 31, `location_changed` 7,
`power_connected_lazy` 6, `battery_screen_on_catchup` 6, others ≤ 4.

**Desktop:** `CURR_FETCH_START reason=charging_loop` at 00:35, 00:45, 00:55, 01:04 and 01:15 on
2026-11-22, so about 10 min. Its forecast loop sleeps for hours (`FORECAST_LOOP_SLEEP sleepMin=510`).

### Why one failure matters on desktop

Desktop Open-Meteo requests time out in clusters 1–4 min after the host resumes (project memory:
open-meteo desktop timeouts after resume). A resume launch refresh is exactly when desktop settles.
One `CLIMATE_WARM_FAIL` then leaves the fallback days blank until the next wake, location change or
source switch.

## Root cause

The shared rule names *moments* (site settle) that each platform maps to different loops. It never
says "retry". Android gets retries as a side effect of full-sync frequency, which depends on
interaction and can lapse for 4–7 h. Desktop gets none.

## Proposed fix (Claude's proposal)

1. **Shared rule:** settle on **every routine refresh**, meaning full syncs and current-conditions
   runs, plus every site change. Always in parallel, never before a paint. A failed fetch is retried
   at the next routine refresh. Write this in `ClimateNormalsRun`'s KDoc. Add no timer, backoff or
   cooldown: the refresh cadence is the retry cadence. A cached site costs one database read, and a
   failing archive costs one background request per refresh.
2. **Android:** `WeatherWidgetWorker.handleCurrentTempOnlyWork` runs
   `weatherRepository.settleClimateNormals(lat, lon, reason = "current_temp")` as a `launch` child of
   a `coroutineScope` around its existing body, at the location that run resolves. This is the same
   shape as `FullSyncPipeline`, and the hook stays `StoredDataVersion` bump + `enqueueUiRepaint`.
   Non-primary current-temp runs and observation-backfill runs stay excluded: they have no
   displayed-site role.
3. **Desktop:** the current-conditions loop tick (the `charging_loop` / `screen_on` paths that log
   `CURR_FETCH_START`) starts `activeRepo.ensureClimateNormals(reason = "current_temp:<reason>")`
   with `daemonScope.launch`. Its hook is the same `stillDisplayed`-guarded publish + notify as
   `runLaunchRefresh`. Factor that hook into one private helper so the two call sites cannot drift.
4. **Single-flight** (already in `ensureForSite`) keeps a tick that overlaps a launch settle to one
   archive request.

### Expected retry windows after the fix

| Platform | Usual | Worst observed in the data above |
|---|---|---|
| Android | ≤ 12 min (current temp) or 6 min (full sync) | the smaller of the two gaps, about 215 min at the 11-21 20:31 lull |
| Desktop | about 10 min | about 10 min while the loop runs |

### Not proposed

- **A dedicated retry timer or backoff.** It would duplicate the refresh loops, and nothing waits on
  the archive.
- **Keeping normals for recent sites.** That makes the problem rarer but does not retry a first visit.

## Tests

- `:shared` `ClimateNormalsRunTest`: a failed `ensureForSite` followed by a second call that
  succeeds fetches twice and repaints once.
- `:desktop` integration (`DesktopClimateNormalsSettleTest` style, real SQLite): the first archive
  call throws (timeout), the second answers. The two settles stand in for a launch and the next tick.
  Assert no repaint after the failure, one repaint after the success, and fallback rows for today + 7.
  Also test the factored hook helper: it publishes and notifies only while `stillDisplayed`.
- `:app` Robolectric worker test: a `currentTempOnly` run calls `settleClimateNormals` once, in
  parallel. With the archive gated, the run's current-temp work completes first and the worker waits
  for the child. A `nonPrimaryCurrentTempOnly` run never calls it.
- The existing `FullSyncPipelineClimateSettleTest` and the round-trip tests stay as they are.

## Docs

`arch/forecast-and-observation-cadence.md`, "Climate normals" section:
- "When" becomes "every routine refresh (full sync, current-conditions run) and every site change";
- add a retry line that points at the cadence tables rather than repeating numbers.

## Verification

- **Desktop:**
  1. Deploy, then simulate the resume failure by blocking `archive-api.open-meteo.com` for one settle.
     Preferred: a debug-only `CLIMATE_ARCHIVE_FAIL_ONCE` test hook, gated like the existing debug
     toggles. Fallback: an `/etc/hosts` entry only with the user's OK.
  2. Delete the site's `climate_normals` rows (re-fetchable cache), then trigger a resume.
  3. Expect `CLIMATE_WARM_FAIL reason=launch:…`, then within about 10 min `CLIMATE_CACHED` and
     `CLIMATE_REPAINT reason=current_temp:…`, and a screenshot with the green fallback bar.
- **Android (emulator):** same deleted-rows setup through `adb` on the debug build. Watch the next
  `CURR_FETCH_START` run log `CLIMATE_REPAINT reason=current_temp`, then screenshot the widget.
- **Still owed from the previous plan:** the live location round-trip (Mountain View → Scottsdale) on
  desktop and emulator.
