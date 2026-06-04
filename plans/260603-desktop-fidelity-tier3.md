# Tier 3 — Desktop Fidelity: Interpolation, Station Quality, Graph Overlays

> Builds on Tier 1 (persistence) and Tier 2 (accuracy tracking). This tier is "fidelity/polish":
> make the current temp smooth, make the observation data trustworthy, and bring the graphs up to
> the Android widget's visual richness (including the actual-vs-forecast overlay deferred from Tier 2).

## Context

Tier 2 verification exposed where desktop fidelity lags Android:
- **Current temp is a step value** — `DesktopWeatherService` uses `obs ?? hourly[0]`; Android
  interpolates between surrounding hourly points so the tray/popup/genmon number moves smoothly.
- **Station selection is naive** — `stations.firstOrNull()` picked the *personal* CWOP station
  `AW020`. It happened to have data, but official `K`-stations (KNUQ/KPAO) are more reliable and are
  what the accuracy actuals should be built from. `NwsApi.classifyStationType`/`StationInfo.type`
  already exist and are ignored.
- **Graphs show forecast only** — no actual-vs-forecast overlay (deferred from Tier 2), no
  current-temp "now" marker, no cloud/precip shading.

## Workstreams (recommended order)

### A. Trustworthy observations — official-station preference + multi-station fallback  *(do first)*

Data quality underpins Tier 2's accuracy, so this is highest-value.

- **Prefer official stations.** Replace `stations.firstOrNull()` in
  `DesktopWeatherService.fetchNwsForecast` with an ordering that puts `StationType.OFFICIAL`
  (`classifyStationType` → K/P/T prefix) first, then by list order (NWS lists nearest-first).
- **Multi-station fallback.** Try up to ~5 stations in that order; for each, fetch the historical
  window and use the first that returns usable data (non-empty, temps present). Mirrors Android's
  multi-station fallback in the observation layer.
- **Cache the station list ~24h** to cut API calls (Android caches station lists for 24h). Store in a
  small `station_cache` table or reuse config dir; key by lat/lon.
- **Extract the ordering as a pure function** `orderStations(stations): List<StationInfo>` so it's
  unit-testable without network.
- Keep the `bestEffort` + `Log.i count` + `app_logs` REFRESH summary so station/coverage health stays
  visible. Remember [[nws-observations-fractional-seconds]]: truncate any new time-range params to
  whole seconds.
- *(Optional, defer if large)* inverse-distance blending across stations (Android `ObservationBlender`
  IDW). v1 can stay single-best-station.

### B. Smooth current temperature — port the interpolator

- Port `app/.../util/TemperatureInterpolator.getInterpolatedTemperature` to `:shared` (new
  `shared/.../util/` or `stats.desktop`), adapted to operate on `List<HourlyForecast>` + a target
  epoch (drop the Room `HourlyForecastEntity` and the `AppLogDao` debug dependency). Keep
  `getUpdatesPerHour` if we later want adaptive refresh cadence.
- Use it for the displayed current temp: prefer a **fresh observation** (latest obs within ~30 min),
  else **interpolate** between the surrounding hourly points at "now". Feeds the tray icon, popup
  header, and (later) the genmon script.
- Pure function → unit tests (two surrounding buckets → linear interp; before-first/after-last edges;
  empty list → null).

### C. Graph overlays — bring desktop graphs to parity

- **Inline actual-vs-forecast on the daily graph** (deferred from Tier 2). Plumb `daily_extremes`
  actuals through the repository (`loadCached`/a new `getActuals` read) into `DailyForecastGraph`
  (`daily: List<DailyForecast>` → add `actuals: Map<LocalDate, DailyActual>`). For past days, overlay
  the actual high/low (recommend the forecast-bar style: forecast bar + actual marker). One style,
  config-gated later via `DesktopConfig.accuracyDisplayMode`.
- **Current-temp "now" marker** on the hourly `TemperatureGraph` — vertical now-line + a dot at the
  interpolated current temp (from B).
- **Cloud-cover shading + precipitation** on the hourly graph — shade the area by `cloudCover` and
  draw precip (probability/amount) along the axis. Port the concepts from Android
  `app/.../widget/GraphRenderUtils.kt` (smoothing/bezier already mirrored in the desktop graph).
- *(Optional)* distinguish original-forecast vs revised hindcast using `hourly_forecast_history`
  (see [[hourly_forecast_line_is_hindcast]]) — likely its own follow-up.

## Files

- `desktop/.../DesktopWeatherService.kt` — station ordering + multi-station fallback (workstream A).
- `shared/.../util/TemperatureInterpolator.kt` *(new)* — ported interpolator (B).
- `desktop/.../DesktopWeatherService.kt` / `DesktopWeatherRepository.kt` — wire interpolated current
  temp (B).
- `desktop/.../DailyForecastGraph.kt`, `TemperatureGraph.kt` — overlays (C); popup passes actuals.
- `shared/.../data/local/desktop/DesktopWeatherDao.kt` — `getActuals`/extremes read for the popup;
  optional `station_cache` table + queries (A).

## Tests

- `orderStations` pure ordering (official-first, list-order tiebreak).
- Interpolator: midpoint, edges, single point, empty.
- Multi-station fallback selection over a fake per-station data map (pure selection function).
- Existing accuracy/DAO tests stay green.

## Verification

1. `./gradlew :shared:test :desktop:test` green (stop any running app first — `:desktop:test` and a
   live `:desktop:run` conflict on the tray singleton, see [[desktop_test_running_app_conflict]]).
2. `./gradlew :desktop:run`; confirm via `app_logs` REFRESH rows that observations now come from an
   official `K`-station and coverage is full; `observations.stationId` shows e.g. `KNUQ`/`KPAO`.
3. Popup: current temp ticks smoothly between hourly points; hourly graph shows the now-marker and
   cloud/precip shading; daily graph shows actual-vs-forecast on past days.

## Reuse map (Android → :shared/:desktop)

- `app/.../util/TemperatureInterpolator.kt` → `:shared` interpolator (drop Room/AppLogDao).
- `NwsApi.classifyStationType` / `StationInfo.type` → station ordering (already in `:shared`).
- `app/.../data/repository/ObservationRepository` multi-station fallback → desktop fetch loop.
- `app/.../util/ObservationBlender.kt` → optional IDW blend.
- `app/.../widget/GraphRenderUtils.kt` → hourly cloud/precip + now-indicator rendering.
