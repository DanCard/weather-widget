# NWS web leg: NWS's own METAR text file, not aviationweather.gov

User, 2026-10-15: "I want NWS for NWS. Why when requesting web data is there a token in the
picture? There shouldn't be." "When I say web, I mean getting an HTML page." After the options were
laid out, the user chose the `tgftp` text file ("ok").

## Evidence

- Desktop Observations Refresh at 12:40 stored KNUQ 12:15. The Samsung had stored 12:35 at 12:38:36.
  The API leg returned 11:55 on both. The web leg differed: desktop's aviationweather.gov batch took
  20 s and returned 12:15 (Azure Front Door CDN, `max-age=60`). The next desktop fetch, at 12:42,
  returned 12:35.
- The web leg was aviationweather.gov because of `848a6131` (2026-08-25, Codex), which replaced
  Synoptic when its trial token was blanked (`b992a3dc`). Synoptic is what
  `weather.gov/wrh/timeseries` loads in the browser: the page's HTML has no readings, and its
  `apiKey.js` holds a Synoptic token. **The user does not want Synoptic or any token in the NWS path.**
- NWS's own sources, KNUQ at 12:50:

  | Source | Newest |
  |---|---|
  | `api.weather.gov` | 12:15 |
  | `forecast.weather.gov/data/obhistory/KNUQ.html` | 12:15, lags like the API |
  | **`tgftp.nws.noaa.gov/data/observations/metar/stations/KNUQ.TXT`** | **12:35**, posted 12:38:20 |

- `tgftp`: no token, no key, `Cache-Control: no-cache`, about 71 bytes. Line 1 is `yyyy/MM/dd HH:mm`
  (UTC); line 2 is the raw METAR. A station with no METAR (LOAC1) returns 301.

## Change

1. **`:shared` `NwsMetarTextApi`**: `GET tgftp…/stations/{ID}.TXT` → `FetchOutcome<MetarRow-like>`.
   It has a pure parse half for fixtures. The time comes from the METAR's own `ddHHmmZ` group,
   checked against line 1. Temperature comes from the T-group tenths when present, else the body
   `23/03`. Sky comes from `MetarRawSkyParser`, and the result passes `MetarPlausibility`, all
   through the existing `MetarDecoder`. A 301 or 404 is `NoData`, not `Failed`.
2. **The web leg becomes `WebSource("nws_metar_text")`, one request per station**, for the stations
   the tier policy already selects (`ObservationFallbackPolicy.shouldFetchWeb` /
   `shouldLogWebMetrics`: nearest 3 used, stations 4–5 for metrics only). The requests start
   alongside the API requests, as the batch does today. Same on Android
   (`NwsCurrentObservationUpdater`) and desktop (`DesktopWeatherService`). The shared prefer-newest
   merge (`NwsStationLatestFetcher`) is unchanged.
3. **aviationweather.gov and Synoptic leave the NWS path.** aviationweather.gov stays only where NWS
   isn't involved: borrowed METAR actuals for forecast-only sources outside NWS coverage
   (`ActualsFeedPolicy`). Synoptic stays only where the user picked it as a source's actuals
   provider. The desktop `synoptic_test` web seam is removed (tests move to an injected
   `NwsMetarTextApi`).
4. **`OBS_WEB_API_DELTA`**: `transport=nws_metar_text`, plus the request's `ms=`. `webTempF` reads the
   newest web row (it read the oldest: 66.2 °F logged beside a stored 73.4 °F).
5. LOAC1 (RAWS) and AW020 (personal) get no web row. Same as today, since aviationweather.gov had
   none for them either.

## Not changed

- The API leg, the history pulls (`api.weather.gov` only), the merge rule, the cadence, the tiers.
- No schema change: `isWebFallback`, `isMetar` and `rawMetar` already carry the provenance.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | Parse: KNUQ fixture with T-group and without; header/METAR time disagreement → METAR group wins; a day rollover (`01` group on the 31st); garbage → `Failed` | shared unit |
| 2 | 301/404 → `NoData`; 5xx / timeout → `Failed` (fake engine) | shared unit |
| 3 | Merge: a web 12:35 beats an API 11:55 and is stored `isWebFallback`; a web row older than the API row loses | shared unit (existing merge tests, new transport) |
| 4 | Android updater with fake NWS API + fake text API: KNUQ stored 12:35, KPAO/KSJC web rows, LOAC1 none, no aviationweather.gov or Synoptic request made | Robolectric integration |
| 5 | Desktop service, same scenario and the same "no other transport" assertion | desktop integration |
| 6 | Live: curl each of the 5 IDs once; save KNUQ / KPAO / KSJC as fixtures | manual, before code |
| 7 | On device: Pixel, Samsung, desktop; Refresh; `OBS_WEB_API_DELTA transport=nws_metar_text`; KNUQ row equals the text file | on-device |

## Implemented (2026-10-15)

- `:shared` `NwsMetarTextApi` (fetch + pure `parse`) and `NwsMetarTextWeb` (one request per station for
  the nearest 5, all started before any API answer; `WebSource.elapsedMs` → `webMs=` in the log row).
- **Also the history web fallback.** When `api.weather.gov` history comes back empty, stale or failed,
  Android's fallback used Synoptic over the whole window. It now uses the same text file
  (`NwsObservationSource.metarTextApi`), as desktop already reused the web leg's rows. That file holds
  only the newest report, so the fallback fills one point, not hours. The API's own 7-day history
  (`/stations/{id}/observations`, 500 readings for KNUQ) is unchanged and remains the main history
  source. Watch the actual line for gaps in the cases where the API history fails.
- Log tags renamed: `NWS_DAILY_SYNOPTIC_FALLBACK` → `NWS_DAILY_WEB_FALLBACK`,
  `OBS_HOURLY_SYNOPTIC_FALLBACK` → `OBS_HOURLY_WEB_FALLBACK`; desktop `NWS_STATION_FAIL … synoptic=` →
  `web=`.
- Desktop: `injectedNwsMetarTextApi` seam; the `synoptic_test` web seam is gone.
  `DesktopSynopticFallbackTest` → `DesktopNwsWebLegTest`, which also asserts no request goes to
  `api.synopticdata.com` or `aviationweather.gov`.
- Tests: `NwsMetarTextApiTest` (10), `NwsMetarTextWebTest` (3), `NwsCurrentWebLegIntegrationTest`
  (Android end to end, real API class over a mock engine), `NwsObservationSourceTest` history
  fallback rewritten, desktop recent-window / blend tests moved to the new seam.
