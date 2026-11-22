# Climate normals: one shared rule for when to warm them and repaint

## Symptom (user, 2026-11-22 00:42 MST)

> Desktop: NWS missing sunday column a week from today on far right
> If forecast is missing shouldn't climate normals be displayed instead?
> I'm only concerned about the climate normals missing

## Evidence (desktop)

Desktop DB `~/.local/share/weather-widget/weather.db`, `app_logs`:

| Time | Event |
|---|---|
| 00:25:10 | Moved to Mountain View; `CLIMATE_CACHED` for it. Retention is current location only, so Scottsdale's normals were deleted. |
| 00:35:23 | Back to Scottsdale. `LOCATION_CHANGE_REFRESH action=keep_cache` (forecast 82 min old). |
| 00:35:25 | Observation refresh. `publishStored` calls `notifyDataUpdated()` and the UI reloads, with no normals for the site yet. |
| 00:35:26 | `CLIMATE_CACHED` (Scottsdale `climate_normals.fetchedAt` = 00:35:26). |

NWS has no row for 11-29 (today + 7), so that column needed the green climate fill and drew nothing.
A screenshot at 00:48 shows the fill (69.4° / 47.3°) after a later reload.

## Root cause

**Desktop.** In `DaemonRuntime.kt` ≈1092, when `ensureClimateNormals()` newly caches, the daemon only
calls `panelPublisher.publishForecastState(...)`. That updates the daemon's in-memory snapshot and
`current_status`. The window runs as a separate `ui` process, and it reloads only on
`notifyDataUpdated()` (`.data-updated` / `ui-notify.sock`). So the averages stay invisible until some
unrelated notification.

**Android (code reading, not yet seen on a device).** `ClimateNormalsRepository.warmBestEffort` runs
only inside `ForecastRepository.fetchAndRecord`, which means only after a network forecast fetch.
Android does not fetch on a location change while the site's cache is fresh
(`LocationChangeRefreshPolicy`). Retention deletes every other site's normals. So a round-trip back
to a site whose forecast is fresh leaves its normals missing until the next cadence fetch, which can
be up to 10 h away (24 h on low battery).

**Shared gap.** `:shared` `ClimateNormalsRun` owns only the fetch and cache. *When* to warm and *what
to do once newly cached* are separate per-platform code, and the two platforms differ on both. This
breaks the AGENTS.md rule that a shared runner owns the complete rule.

## Proposed fix (Claude's proposal)

1. `ClimateNormalsRun` gains the whole rule:
   - `Host.onNormalsCached()`: the platform's "stored data changed, repaint" hook.
   - `ensureForSite(host, lat, lon, reason)`: calls `warm`, and when `fetched = true` calls
     `onNormalsCached()` and logs `CLIMATE_CACHED … reason=<reason>`.
2. One shared trigger list, called from both platforms:
   - after every forecast fetch;
   - on every site settle (launch, resume, location change), **whatever** the forecast-refresh
     action (`NONE` / `OBSERVATIONS` / `keep_cache` included).

   This is cache-first, so it costs nothing once the normals are cached.
3. Hosts:
   - **Desktop:** `publishForecastState(loadCached())` + `notifyDataUpdated()`. The daemon's two
     existing publish-then-notify blocks become one helper.
   - **Android:** `StoredDataVersion.process.bump("climate_normals")` + the existing widget repaint
     request for the site's widgets.
4. The location change on Android calls `ensureForSite` even when `LocationChangeRefreshPolicy`
   keeps the cache.

Retention stays "current location only", unchanged. Keeping normals for recent sites would make the
problem rarer but would not fix the first visit to a site.

## Tests

- `:shared` `ClimateNormalsRunTest`, with a fake host:
  - `onNormalsCached` fires exactly once when it fetches;
  - it does not fire on a cache hit;
  - it does not fire on an empty fetch or a failed fetch.
- Integration:
  - Desktop: the daemon's launch/location path over the real desktop SQLite host, with no normals
    cached and a stubbed archive fetch. Assert the data-updated notification fires after
    `CLIMATE_CACHED`, and that `loadCached()` then holds `isClimateNormal` rows for today + 3 to
    today + 16 where no forecast covers them.
  - Android: Robolectric over Room. A location change with a fresh cache and no normals warms them
    and requests a repaint.
- `ClimateNormalsHostContract` gets the new hook.

## Docs

- `arch/data-pruning.md`: link the warm rule from the `climate_normals` row.
- `arch/forecast-and-observation-cadence.md`: in the location-change section, say that normals warm
  on every site settle.

## Verification

- **Desktop:** deploy, then do a location round-trip (Mountain View, then back to Scottsdale).
  - Screenshot within seconds: the far-right day has the green climate bar.
  - Logs: `CLIMATE_CACHED reason=location_change`, followed by a UI reload.
- **Android (emulator first, then all devices):**
  - Before the fix: reproduce the round-trip to confirm the gap.
  - After the fix: same round-trip; screenshot the widget's daily view.

## Revision (user, 2026-11-22): settle in parallel, never before the paint

The first implementation settled normals on Android's cache-served sync paths **before** returning
rows to the worker, so a missing site's paint waited on the Open-Meteo archive. With a 30 s request
timeout and 2 retries, that wait could reach about 90 s.

A side-agent note raised this. The user asked: "Why is there a wait? Can't both happen in parallel?"
The answer was yes: "go parallel".

- `ensureForSite(host, lat, lon, reason)`:
  - single-flight `Mutex`, so parallel settles fetch once;
  - a failing hook is logged (`CLIMATE_REPAINT_FAIL`), never thrown;
  - the `Trigger` enum is replaced by a `reason` string.
- **Android:**
  - `FullSyncPipeline` launches `settleClimateNormals` as a child of a `coroutineScope` around fetch
    and paint, for network-allowed runs only;
  - `ForecastRepository` no longer warms normals anywhere in `getWeatherData` / `fetchAndRecord`;
  - the hook bumps `StoredDataVersion` and calls `WidgetWorkScheduler.enqueueUiRepaint`.
- **Desktop:**
  - `runLaunchRefresh` starts the settle at its beginning (`daemonScope.launch`); its hook publishes
    and notifies only while `stillDisplayed()`;
  - the blocking warm inside `refreshWithOutcome` is removed.
- No timeout and no failure cooldown: nothing waits, so a slow or failing archive costs only
  background requests. Revisit if `CLIMATE_WARM_FAIL` repeats in logs.
- Tests:
  - `FullSyncPipelineClimateSettleTest`: the paint lands while the archive is gated, and the worker
    waits for its child. It fails when the settle is made sequential.
  - `ForecastRepositoryClimateSettleRoboTest`: a sync never calls the archive; the settle re-caches,
    enqueues `WORK_NAME_UI`, and bumps the version.
  - `ClimateNormalsRunTest`: parallel settles produce 1 fetch and 1 repaint; a failing hook is
    handled.
