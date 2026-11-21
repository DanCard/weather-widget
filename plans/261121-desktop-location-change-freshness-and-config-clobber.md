# Desktop location change: site-blind freshness, and the picker save wiping window state

Not critical (user, 2026-11-21: "Google just updated on its own"). Two desktop-only defects found while
answering "Forecast comparison says Google hasn't updated in 9 hours, even though I requested Refresh all".

## Evidence (desktop DB + autostart-20261121-073810.log)

1. `07:43:10 CONFIG_SAVE source=location-picker` — the user moved Mountain View → Scottsdale (intended).
   `LOCATION_FETCH_PENDING action=cache_adopted` → `cleared`: last night's Scottsdale cache was adopted.
2. `07:43:10 [source_or_location_change] Launch refresh action: OBSERVATIONS … lastForecastFetch=1795274972265`
   — that timestamp is the NWS **Mountain View** fetch; at Scottsdale the forecast was ~10 h old.
3. `07:43:53 SETTINGS_REFRESH partition … skip_fresh=NWS(40s),GOOGLE_WEATHER(303s)` — 303 s is the age
   of the 07:38:48 Google fetch at **Mountain View**. Google's newest Scottsdale row: 2026-11-20 21:48.
4. Forecast Comparison loads `site=33.6452,-111.9113`, so "newest 9h ago" was true for Scottsdale.
5. config.json before vs after the picker save: `viewMode`, `windowX/Y/Width/Height`, `zoomFactor`,
   `obsWindowX/Y/Width/Height` (and any other non-settings field) are gone — reset to defaults.
6. The `settings` block survived: the console line is
   `CONFIG_SAVE source=location-picker merged-away-stale-settings: visibleSources: […] -> [NWS, OPEN_METEO, SILURIAN], apiKeys …`.
   That is the merge *discarding* the picker's default settings. Its `app_logs` row drops the
   `merged-away-stale-settings` label, so in the DB it reads exactly like a settings wipe.

## Root causes

A. **Site-blind freshness.** `DesktopWeatherDao.getLastSuccessfulFetch(source)` is the newest `REFRESH`
   log row for the source, from any site. 17 desktop call sites use it — Settings → Refresh data's
   partition, `UserRefreshThrottle`, the launch check (`runLaunchRefresh`), `DesktopViewingForecastHost`,
   wake/loop staleness, and `DataStatus.Live`. Android reads a per-site marker
   (`SettingsRefreshPartitionLoader.lastFetchMs` → `getLastLongFetch(source, lat, lon)`); desktop already
   writes the same per-site marker (`DesktopForecastHorizon`, `FORECAST_LONG_FETCH source=… site=…`)
   but its freshness checks do not read it.

B. **Picker builds a whole new config.** `LocationPickerWindowHost` sends
   `resolved.toConfig().copy(recentLocations = …)` — every field at its default. `resolveDesktopConfigSave`
   → `mergeNonSettingsSave` restores only `settings` from the persisted config and keeps the draft's
   defaults for everything else.

## Proposed fix

1. **Per-site freshness (A).** Add `DesktopWeatherDao.getLastSuccessfulFetch(source, lat, lon)`, which
   reads the newest `FORECAST_LONG_FETCH` row for `source=… site=<quantized>` (the same prefix as
   `DesktopForecastHorizon`). Put the prefix in one place and use it from both. Switch every call site
   that judges forecast freshness or throttling to it, passing the config's site. `DataStatus.Live`
   (the "updated X ago" status) uses it too, so the status describes the displayed site. A site with no
   marker returns null, so the check counts the source as stale and fetches it.
2. **The picker changes the location only (B).** In the `onResolved` path, apply
   `persisted.copy(lat, lon, label, recentLocations)` when a config exists; first launch keeps
   `toConfig()`. Make it explicit in `resolveDesktopConfigSave`: a `location-picker` save takes only
   location fields from the draft. Date and hourly offsets are kept as they are (Claude proposal: no
   reset rule, so the change stays minimal).

## Extra logging (what might be killing settings)

3. Persist the label: the `app_logs` rows become
   `source=… settings-fields-changed: …` and `source=… merged-away-stale-settings: …`, so the DB tells a
   real change from a correctly discarded stale draft.
4. **Top-level field diff on every save:** `CONFIG_SAVE source=… top-level-fields-changed: windowX: 44.0 -> null, …`
   (`DesktopConfig.topLevelDiffFrom`, settings block excluded). WARN when a non-null field becomes null
   or its default from a writer that does not own it; INFO otherwise. Window drags produce many saves,
   so the persisted row skips pure geometry drags from the owning window (`source=popup` moving
   `windowX/Y`) and logs those only to the console.
5. **External-writer check on load:** the daemon's and UI's `config loaded` path compares against the
   previous in-memory config and logs `CONFIG_LOAD_DIFF reader=daemon|ui fields=…` (WARN when fields
   vanish). This catches a writer that bypasses `saveConfigAndNotify` (another process, the daemon,
   a hand edit, or a crash mid-write).

## Tests

- `resolveDesktopConfigSave` unit tests: a `location-picker` save keeps `windowX/Y/W/H`, `zoomFactor`,
  `viewMode` and `obsWindow*`, and changes `lat/lon/label/recentLocations`. They fail on current code.
- Integration (real `DesktopWeatherDao`): store a `FORECAST_LONG_FETCH` marker for GOOGLE at site A
  only. Then `SettingsRefreshAllSources.partition` with the desktop per-site lookup at site B fetches
  GOOGLE, and at site A skips it. Fails on current code (skips at B).
- Integration: `determineLaunchRefreshAction` inputs taken from the DAO at site B with a fresh
  site-A fetch → `FULL_FORECAST`.
- Logging: a picker save over a full config produces no `top-level-fields-changed` WARN. A
  synthetic save from a default draft under a non-owner source produces one.

## Verification

Desktop: switch the location Scottsdale → Mountain View → Scottsdale. Each switch logs
`LAUNCH_REFRESH_CHECK … action=FULL_FORECAST` when that site's forecast is older than the cadence.
config.json keeps the window geometry, zoom and view mode. Settings → Refresh data right after a
switch fetches sources that are stale at the new site. End-of-task gate.

## Docs

`arch/forecast-and-observation-cadence.md` (refresh throttle / Settings refresh: freshness is per
site) and the desktop config-save section of `arch/ARCHITECTURE.md` or the doc that owns
`resolveDesktopConfigSave` (`rg merged-away arch/`).
