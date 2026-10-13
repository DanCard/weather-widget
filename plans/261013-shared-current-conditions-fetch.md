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

### Phase 2 (2026-10-13)

**Scope as built.** `:shared` `NwsStationLatestFetcher` covers the per-station step both platforms
ran:
- the API's latest report;
- the station's web rows (the Aviation Weather batch, filtered by `batchRows`, or desktop's
  Synoptic test path);
- `NwsObservationPlanner.mergeLatest`;
- the cloud carrier;
- the touch / failure outcome;
- one `OBS_WEB_API_DELTA` format: `apiTempF` / `webTempF`, where Android and desktop had each logged
  different fields.

It is the deferred "Phase 3d orchestration" of plans/260909-nws-fetch-unification.md. Desktop's
history window and web-history fallback stay desktop-side (that plan's 3e). They now read the
shared `webRows`.

- **Android:** `NwsObservationSource.fetchLatest` + `LatestStationObservation` are deleted; it went
  from 461 to 273 lines. Their old test-only Synoptic branch went with them (production always
  passed the parallel batch).
  - `NwsCurrentObservationUpdater` uses the shared step.
  - `fetchLatest`'s two tests moved to `:shared` `NwsStationLatestFetcherTest` (7 tests).
- **Desktop:** `ObservationBundle` now holds shared readings. Gone: the METAR →
  `NwsApi.Observation` → reading round trip, `toNwsObservation`, and the per-slot `latestIsWeb` /
  `historicalIsWeb` flags.
- **Fix found by the replay: a web row is filed at the NWS station's distance.** The Aviation
  Weather feed has its own station coordinates.
  - KPAO came out 5.0 km from Mountain View against NWS's 6.0 km. The Fold 4 already stored both
    (`KPAO isWebFallback=1`: 2 rows at 5.009, 77 at 6.039), so one station got two blend weights.
  - `asNwsWebRow` now sets the site location and the station's distance on both platforms.
- **Personal-station discount in the NWS current blend (user, 2026-10-13).**
  - `SpatialInterpolator.interpolateIDW` (behind `NwsBlend`, i.e. the NWS current value on both
    platforms) now takes the user's `personalStationWeight`. It applies the actual line's three
    rules: dropped at weight 0, official wins inside `NEAR_ZERO_KM`, otherwise the weight is
    multiplied.
  - Required parameter. Android passes `PersonalStationWeightProvider` (reader, updater); desktop
    passes `config.personalStationWeight()` at all 5 service constructions and in the repository.
    The UI's remembered service is now keyed on the weight.
  - Desktop already used the same IDW (`NwsBlend.build` calls `interpolateIDW`). Its remaining
    difference is the forecast fallback when no station is usable, which is unchanged.
- **Old-vs-new replay** (`ObservationFetchReplayHarnessTest`, inert without `REPLAY_DIR`): recorded
  14 real NWS + Aviation Weather responses at 15:1x, then replayed them through `HEAD` and through
  the new code.
  - 1097 rows each, identical except the two web-won rows (KNUQ, KPAO). Those now carry the NWS
    station name ("Mountain View, Moffett Field", the same as that station's 71 API rows) instead
    of the METAR feed's ("Moffett Fed Airfld, CA, US").
  - The current value moved 74.61 → 73.82 °F: the discount on AW020, a personal station.
- **Tests:**
  - `SpatialInterpolatorPersonalDiscountTest` (4).
  - Distance asserted in `NwsStationLatestFetcherTest`.
  - Existing blend tests pass `personalStationWeight = 1.0`, which keeps their original meaning.
- **Suites:** `:shared` 2181, app 2501 (2 tests moved to `:shared`), desktop 582 — 0 failures.
- **Live, both platforms on the new build:**
  - Desktop at 15:29:17 and the Fold 4 at 15:33:15 logged the same five `OBS_WEB_API_DELTA` lines
    in the shared format (same stations, tiers, timestamps and temperatures; KNUQ `chosen=web`
    with `deltaMin=20`, a KNUQ cloud carrier on desktop).
  - New web rows: KNUQ "Mountain View, Moffett Field" at 3.798 km (desktop) / 3.818 km (Fold).
    Each matches that platform's own KNUQ API-row distance (their sites differ by about 20 m).
  - Fold `NWS_IDW` 73.66 °F from 5 stations; `CURR_FETCH_SOURCE_RESULT NWS success=true`.
- **Replay harness kept** (inert without `REPLAY_DIR`) for Phase 3.

### Phase 3 plan (user, 2026-10-13: "phase 3 with Android's rule")

- **What exists.** Both platforms already share the cadence (`CurrentTempCadence`; primary only on
  battery) and the 30-min loop for the other sources (`NonPrimaryObservationPolicy`).
- **The gap is the charging run.** Android's charging loop calls `refreshCurrentTemperature(source = null)`.
  - That run fetches every visible source's feed **once**, deduplicated through
    `ActualsFeedPolicy.currentTempFeeds`.
  - Throttle: a feed whose best consumer is ranked 4th or lower and is not displayed is fetched at
    most hourly.
  - It has a 5-min per-location freshness gate; a force or an explicit source bypasses both the
    gate and the throttle.
  - It records per-feed success/failure and logs `CURR_FETCH_*`.
  - Desktop's loop 3b fetches only the displayed source's feed.
- **`:shared` `CurrentConditionsRun`** is that run, moved from `CurrentTempRepository` behind a
  `Host` interface. The host supplies:
  - visible order and displayed ids;
  - freshness / throttle stores;
  - the per-feed fetch-and-store;
  - success/failure recording;
  - the log.

  The run owns the mutex, the plan and the log lines (unchanged text).
- **Android:** `refreshCurrentTemperature` delegates to the run. No behaviour change.
- **Desktop:**
  - While charging, loop 3b's observation step runs the shared run over the visible sources, then
    finishes the displayed source's post-processing as today.
  - On battery it stays primary-only (`CurrentTempCadence`), as on Android.
  - Desktop gains `CURR_FETCH_START / SOURCE_RESULT / COMPLETE` rows.
- **Not changed:** loop 3d and Android's non-primary branch (both on `NonPrimaryObservationPolicy`).

### Phase 3 (2026-10-13)

- **`:shared` `CurrentConditionsRun`.** It holds the mutex, the freshness gate (5 min per site,
  bypassed by force), the plan (`currentTempFeeds`), the low-priority throttle (rank ≥ 3, not
  displayed, hourly; bypassed by force or a named source), and per-feed records. It logs
  `CURR_FETCH_MUTEX/FRESH_SKIP/THROTTLE_SKIP/START/SOURCE_RESULT/ERROR/COMPLETE` and
  `CURRENT_TEMP_STATUS`, in the same text as before.
  - Change: the orchestrator now rethrows a cancellation after logging it, where Android used to
    return `Result.failure`.
- **Android:** `CurrentTempRepository.refreshCurrentTemperature` delegates to it.
  - The host is `FetchMetadata`, `WidgetStateManager` and `fetchFromSource`.
  - Removed: `syncMutex`, the run's constants, the two log helpers and `extractHttpErrorDetail`.
  - Over the three phases the file went from 679 to about 250 lines.
- **Desktop:** `WeatherApiClient.fetchFeedObservations(feed)` fetches any feed.
  - `DesktopWeatherRepository.refreshObservations(visibleSources, reason)` runs the shared run.
    `visibleSources = null` means the displayed source alone, forced; that's every caller except
    the charging loop.
  - Station-network feeds (METAR / Synoptic) are still fetched beside the run for a source that
    borrows one.
  - A failure of the displayed source's feed is rethrown, so the daemon's failure banner works as
    before.
  - The stores are `DesktopCurrentConditionsMemory` (in memory, per daemon).
  - Loop 3b passes `config.effectiveSources` while charging (`reason=charging_loop`); on battery it
    is the displayed source only.
- **User correction, mid-phase: "for meteo the source is NWS".** `currentTempFeeds` used to list
  every source able to file its own actuals as its own feed, "to keep the loop fetching what it did
  before", even when the user had redirected it.
  - With Open-Meteo set to NWS actuals, nothing reads its own current: the series builder rejects
    its rows, the daily actuals drop them, and `MetarCloudBlender` reads the provider.
  - Fix (shared, both platforms): a source contributes only its provider (`feedFor`), so it is its
    own feed only when self-provided. NWS outside its coverage is no longer listed either.
  - The user's desktop sources (Google, Open-Meteo, Silurian, NWS; Open-Meteo and Google → NWS) now
    give one feed: NWS, fetched once for all four.
  - This was already wasting a request per cycle on Android: the Fold 4 logged
    `targets=NWS, OPEN_METEO`.
  - Quota: Google is never a feed (forecast-only), so the run spends no Google quota.
- **Tests:**
  - `CurrentConditionsRunTest` (7), including the user's exact settings.
  - `ActualsFeedPolicyTest` +2: redirected source not fetched; NWS outside coverage.
