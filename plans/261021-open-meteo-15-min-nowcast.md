# Open-Meteo 15-minute rain nowcast (phase 1)

## Decision (user, 2026-10-21)
- Start nowcasting with **only what Open-Meteo gives us**; radar extrapolation and other sources
  later (survey: `notes/261021-free-radar-nowcast-sources.md`).
- Ukraine and Europe matter, not only the US.
- **No battery-policy change in phase 1.** "Rain-armed" screen-off polling is deferred: Open-Meteo's
  15-min models update hourly to 3-hourly, so polling faster than that returns the same numbers.

## What we already have
- Every Open-Meteo forecast fetch asks `minutely_15=temperature_2m,precipitation,weather_code,cloud…`
  (`OpenMeteoApi.kt:172`), but `parseSubHourlyHistory` drops every slot after `current.time`: the
  future 15-min precipitation is downloaded and thrown away.
- `RainAnalyzer` deliberately ignores rain **< 2 h away** (`IMMINENT_RAIN_HOURS`): the hourly
  summary has nothing to say about the next two hours. That is the gap this fills.
- Open-Meteo rain map loop (`plans/261020-open-meteo-rain-map-loop.md`): same model, hourly grid,
  disk-cached `RainGridRepository`. That cache pattern is reused here.

## Measured (2026-10-21)
- `forecast_minutely_15=N` works and starts at the current hour; `precipitation_probability` is
  accepted in `minutely_15`.
- Native 15-min coverage, `models=icon_d2,ncep_hrrr_conus,meteofrance_arome_france_hd,
  knmi_harmonie_arome_europe,dmi_harmonie_arome_europe`:
  **Kyiv → `{"error":true,"reason":"No data is available for this location"}`**; Berlin, Paris →
  four suffixed series; Rome → three; SF → one **unsuffixed** `precipitation` (a single-model hit
  is not suffixed; the parser must accept both forms).

## Design

### 1. Data (`:shared`)
- **`OpenMeteoApi.getNowcast(lat, lon)`**: `minutely_15=precipitation,precipitation_probability`,
  `forecast_minutely_15=12` (3 h), `past_minutely_15=1`, `forecast_days=1`, no daily/hourly,
  no `models=` (Open-Meteo's automatic choice, the same model as the graph and the rain map), same
  centre coordinate as the forecast fetch. ~1 KB. `api_usage_stats` endpoint `nowcast`.
- **Keep the full fetch's future slots too**: a parse of the same response
  (`parseMinutelyNowcast`), so every normal Open-Meteo fetch refreshes the nowcast at no extra
  cost. `parseSubHourlyHistory` is unchanged.
- **`NowcastSeries`**: slot ends (epoch ms), mm per slot, chance %, `fetchedAtMs`, `native: Boolean`.
  Open-Meteo's 15-min precipitation is the **sum over the preceding 15 min**, so the slot labelled
  14:15 is rain from 14:00 to 14:15; onset is the slot's *start*. A fixture test pins that.
- **Storage: disk cache only, no Room/desktop schema change**: one small JSON per site, like
  `RainGridRepository`. Read-through, reused while < 15 min old, discarded after 3 h. The data
  expires within an hour, so a table would only hold dead rows.

### 2. Coverage (`:shared` `NowcastCoverage`)
- Learned, not a hardcoded map (per "no hardcoded provider limits"): a probe with the native-model
  list above (a request-formation constant). Any non-null series → `native`; the
  "No data is available" error or all-null → `interpolated`. Cached per site **30 days**, then
  re-probed, so a model domain that grows (or shrinks) is picked up. Probe failure → unknown →
  treated as interpolated (claims nothing it can't back).
- `native` → minute-level wording allowed. `interpolated` (Kyiv) → **no nowcast line**; the
  existing hourly views are what Open-Meteo actually knows there.

### 3. What it says (`:shared` `NowcastSummary`, pure)
- Rain slot = ≥ 0.1 mm per 15 min (0.4 mm/h; drizzle shows, model noise doesn't). One constant.
- Over the next 2 h, one of:
  - dry now → rain within 2 h: **"Rain from 14:15"** (locale 12/24 h, the slot's start)
  - raining now → stops within 2 h: **"Rain until 15:30"**
  - raining now → continues: **"Rain for 2+ h"**
  - dry throughout, or `interpolated`: nothing (no "no rain" line: absence is the default).
- Hysteresis against flicker between refreshes: one isolated wet slot between dry ones doesn't
  start a window; a single dry slot inside rain doesn't end it.
- Times, not "in 12 min": the data has 15-min steps and the widget repaints on its own cadence, so
  a countdown would be wrong between paints.

### 4. Display (both platforms)
- **Daily view, header**: beside the header rain % (`PrecipProbabilityCalculator
  .resolveHeaderPrecipitation`; Android `DailyHeaderBinder`, desktop `DesktopHeaderPrecipitation`),
  the summary text in the %'s blue. Hidden at 1×1 and 1-row sizes.
- **Hourly precip view** (Android `PrecipitationGraphRenderer`, desktop `PrecipitationGraph`): the
  next 2 h drawn from the 15-min amounts as a stepped blue band under the hourly line, a thin seam
  where the hourly data takes over. Only when `native`.
- **Desktop genmon panel**: `☂ 14:15` / `☂ →15:30` appended to the served markup when a summary
  exists (computed in the refresh, never on `PanelIpcServer`'s accept path).
- **Shown only while Open-Meteo is the displayed source**, matching the rain map loop decision
  (map/graph from the same model) and "no cross-source fallback". See open question 1.

### 5. Refresh (no new wakeups)
- `:shared` `NowcastRefreshPolicy.due(...)`: when a view is painted or the screen comes on, the
  nowcast is refetched if **all** hold:
  - Open-Meteo displayed and coverage `native`;
  - cached nowcast ≥ 15 min old;
  - rain plausible: any stored hourly PoP ≥ 30 % in the next 3 h (any source), or the cached
    nowcast has rain in its window, or the newest observation reports precipitation.
- Android: rides the existing screen-on catch-up and paint paths (`ViewingRefreshPolicy` callers);
  no alarm or job is added. Desktop: the observation loop's tick plus unblank / panel open.
- Dry hours cost nothing beyond the normal forecast fetch, which refreshes the nowcast anyway.
- Logs: `NOWCAST_FETCH reason=… slots=… native=…`, `NOWCAST_COVERAGE site=… native=… models=…`,
  `NOWCAST_SKIP reason=dry|fresh|interpolated|not_displayed`, `NOWCAST_SUMMARY text=…`.

### 6. Scoring (log-only, phase 1b)
- On each fetch, log what the previous nowcast said for the slots now past (onset slot, wet/dry
  per slot) against the observation feed's precipitation flags (METAR `RA`/`DZ`/`SH`, NWS precip)
  for the same 15 min: `NOWCAST_CHECK hits=… misses=… falseAlarms=… onsetErrMin=…`. No table yet.
  This is what decides phase 2 (Tomorrow.io `1m` / radar extrapolation / rain-armed polling).

## Tests
| # | Kind | What |
|---|---|---|
| 1 | Unit (:shared) | `getNowcast` request: params above, no `models=`, centre = forecast coordinate (MockEngine) |
| 2 | Unit | `parseMinutelyNowcast` from a full forecast fixture keeps slots after `current.time`; slot 14:15 → window 14:00–14:15 |
| 3 | Unit | `NowcastCoverage` parse: suffixed multi-model, unsuffixed single-model (SF shape), "No data is available" error (Kyiv shape), all-null |
| 4 | Unit | Coverage cache: re-probe after 30 days, not before; probe failure → interpolated |
| 5 | Unit | `NowcastSummary`: from / until / 2+ h / dry; 0.1 mm threshold; hysteresis cases (lone wet slot, lone dry gap); 12 h vs 24 h locale |
| 6 | Unit | `NowcastRefreshPolicy.due`: each gate alone blocks; all pass → due |
| 7 | Unit | Disk cache: < 15 min reused, > 3 h discarded |
| 8 | Robolectric | Android daily header shows "Rain from 14:15" with Open-Meteo displayed + native fixture; hidden for NWS displayed, for interpolated coverage, and at 1×1 |
| 9 | Unit (desktop) | Desktop header + genmon markup carry the summary; none when interpolated |
| 10 | Integration (2+ classes) | Fetch fixture → cache → summary → header text, both platforms' resolvers produce the same string |
| 11 | Unit | `api_usage_stats` row for endpoint `nowcast` |

Each summary/parse test must fail on today's code (future slots dropped, no summary).

## Manual verification
- Pixel + desktop at a central-European location with rain in the next 2 h (pick from a live
  Open-Meteo map): header line appears, hourly precip view shows the 15-min band; it matches
  `curl …&minutely_15=precipitation&forecast_minutely_15=12`.
- Kyiv: no nowcast line, `NOWCAST_COVERAGE … native=false` logged once, no repeated probes.
- Dry day: `NOWCAST_SKIP reason=dry`, no `nowcast` rows in `api_usage_stats` beyond the probe.

## Open questions
1. **Other displayed sources**: show the Open-Meteo nowcast line under NWS / Google too, labelled
   "Open-Meteo"? Recommended **no** for phase 1 (rain-map precedent, no cross-source fallback).
2. **Ukraine**: phase 1 shows nothing there. Acceptable, or show an *hourly* "Rain around 14:00"
   inside 2 h (would relax `RainAnalyzer`'s 2 h rule for all regions)?

## Later phases (not in this plan)
- Phase 2: Tomorrow.io `1m` as a second nowcast (global, incl. Ukraine), and/or our own radar
  extrapolation from OPERA (Europe) / MRMS (US); blend to Open-Meteo 15-min over 45–120 min.
- Phase 3: "rain-armed" cadence (10-min screen-off polling only when hourly PoP ≥ 30 % in the
  next 3 h, battery-gated) and opt-in rain-start notifications; belongs in `performance/`.

## Resolved (user, 2026-10-21: "go with your recommendations")
1. Nowcast shown **only while Open-Meteo is displayed**.
2. **Ukraine shows nothing** in phase 1; `RainAnalyzer`'s 2 h rule is unchanged.

## As built (2026-10-21)
- `:shared` `com.weatherwidget.shared.nowcast`: `NowcastSeries`/`NowcastParser`, `NowcastCoverage`,
  `NowcastSummary`, `NowcastStore`, `NowcastRefreshPolicy`, `NowcastFetcher`, `NowcastDisplay`,
  `NowcastBand`. `RawFetch.nowcast` carries the full fetch's slots; both platforms save it.
- **Header text is compact and word-free**: `40% 14:15–` (rain from) / `40% –15:30` (until); nothing
  extra for "raining throughout". It is *part of the rain % string*, so every Android width fit
  (`HeaderWidthChecker`, `resolveHeaderPrecipPlacement`) measures it and the date-vs-rain-% rule
  drops it with the %; shown only when the % is. Daily header only (hourly header unchanged).
- Hysteresis fills lone dry gaps **before** dropping lone wet slots (applied the other way round,
  the rules fought: a shower with one dry quarter ended early — caught by the unit test).
- Store keyed by `LocationMatch.quantize` then 2 decimals, so the active location and stored row
  coordinates name the same file. Android: `cacheDir/nowcast`; desktop: `$XDG_CACHE_HOME/weather-widget/nowcast`.
- Refresh: Android inside the current-temp branch's fetch block (`NowcastRefresher`, after the
  METAR/Synoptic refreshers); desktop each observation-loop tick, before the full-refresh skip
  (`DesktopWeatherRepository.refreshNowcastIfDue`), then a panel + popup refresh.
- **Not built**: the "newest observation reports precipitation" plausibility input (hourly chance
  from any source, or the cached series' own rain, covers it). Scoring (§6) is still phase 1b.
- Usage stats: `forecast/nowcast` (nowcast + probe; `forecast_minutely_15=` is the tell).
