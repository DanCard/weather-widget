# Location change refetches a fresh cached site (and twice on desktop)

User, 2026-11-21: "NWS refreshed on location change, even though cached copy was less than 2 hours old."

## Evidence (desktop DB, 08:55:46 Scottsdale → Mountain View)

1. `LOCATION_FETCH_PENDING action=cache_adopted`: Mountain View's cached graph was shown at once.
2. `LAUNCH_REFRESH_CHECK reason=source_or_location_change source=NWS action=FULL_FORECAST
   forecastAgeMs=5176481 forecastStaleAfterMs=900000`. The NWS Mountain View forecast was 86 min old.
   The daemon judged it against 15 min (`FORECAST_FRESHNESS_THRESHOLD_MS`), so it fetched.
3. In the same second, `FETCH_REQUEST start kind=FORECAST source=NWS origin=location_change`. The UI
   sends this unconditionally, so it is a second full fetch: two `FORECAST_LONG_FETCH` rows and two
   `REFRESH` rows at 08:55:47–50.
4. For comparison, 26 s earlier the source toggle Google → NWS judged NWS against 7 200 000 ms
   (2 h, `SourceToggleRefreshPolicy`) and fetched nothing.

## Root causes

A. **Desktop UI fetches on every picker move.** `DesktopUiApplication` (location-change
   `LaunchedEffect`) always sends `FetchRequest(FORECAST, …, "location_change")`. Its comment says
   why: the daemon's check "is gated on the SOURCE's last fetch, not the site's". That stopped being
   true with 7eea51e2b (per-site freshness), so this second fetch is now redundant.
B. **The daemon's location-change threshold is 15 min.** `launchForecastStaleAfterMs` gives
   `source_or_location_change` the `else` branch (`FORECAST_FRESHNESS_THRESHOLD_MS`), while a source
   toggle uses `SourceToggleRefreshPolicy.staleMs` (2 h at ≥ 70 % or charging, otherwise 4 h).
C. **Android does the same thing.** `LocationUpdater` calls `enqueueForceRefresh` for every
   user-chosen location change. A force refresh bypasses `ForecastFetchCoordinator.isStale`, so a
   fresh cached site is refetched there too.

## Proposed fix (both platforms, shared rule)

1. **Shared rule.** A user location change fetches the forecast only when the new site has nothing
   to draw, or when the displayed source's per-site forecast is older than
   `SourceToggleRefreshPolicy.staleMs(isHighBattery)`. These are the same limits as a source toggle:
   both are "the user asked to see this", so the battery-aware 2 h / 4 h applies. Observations
   still refresh when they are older than the usual observation freshness. The rule goes in
   `:shared` as `LocationChangeRefreshPolicy.action(cachePresent, lastForecastFetchMs,
   lastObservationFetchMs, nowMs, isHighBattery)`, which returns FULL / OBSERVATIONS / NONE and logs
   `LOCATION_CHANGE_REFRESH action=… forecastAgeMs=… staleAfterMs=…` (Claude proposal: a single
   policy rather than a second constant).
2. **Desktop:**
   - The UI's location-change effect calls the policy:
     - FULL → send FORECAST, as today;
     - OBSERVATIONS → send OBSERVATIONS;
     - NONE → no request, and the effect logs `cleared`.
     The interstitial path (no cache) is always FULL.
   - The daemon's `source_or_location_change` branch uses the same `staleMs`, so the daemon cannot
     start the duplicate fetch. `launchRefreshJobs.fetchOnce` already coalesces, but the UI request
     uses a different job.
   - Remove the stale comment.
3. **Android:**
   - For BANNER and INTERSTITIAL changes, keep the immediate repaint (banner or cached render).
   - Choose force vs. stale-gated work from the same policy: NONE or OBSERVATIONS enqueues the
     ordinary candidate refresh, which `isStale` gates per site; FULL keeps `enqueueForceRefresh`.
   - The banner must still clear when no fetch runs: the worker clears it on its `cache_adopted`
     path, which already exists (`WidgetPaintCoordinator.finishLocationChangeBanner`).
   - I will verify that path on the emulator before relying on it.
4. Manual Refresh and Settings → Refresh data are unchanged. Refresh still fetches at once.

## Tests

- Shared unit tests for `LocationChangeRefreshPolicy`:
  - 86 min old on high battery → OBSERVATIONS or NONE;
  - 3 h on high battery → FULL;
  - 3 h on low battery → not FULL;
  - no cache → FULL.
- Desktop integration (real `DesktopWeatherDao` + marker writer): a site fetched 86 min ago, then a
  picker move to it, sends no FORECAST request. The test uses a recording `FetchClient` seam, or the
  extracted decision function the effect calls. The same setup with a 3 h old marker sends exactly
  one FORECAST. This fails on current code, which sends one at 86 min.
- Daemon: `launchForecastStaleAfterMs("source_or_location_change", highBattery)` equals 2 h. This
  fails on current code (15 min).
- Android Robolectric: a location change to a site with a fresh per-site fetch enqueues the
  non-force request; a stale one enqueues the force request.

## Verification

- **Desktop:** switch Mountain View ↔ Scottsdale. The site fetched within 2 h logs
  `LOCATION_CHANGE_REFRESH action=NONE` (or OBSERVATIONS) and no `FORECAST_LONG_FETCH`. The graph
  appears at once without a banner. A stale site fetches exactly once.
- **Emulator:** run the same check through the location picker. The banner clears.
- End-of-task gate.

## Docs

`arch/forecast-and-observation-cadence.md`: the paragraph listing "Manual Refresh, source switches,
location changes…" and the per-site freshness paragraph. State that a location change uses the
source-toggle limit, and link to the policy instead of repeating the numbers.

## As implemented (differences from the proposal)

- The policy decides only the forecast (`LocationChangeRefreshPolicy.decide` → `fetchForecast`).
  Observations stay with each platform's existing check.
- **Android: no change.** After adopting the cache the sync already ran unforced, and each source
  fetched only when its own cadence was due. A forced sync past the limit was tried and dropped
  (user, 2026-11-21: "Why every source? Just delete this mechanism, and let the stale fetching code
  take care of it"). An untargeted forced sync refetches every visible source, Google included.
  Then, at the user's request, the no-cache case too: a location-change sync is never forced
  (`FullSyncPipeline`: `forceFetch = forceRefresh && locationChangePlace == null`). A site with nothing
  drawable has no hourly fetched there within 24 h, the longest cadence, so it is still fetched on
  that run.
- **Desktop, one fetcher (user, 2026-11-21: "Why wait? Can we do something simpler?", option 2):**
  - The window no longer sends a location-change fetch. The daemon's `.config-changed` launch
    refresh is the only one: `locationChangeLaunchAction` applies the policy, judging "drawable"
    exactly as the window does, and passes `userLocationChange` for the Synoptic backoff bypass.
  - The daemon's status never reached the window (it goes only to the panel), so the daemon records
    `LOCATION_REFRESH_RESULT site=… ok=…` and pushes an `L` byte (`DesktopLocationRefreshResult`).
  - A window that shows the interstitial or the banner waits up to 150 s for a result for its site
    recorded since the pick. It clears on success; on failure or no answer it shows the failure.
  - A first attempt, where the window's request joined the daemon's in-flight fetch
    (`fetchOnceOrJoin`), was dropped as more complex.
