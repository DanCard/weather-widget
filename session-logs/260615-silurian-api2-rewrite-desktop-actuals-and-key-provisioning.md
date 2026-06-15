# Silurian API² rewrite + desktop actuals backfill, key provisioning, non-silent fallback

## Summary
Started as "Open-Meteo missing actuals on the desktop hourly graph" and unwound into a stack of three
independent failures hiding behind one symptom for the Silurian source:

1. **Desktop never backfilled non-NWS actuals** — the pink "actual" line is built only from
   observation rows whose `api` matches the displayed source. NWS supplies real station obs; every
   other source returned `rawObservations = emptyList()`, so no actual line. (Open-Meteo fix first,
   then generalized to all non-NWS sources.)
2. **Desktop had no API-key path** — keys came only from `config.json` (desktop Settings), which was
   empty, so keyed sources (Silurian/WeatherAPI/OWM/Visual Crossing/Tomorrow.io) silently failed.
3. **Silurian's API was fully retired** — `api.silurian.ai` is NXDOMAIN; the provider moved to a
   rewritten "API²". So Silurian had been failing for ages, and desktop's silent `getOrElse` served
   Open-Meteo data *relabeled as Silurian* — masking it completely.

All fixed and verified: desktop now backfills actuals for all non-NWS sources; keys are baked in at
build time (parity with Android `BuildConfig`); the silent fallback is gone; and `SilurianApi.kt` is
rewritten for API² and confirmed returning real Silurian data end-to-end.

## Prompts (verbatim, in order)
1. `Open meteo missing temperature actuals on hourly graph desktop`
2. (plan approved)
3. `Silurian missing temperature actuals on hourly graph desktop`
4. `What , that sounds crazy, siluran has no api key?  fall back to open-meteo?  That should not happen.`
5. `Where does android get the api key from?`
6. `both`  (→ implement key provisioning AND non-silent fallback)
7. `Does android have fallback api?  That should never happen`
8. `Yes, rewrite SilurianApi.kt for the new API²`
9. `new silurian api key: <KEY>`  (key masked in this log)
10. `write session log to session-logs/ dir`

## What was built

### 1. Shared actuals backfill (Open-Meteo fix, then generalized)
- New `shared/.../actuals/HistoricalActualsBackfill.kt` — re-files the past slice of a source's
  hourly list as `ObservationReading`s (`stationId="${src}_MAIN"`, `api=src`), with measured precip
  kept only when `WeatherSource.providesHistoricalActuals`. Pure function (+ unit test).
- `DesktopWeatherService`: `withHistoricalActuals(result, sourceId)` applied to **all** non-NWS
  sources; `fetchOpenMeteoForecastWithActuals()` additionally fetches `past_days=7`. Labeled with the
  **display source** so it stays matched to the (display-labeled) forecast even on fallback.
- Android `ForecastRepository.saveHistoricalActuals` refactored to delegate to the shared helper
  (kills platform drift).

### 2. Desktop API-key provisioning (parity with Android)
- `desktop/build.gradle.kts`: reads `local.properties` / env and generates `DesktopApiKeys.kt`
  (`DEFAULTS: Map<id,key>`) into `build/generated/apikeys/`, wired via
  `compileKotlin.dependsOn(generateDesktopApiKeys)`.
- `DesktopWeatherService.effectiveKeys = DesktopApiKeys.DEFAULTS + config.apiKeys.filterValues{notBlank}`
  — Settings key overrides the baked key; keys never written back to config.json.

### 3. Non-silent fallback
- `fetchForecast().getOrElse` now: **only** NWS→Open-Meteo (logged `SOURCE_FALLBACK`, kept for ex-US
  coverage); every other selected source rethrows on failure (logged `SOURCE_ERROR` + "no API key"
  hint) so the refresh loop surfaces `REFRESH_FAIL`/`DataStatus` instead of fake data.
- Confirmed Android never had this bug: `safeFetch` returns null per source, no cross-source swap.

### 4. Silurian API² rewrite (`shared/.../SilurianApi.kt`)
Old `api.silurian.ai/v1/forecast` (single endpoint, Bearer) → new:
- Host `https://earth.weather.silurian.ai/api/v1` (docs say `beta`, but prod keys 401 on beta, 200 on earth).
- Auth `X-API-KEY` header.
- Two concurrent calls: `/forecast/hourly?units=imperial&timezone=local&include_past=true` +
  `/forecast/daily`. `include_past` reaches back to the model-run time (~hours) → short but real
  actual line.
- `units=imperial` (°F native; `precipitation_accumulation` is inches → ×25.4 mm).
- Timestamps: hourly naive-local ISO + separate `utc_offset` (robust 3-tier parse); daily `yyyy-MM-dd`.
- `weather_code` enum (clear-day/-night, partly-cloudy-day/-night, cloudy, rain, snow) → condition.
- No `current` endpoint (current temp interpolated from hourly upstream).
- `SilurianApiTest.kt` rewritten: MockEngine for both endpoints, asserts X-API-KEY/imperial/include_past
  + parsing (note: used `Collections.synchronizedList` to capture concurrent requests).

## Files changed
- NEW `shared/src/main/kotlin/com/weatherwidget/shared/actuals/HistoricalActualsBackfill.kt`
- NEW `shared/src/test/kotlin/com/weatherwidget/shared/actuals/HistoricalActualsBackfillTest.kt`
- `shared/src/main/kotlin/com/weatherwidget/data/remote/SilurianApi.kt`  (API² rewrite)
- `desktop/build.gradle.kts`  (key generation)
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt`  (backfill + effectiveKeys + non-silent fallback)
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`  (delegate to shared backfill)
- `app/src/test/java/com/weatherwidget/data/remote/SilurianApiTest.kt`  (API² test)
- `local.properties`  (new Silurian key — gitignored)

## Verification
- Unit: `HistoricalActualsBackfillTest`, `SilurianApiTest` pass; `:shared`/`:desktop`/`:app` build green.
- Open-Meteo desktop: `OPEN_METEO_MAIN` obs 3→194 over ~7 days to now; pink actual line renders (screenshot).
- Silurian endpoint discovered live via `/api/v1/openapi.json` on the earth host; old key + the new
  key both 401 on `beta`; new key 200 on `earth` (real data).
- Silurian desktop end-to-end (new key baked in): fresh refresh `hourly=361 daily=15 obs=8`; hourly
  temps **differ** from Open-Meteo on every hour (no longer fallback); no `SOURCE_FALLBACK`/`SOURCE_ERROR`.

## Housekeeping / loose ends
- Desktop settings restored to original **OPEN_METEO / HOURLY**; app healthy (2 procs).
- ~192 stale `SILURIAN_MAIN` obs remain from fallback-era testing (Open-Meteo data labeled Silurian);
  they age out in 30 days. A targeted DELETE was (correctly) blocked by the no-clear-app-data guard.
- All code changes uncommitted at session end (commit was offered, not yet done).
- Learned: the running desktop app continuously rewrites `config.json` (and `encodeDefaults=false`
  drops default-valued fields) — only edit config with the app fully stopped.

## Memory
Wrote: `silurian_api2_migration`, `desktop_api_key_provisioning`, `desktop_openmeteo_actuals_backfill`,
`desktop_force_full_refresh_for_test`, `desktop_config_write_races` (+ MEMORY.md index entries).
