# One observation-persist rule: what a fetch's readings become in `observations`

Follows `261023-duplicate-logic-audit-different-names.md` → "Next" item 2 (B rest, after forecast
persistence — `261024-shared-forecast-persist-rules.md`, phases 1–3 done).

## Why
Every observation a fetch brings back ends in one write to `observations`, and each platform decides
around it itself — Android in seven places, desktop in five:

| Writer | Android | Desktop |
|---|---|---|
| Provider history (`HistoricalActualsBackfill`) from a forecast fetch | `HourlyForecastStore.saveHistoricalActuals` (via `ForecastFetchCoordinator.saveProviderActuals`) | `DesktopWeatherService.withHistoricalActuals` → `RawFetch.rawObservations` → `persistObservations` |
| NWS station rows, full window | `NwsObservationBackfiller` → `observationDao.insertAll` | `refresh` / `refreshObservationWindow` → `persistObservations` |
| NWS station rows, recent | `NwsCurrentObservationUpdater` → `insertAll` (+ `touchLatestFetchedAt`) | `refreshObservations` → `persistObservations` (touch in `DesktopNwsStationBundlesHost`) |
| Current conditions (Open-Meteo, Tomorrow.io, METAR…) | `CurrentTempRepository.insertCurrentObservation` / `storeTomorrowIoHistory` | `refreshObservations` → `persistObservations` |
| Synoptic (borrowed) | `SynopticObservationRefresher` → `insertAllRetaggingStationTypes` | borrowed recovery inside `refresh` → `persistObservations` |
| Deeper history on demand | — (Android has no on-demand history deepen) | `ensureHistory` → `upsertObservations` (Open-Meteo, NWS) |
| WeatherAPI prior day | `WeatherApiHistoryRun` Host (shared, contract) | same |
| Observations screen refresh | `WeatherObservationsActivity` → `insertAllRetaggingStationTypes` | Observations Refresh → `refreshObservations` |

The fetchers are mostly shared already (`NwsStationBundles`, `CurrentConditionsFetcher`,
`HistoricalActualsBackfill`, `TomorrowIoActuals`, `RetiredProductCleanup`, `PersonalStationThinning`);
what each write does around the insert is not.

## Divergences found (read 2026-10-24)
| # | | Android | Desktop | Effect today |
|---|---|---|---|---|
| O1 | Personal-station thinning (`PersonalStationThinning`, 10-min floor; OFFICIAL and each station's newest row untouched) | Synoptic refresher only | never | desktop holds 3,690 personal Synoptic rows (11 stations); the three fastest (F4751, G6550, G4110) ~720 rows each in 84 h ≈ one per 7 min. Android's NWS-path personal rows (NWS `stationType=2`) are not thinned either |
| O2 | Station-type re-tag after a write (a reclassification reaching stored history) | Synoptic refresher and Observations screen: every api written | Synoptic readings only | an NWS-api station reclassified (NWS `stationType=3` RAWS rows exist) keeps its stored rows on the old type on both, except via Android's Observations screen |
| O3 | Which history a forecast fetch files as actuals | `subHourly`, else `hourly` — **except Tomorrow.io: `subHourly` or nothing** (call-site guard) | Tomorrow.io: `withHistoricalActuals` applied to the history reply only (call-site structure) | same result today; the guard lives at two call sites, and `HistoricalActualsBackfill.build` would file Tomorrow.io's *forecast* hours as `TOMORROW_IO_5M_HISTORY` if either were changed |
| O4 | `TOMORROW_IO_FETCH`-style line (`TomorrowIoActuals.fetchLine`) | whenever Tomorrow.io is fetched (forecast: full lookback; current: incremental) | whenever the displayed source's actuals provider is Tomorrow.io, on any `persistObservations` call | desktop says nothing for a Tomorrow.io fetch while another source is displayed; Android always does |
| O5 | Row `fetchedAt` | the reading's own (`ObservationReading.fetchedAt`) | the persist call's `now` (`toEntity(now)`) | seconds apart in practice |
| O6 | Retired-product cleanup (`RetiredProductCleanup`) | after each fetched forecast source, and after Tomorrow.io current | after every write, per api written | none: only Tomorrow.io has a retired product, and both run after its writes |

Already the same: the readings themselves (shared fetchers), quantized keys, Tomorrow.io's replacement
count (`TomorrowIoActuals.replacements`), the NWS latest-row touch (`NwsStationBundles.Host.touchLatest`).

## Decisions needed (recommendation first)
- **O1 — thin personal stations on every observation write, both platforms.** Same rule Android
  already applies to Synoptic, by station type (never by interval — KSJC is OFFICIAL at 4.6 min).
  Desktop's table loses roughly a third of its personal rows; the blend weights them 0.05.
  Alternative: Synoptic only, desktop gains it (Android's scope).
- **O2 — re-tag station types after every write, every api.** One `UPDATE … WHERE stationId IN` per
  (api, type) written; makes a reclassification reach stored rows whichever path fetched it.
  Alternative: Synoptic only (desktop's).
- **O3 — move the Tomorrow.io guard into the shared input rule** (`HistoricalActualsBackfill`
  refuses Tomorrow.io rows that are not its five-minute product). No behaviour change; one place.
- **O4 — Android's: log whenever Tomorrow.io readings are written**, whatever is displayed.
- **O5 — the reading's own `fetchedAt`, persist time only when 0** (as the hourly write now does).
- **O6 — no decision**: moves into the shared rule as "after a write, per api written".

## Design
- `:shared` `ObservationWriteRun` + `Host`: `write(readings, context)` → thin (O1) → insert → re-tag
  (O2) → Tomorrow.io replacements + line (O4) → retired-product cleanup (O6). Host: existing keys in a
  range, insert, re-tag, cleanup queries, log.
- Android: every `observationDao.insertAll` site above goes through it (`RoomObservationWriteHost`);
  `insertAllRetaggingStationTypes` and `storeTomorrowIoHistory` go. Desktop: `persistObservations`
  and the two `ensureHistory` upserts become the Host call (`DesktopObservationWriteHost`).
- `ObservationWriteHostContract` per the Tier 3 pattern (`HostContractCoverageTest` forces it).
- Not in this plan: the fetch registry / `refresh()` orchestration (last B item); Android has no
  on-demand history deepen (desktop `ensureHistory`) — a feature gap, not a twin.

## Phases
1. `ObservationWriteRun` + both Hosts + contract; desktop `persistObservations` and Android's NWS,
   current-conditions and provider-history writers through it (O1, O2, O4–O6).
2. O3 (input rule), Synoptic refresher and Observations screen through the run; delete the old helpers.

Each phase: tests that fail on the old code for each decided divergence, full suite incl.
`:app:compileDebugAndroidTestKotlin`, install on the phones, restart desktop, check the log lines and
a day's rows on a device, Progress line in the audit plan.

## Verification on devices
- Desktop: personal Synoptic rows per station-hour ≤ 6 after a day (O1):
  `select stationId, count(*)/((max(timestamp)-min(timestamp))/3600000.0) from observations
   where api='SYNOPTIC' and stationType=2 and timestamp > strftime('%s','now','-1 day')*1000 group by 1`.
- Both: the Tomorrow.io line appears on a Tomorrow.io fetch with another source displayed (O4).
- The blend's actual line for yesterday unchanged within 0.1° (thinning keeps the newest row and is
  weighted 0.05).

## Progress
- 2026-10-25 — Phase 1 (approved O1–O5 as recommended). `:shared` `ObservationWriteRun` + Room/desktop
  Hosts + `ObservationWriteHostContract`. Through it: desktop `persistObservations` (refresh, deferred NWS
  window, `refreshObservations`, both `ensureHistory` upserts); Android NWS backfiller, NWS current
  cycle, current conditions (incl. Tomorrow.io), provider history (`saveHistoricalActuals`), and the
  METAR refresher (an eighth writer the table above missed). `ForecastRepository`'s per-source cleanup
  loop removed (O6 now per write).
  - **O1 changed from the plan's sketch**: Android's batch-only thinning leaks — every incremental fetch
    keeps its station's newest row, which then sits beside its bucket's keeper (Pixel 2026-10-25: F4751
    7.19 rows/h, G6550 7.16, G4110 7.09 against a 6/h floor; desktop unthinned at 8.6/h). The run thins
    the stored rows from 24 h before the batch onward, keyed on api + exact site + station.
  - Tests failing on the old code: `DesktopObservationWriteRulesTest` (O1, O2, O4, O5 — 4/4 failed on
    the old repository) and `NwsObservationWriteRulesIntegrationTest` (O1, O2 on Android's NWS cycle — 2/2
    failed on the old write); runner rules in `ObservationWriteRunTest`.
  - Phase 2 left: O3 (Tomorrow.io guard into `HistoricalActualsBackfill`), Synoptic refresher and
    Observations screen through the run, delete `insertAllRetaggingStationTypes`. Not in this plan:
    `DesktopWeatherApiHistoryHost.store` still upserts directly (its `WeatherApiHistoryRun` Host).
  - Device check 2026-10-25 11:52 (both phones + desktop on the phase 1 build): NWS cycles stored through
    the run (`stored=101/102`), no errors, no `OBS_WRITE` (nothing to re-tag or thin: NWS personal
    AW020 reports every 9–13 min). Yesterday's blend identical on every source (desktop and Pixel, ±0.00).
    O1 not yet visible on a device: the fast personal stations (Pixel F4751/G6550/G4110/G8106, 26–30
    extra rows/day each) are written only by `SynopticObservationRefresher` — phase 2.
- 2026-10-25 — Phase 2. O3: `HistoricalActualsBackfill.actualsInput` (sub-hour product, else hourly, never
  Tomorrow.io's forecast hours) replaces three call sites' guards — Android provider history, desktop
  `withHistoricalActuals`, and the shared `CurrentConditionsFetcher` (not in the table above); no behaviour
  change, Tomorrow.io's history call fills `subHourly` only. Synoptic refresher and the Observations screen
  write through the run; `insertAllRetaggingStationTypes` deleted. `SYNOPTIC_OBS_STORED` now reports
  `thinned=` (deleted over the stored window) and `retagged=`. `SynopticRefresherThinningTest` failed on
  the old batch-only write (`[0, 5, 10, 15]`). Device check of O1 on the Pixel pending its next full sync.
  - Device check 2026-10-25 12:24 (Pixel, phase 2 build): first Synoptic write `thinned=138` (the 24 h
    backlog of leaked rows), the next `thinned=40` (its own batch). No personal station above the floor in
    the last 24 h (was 26–30 extra rows/day each); F4751, G4110, G6550, G8106 at exactly 6.0 rows/h (was
    7.1–7.2). Yesterday's blend: unchanged except Silurian's low −0.03° (within the 0.1° bound). O1 done.

