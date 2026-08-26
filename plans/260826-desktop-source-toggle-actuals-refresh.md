# Desktop Source Toggle: Refresh Stale Actual Temperature Provider

**Date:** 2026-08-26  
**Scope:** Linux desktop only (`:desktop` and desktop persistence in `:shared`)  
**Status:** Implemented and runtime-verified

## Goal

When the user switches the desktop weather API, immediately refresh the newly displayed source when
its forecast is stale and refresh its resolved actual-temperature provider when that provider's data
is missing or at least 10 minutes old. The refresh must target the configured actuals provider rather
than treating the display source's most recent refresh log as proof that its actual-temperature data
is current.

## Evidence Collected

The live desktop configuration selected `OPEN_METEO` for forecasts and `METAR` as Open-Meteo's
actual-temperature provider.

At 2026-08-26 13:20:14 local time, the popup switched from NWS to Open-Meteo. The daemon immediately
logged `LAUNCH_REFRESH_CHECK ... action=FULL_FORECAST`, entered the Open-Meteo refresh, and completed
it at approximately 13:20:17. This proves that the existing config-change watcher already starts a
source refresh quickly; waiting for the normal observation timer is not the primary defect.

The resulting rows did not refresh the configured actuals provider:

1. Native Open-Meteo observations advanced to 13:30 and had a recent `fetchedAt`.
2. METAR remained at an approximately 11:55 observation with a 12:03 `fetchedAt`, roughly 100 and
   91 minutes old respectively when inspected.
3. The 13:20 Open-Meteo full refresh logged no `BORROWED_METAR_FETCH` or
   `BORROWED_METAR_RECOVERY` event.

## Root Cause

### 1. The daemon's actuals-provider preference becomes stale

`DaemonProcess.runDaemon()` installs `DesktopActualsPreference` and publishes the settings loaded at
daemon startup. The `.config-changed` watcher later reloads `currentConfig` and restarts fetch loops
when the location or display source changes, but it never calls
`DesktopActualsPreference.update(newConfig.settings)`.

Consequently, a provider choice made after daemon startup is visible to the UI process but not to the
daemon. When the display source changes, `DesktopWeatherRepository.fetchBorrowedRecovery()` can
resolve the provider from the daemon's old settings and refresh native Open-Meteo instead of the
currently selected METAR feed.

### 2. Observation freshness is keyed to the display source

Both the daemon launch check and Gemini's in-flight UI patch call
`DesktopWeatherDao.getLastSuccessfulObservationFetch(displaySource)`. That method reads `REFRESH` and
`OBS_REFRESH` log rows whose `source=` field is the display source. It does not identify which
actuals provider supplied the observations.

Therefore, a recent Open-Meteo refresh can make the check report "fresh" even when the Open-Meteo
graph is configured to use stale METAR actuals. It also cannot distinguish a provider change made
after the last display-source refresh.

### 3. Gemini's UI-side patch duplicates the daemon refresh

The uncommitted `Main.kt` patch runs another network refresh from `LaunchedEffect(repository)`. The
daemon already restarts its source-specific loops and runs `runLaunchRefresh()` on the same source
change. The UI and daemon are separate processes and do not share `refreshInFlight`, so both can
start the same network work concurrently.

The patch also uses the display-source freshness method described above and runs in a separate
Compose effect from `DesktopActualsPreference.update(config.settings)`, leaving an ordering race in
which the refresh can resolve the previous actuals provider.

## Proposed Implementation

### Phase 1: Keep the daemon's preference snapshot current

In `desktop/src/main/kotlin/com/weatherwidget/desktop/DaemonProcess.kt`:

1. On every successful config reload, call `DesktopActualsPreference.update(newConfig.settings)`
   before updating state or starting/restarting fetch loops.
2. Clear the preference snapshot when the config becomes unavailable.
3. Treat an actuals-provider change as refresh-relevant even when the location and display source
   are unchanged. Restart or otherwise reconfigure the active observation path so it immediately
   uses the new provider.
4. Give the launch check a precise reason such as `source_change` or `actuals_provider_change`
   instead of labeling every restart `startup`, improving persistent-log evidence.

### Phase 2: Measure the resolved provider's data freshness

In `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt`:

1. Add a read method for the newest persisted observation fetch at a location and provider, based on
   `MAX(observations.fetchedAt)` filtered by `locationLat`, `locationLon`, and `api`.
2. Return `null` when no matching provider rows exist so missing actuals trigger a refresh.
3. Keep forecast freshness based on the existing per-display-source successful forecast timestamp.

At the desktop orchestration boundary:

1. Resolve `displaySource` through `ActualsProviderResolver.providerIdFor(displaySource)` after the
   latest settings have been published.
2. Feed the resolved provider's observation timestamp into the existing 10-minute freshness policy.
3. Select `FULL_FORECAST` when the selected forecast is stale; otherwise select `OBSERVATIONS` when
   the resolved actuals provider is missing or stale; otherwise perform no network request.
4. Include both `source=` and `actualsProvider=` plus their ages in `LAUNCH_REFRESH_CHECK` so runtime
   behavior is directly auditable.

### Phase 3: Use a single source-toggle refresh owner

Prefer the daemon's existing config-change/start-loop path as the sole network owner:

1. Rework or remove Gemini's uncommitted UI-side network block in `Main.kt` rather than layering it
   on top of the daemon refresh.
2. Keep the UI's existing cache reload and daemon-notification paths; they should display durable
   rows produced by the daemon.
3. Do not add a second process-local cooldown. The 10-minute provider freshness check is the
   per-provider throttle.
4. Preserve unrelated user changes and limit reconciliation to Gemini's current `Main.kt` diff.

## Tests

All new test classes must declare exactly one desktop duration category.

1. Extend the launch-refresh policy tests to cover:
   - fresh forecast plus stale resolved provider selects `OBSERVATIONS`;
   - stale forecast plus stale provider selects `FULL_FORECAST`;
   - fresh forecast and fresh provider selects `NONE`;
   - missing provider rows select `OBSERVATIONS`.
2. Add DAO coverage showing provider freshness is isolated by API and location: a fresh
   `OPEN_METEO` row must not make stale `METAR` rows fresh.
3. Add daemon/config seam coverage showing a provider setting changed after startup is published
   before the next refresh decision.
4. Preserve existing `DesktopBorrowedMetarObservationsTest` coverage proving an observations-only
   Open-Meteo refresh actually requests and returns METAR readings.
5. Add a regression assertion that one source change selects one network-refresh owner rather than
   starting concurrent UI and daemon refreshes.

## Verification

After implementation approval:

1. Run focused desktop tests for refresh policy, provider preference, DAO freshness, and borrowed
   METAR observations.
2. Run `./gradlew :desktop:test` and `./gradlew :desktop:createDistributable`.
3. Restart the installed desktop daemon/UI so verification is against the newly built binary.
4. Configure Open-Meteo to use METAR actuals, allow METAR rows to become stale or use controlled
   test state, switch away, then switch back to Open-Meteo.
5. Verify persistent logs show:
   - config reload with the current provider;
   - `LAUNCH_REFRESH_CHECK` naming `source=OPEN_METEO actualsProvider=METAR`;
   - an immediate `OBSERVATIONS` or `FULL_FORECAST` decision as appropriate;
   - `BORROWED_METAR_FETCH` or `BORROWED_METAR_RECOVERY`;
   - successful `OBS_REFRESH` or `REFRESH` completion.
6. Query `observations` and confirm the newest METAR `fetchedAt` advanced at the active location.
7. Inspect the temperature graph and current-status output to confirm the visible actual line and
   temperature resolve from the refreshed provider.

## Risks and Controls

1. **Duplicate API calls:** keep one network owner and base suppression on provider-specific
   freshness.
2. **Provider changes without source changes:** publish all settings on every config reload and make
   `actualsProviders` refresh-relevant.
3. **Cross-location contamination:** filter observation freshness by both provider and location.
4. **False freshness after provider changes:** do not infer provider freshness from display-source
   log timestamps.
5. **Rapid repeated toggles:** the active daemon loop remains cancellable/restartable, while durable
   provider timestamps prevent redundant completed fetches after the newest source settles.

## Verification Result

Implementation was approved on 2026-08-26. Gemini's overlapping UI-side refresh was removed and the
daemon remains the single network owner.

Focused and full verification passed:

1. Focused DAO, provider-preference, refresh-policy, and borrowed-METAR tests passed.
2. `./gradlew :shared:test :desktop:test :desktop:createDistributable` completed successfully.
3. The rebuilt desktop daemon and UI were restarted and remained running.
4. A controlled live toggle from NWS back to Open-Meteo, with METAR `fetchedAt` set just beyond the
   10-minute threshold, logged:
   `reason=source_or_location_change source=OPEN_METEO actualsProvider=METAR action=OBSERVATIONS`.
5. `OBS_REFRESH source=OPEN_METEO obs=13` completed about one second later, and the newest METAR
   `fetchedAt` advanced to the current time.
6. The final saved source is `OPEN_METEO`, its configured actuals provider is `METAR`, and the live
   temperature graph visibly labels the curve `Actual temperature data from METAR`.
