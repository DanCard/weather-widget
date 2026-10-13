# Current-conditions fetching moves into `:shared`

## Problem

Android and desktop each have their own code to fetch current conditions: the current temperature
plus the recent observation rows behind the actual line. The API clients (`NwsApi`, `OpenMeteoApi`,
…) and several policies are already in `:shared` (`ActualsFeedPolicy`, `ObservationFallbackPolicy`,
`NwsObservationPlanner`, `NwsBlend`, `HistoricalActualsBackfill`). The code between them is written
twice: which feeds to fetch, what each API response becomes as rows, and the NWS station loop.

| | Android | Desktop |
|---|---|---|
| Orchestration | `CurrentTempRepository.refreshCurrentTemperature` (~220 lines): freshness gate, feed choice, rank throttle, per-source success/failure, `CURR_FETCH_*` logs | `DesktopWeatherService.fetchObservationsOnly` + repository `refreshObservations`; no freshness gate, no throttle, no `CURR_FETCH_*` logs |
| Per-feed fetchers | `CurrentTempRepository` (~350 lines) | `DesktopWeatherService` `fetch*ObservationsOnly` (~200 lines) |
| NWS stations | `NwsObservationSource` (461) + `NwsCurrentObservationUpdater` (259) | `fetchNwsObservations` / `fetchObservationBundles` (~300) |
| METAR / Synoptic | `MetarObservationSource`, `SynopticObservationSource` (187) | inline in `fetchStationNetworkObservations` |

About 1,500 Android lines and 600 desktop lines do the same job.

### The copies have already drifted

| Feed | Android | Desktop |
|---|---|---|
| Open-Meteo | 5 points (centre + N/S/E/W ~8–10 km) as `OPEN_METEO_MAIN` / `_0.._4`, for the IDW blend | centre only |
| OpenWeatherMap | 5 points, a full forecast call at each | centre only |
| Silurian, WeatherAPI | 5-point current fetch | no current-only path (`Skipping … no current-only desktop path`) |
| Google | centre `current` endpoint | no current-only path |
| Which feeds | every visible source's feed (`ActualsFeedPolicy.currentTempFeeds`), rank-throttled | displayed source's feed only (`feedFor`) |
| Station name | `"Meteo: Current"` | `"Open-Meteo Current"` |

Each row is a past or future "fixed on one side only" bug.

## Fix: three phases, each its own commit

### Phase 1 — `:shared` `CurrentConditionsFetcher`: one fetcher per feed

```kotlin
class CurrentConditionsFetcher(apis: CurrentConditionsApis, stations: NwsStationObservationFetcher, log: FetchLog) {
    suspend fun fetch(feed: WeatherSource, lat: Double, lon: Double, window: ObservationWindow): CurrentConditions
}
data class CurrentConditions(
    val readings: List<ObservationReading>,  // rows to store
    val current: CurrentReading?,            // the source's own current product (header), if any
    val notes: List<FetchNote>,              // log lines the platform writes
)
```

- Pure fetch-and-map. It does not store anything. Each platform writes `readings` to its own store:
  Android through a new `ObservationReading.toEntity()` (the reverse of the existing `toReading()`),
  desktop through `weatherDao`.
- One implementation per feed: NWS, Open-Meteo, OWM / Silurian / WeatherAPI (the shared
  forecast-current helper), Google, Tomorrow.io (5-minute history via `HistoricalActualsBackfill`),
  METAR, Synoptic.
- Android's `fetchFromSource` and desktop's `fetchFeedObservations` both shrink to a call to it.
- The drifted rows above become one behaviour each (decisions below).

### Phase 2 — NWS stations into `:shared` `NwsStationObservationFetcher`

- One station loop: latest plus the recent/historical window per station, the parallel Aviation
  Weather batch, the web-fallback merge through `NwsObservationPlanner`, and the cloud-carrier row.
- Interfaces for the parts that really differ by platform:
  - `StationListCache` (Android `FetchMetadata` prefs; desktop's own cache);
  - `SynopticGate` (both already have one);
  - `FetchLog` (`OBS_WEB_API_DELTA` and similar).
- `NwsObservationBackfiller` (deep history) stays separate. It's a different job, and folding it in
  would double this change.

### Phase 3 — orchestration into `:shared` `CurrentConditionsRun`

- One run:
  - freshness gate, per location;
  - feed choice (`ActualsFeedPolicy.currentTempFeeds`);
  - the low-priority throttle;
  - per-feed success/failure, written through an interface: Android `WidgetStateManager.record*`,
    desktop the failure pill;
  - the `CURR_FETCH_START / SOURCE_RESULT / COMPLETE / ERROR` log rows.
- Desktop gains the same log rows, so a "refresh did nothing" report reads the same on both
  platforms.

### Guards

As with `UserRefreshScopeGuardTest`, a source-scanning test on each platform: the current-product
calls (`getCurrent(`, `getFiveMinuteHistory(`, the station-observation endpoints) appear only in
`:shared` fetchers, never in `app/` or `desktop/` code.

## Decisions (user, 2026-10-13)

1. **Sample the centre point only when a feed's actuals come only from virtual stations** — model
   output at a point, not a real station network. Open-Meteo, OWM, Silurian, WeatherAPI and Google
   current are all virtual, so they are centre-only on both platforms.
   - Android stops fetching the four offset points (`<SOURCE>_0.._4`): 4 fewer requests per such feed
     per refresh.
   - Desktop's centre-only behaviour, which already draws these sources' actual lines, becomes the
     rule.
   - Rows already stored at the offsets age out of the blend after 3 h (IDW decay) and out of the
     table after 10 days.
   - NWS, METAR and Synoptic are real station networks and have no offset concept.
2. **Which feeds to fetch: decided in Phase 3** with the orchestration. Phase 1 shares the per-feed
   fetchers only.
3. **Station names: Android's** (`"Meteo: Current"`, `"OWM: Current"`, …).
4. **Scope: Phase 1, then check in.** NWS stays per-platform until Phase 2.

## Tests

- **Phase 1:**
  - A `:shared` contract test per feed, with fake API clients: rows, station ids, POI offsets,
    `*_MAIN` naming, cloud bands, error propagation (`ApiAccessException` rethrown, other errors
    → no reading).
  - Existing app and desktop tests that cover these paths keep passing. They now run through the
    shared code.
- **Phase 2:**
  - Port the existing NWS station tests (web-fallback merge, metrics tier, cloud carrier,
    historical window) to the shared fetcher, so they run once.
  - Then a regression replay: one recorded station response set gives identical rows from the old
    and the new path, compared before the old path is deleted.
- **Phase 3:**
  - Orchestrator test: freshness skip, throttle, forced bypass, failure recording.
  - Desktop gets `CURR_FETCH_*` rows.
- **Every phase:** the full `:shared` / app / desktop suites, then a check on the Fold 4 and the
  desktop. Refresh NWS + Open-Meteo, compare the new rows and log lines with today's, and restart
  the desktop app.

## Risk

- Phase 2 moves the most-debugged code in the app: NWS web fallback, QC and cloud carriers. That
  is why the replay test is compared before the old path is deleted.
- Each phase leaves both platforms working and is committed separately, so stopping after Phase 1
  still pays off.

## Verification

### Phase 1 (2026-10-13)

- **`:shared` `CurrentConditionsFetcher`:** Open-Meteo, WeatherAPI, OpenWeatherMap and Tomorrow.io;
  centre point only; Android's names; `OFFICIAL` station type.
  - The clients are passed in as functions, so tests need no HTTP.
  - `CurrentConditionsFetcherTest` (6 tests).
- **Android:** `CurrentTempRepository` went from 679 to 418 lines.
  - Removed: the 5-point sampler, the Silurian and Google current fetchers (forecast-only, never a
    feed: 72 h of `CURR_FETCH_SOURCE_RESULT` on 3 devices shows only NWS and OPEN_METEO), and
    `checkAndRethrowFailure` (dead: no client sets `expectSuccess`, and 429/401 come from
    `require2xx`).
  - Constructor no longer takes `silurianApi` / `googleWeatherApi`; 11 test call sites updated.
  - Errors as before: non-`ApiAccessException` failures → no reading.
  - Tomorrow.io's batch store, replacement count and retired-product cleanup stay on Android.
- **Android converter:** one public `ObservationReading.toEntity()` (quantized) replaces the private
  copies in `MetarObservationSource` and `SynopticObservationSource`.
- **Desktop:** `fetchOpenMeteo/TomorrowIo/OpenWeatherMapObservationsOnly` are replaced by one
  `fetchCurrentConditions`.
  - WeatherAPI gains an observations-only path; it had none.
  - The Open-Meteo row is renamed "Meteo: Current", and OWM rows are now `OFFICIAL`.
- **Guards:** `:shared` `CurrentConditionsCallSites`, plus `CurrentConditionsCallSiteGuardTest` on
  Android and desktop.
  - Only each full forecast refresh's Tomorrow.io history call is allowed (1 per file).
  - The rule itself is unit-tested (`CurrentConditionsCallSitesTest`).
- **Suites:** app 2503 and desktop 581 (461 / 39 / 81) pass; `:shared` 2170 with 1 failure.
  - The failure: `GoogleWeatherApiTest` "parses ten daily rows…" threw a
    `ConcurrentModificationException`, then passed 3 of 3 reruns. That class is untouched by this
    change, so it's a pre-existing flake, not yet diagnosed.
- **Fold 4:** the charging loop at 14:56:05 wrote `OPEN_METEO_MAIN` only ("Meteo: Current", 72.8,
  provider bands, `CURR_FETCH_COMPLETE successCount=2`). The 14:30 fetch from the old build had
  written five rows (`_MAIN` + North/South/East/West).
- **Desktop:** rebuilt and restarted. Desktop displays NWS, so its Open-Meteo path has not run live
  yet; the shared contract test covers it.
