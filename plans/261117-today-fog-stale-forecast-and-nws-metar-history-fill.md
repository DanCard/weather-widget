# NWS Today drew dense fog on a clear morning: a stale forecast fog top, plus a backfill that recovers one report

User (2026-11-17, emulator): "Why fog indicator? NWS today column." Approach chosen by the user:
recover the nearest station's real METARs when the NWS API's history is stale, with no distance limit
("I don't like the distance limit complexity … Fallback to metar or synoptic?" → "yes … No distance
limit complexity"). This replaces the distance-limit draft
`plans/261117-today-fog-stale-forecast-and-far-station-fill.md`.

## Evidence (emulator-5554, backup `backups/20261117_101124_sdk_gphone64_x86_64_emulator-5554`)

1. Screenshot 10:11: the NWS Today column shows a bright ellipse from the low (42.6°) to about 54°.
2. logcat 10:10: `DAILY_FOG_DRAWN date=2026-11-17 level=4 top=53.5 vis=0.125`. This is
   `combine(observed, forecast)`: DENSE and ⅛ mi come from observed, and top 53.5 comes from the
   forecast, because the higher top wins.
3. **Forecast side.** The NWS `forecasts` row for 11-17, fetched at 10:09:28, has
   `fogLevel=2 top=53.5 vis=3.0`, but that fetch's hours 01:00–12:00 have no fog ("Mostly Clear",
   "Clear" or "Sunny", with 6–10 mi visibility). The 53.5 comes from the **01:10** fetch, which had
   "Patchy Fog" at 06:00, 07:00 and 09:00 (52°), with 10:00 at 55°: (52 + 55)/2 = 53.5.
4. **Observed side.** Hour by hour, `ObservedMorningFog.nearestPerHour` picked from the `api='NWS'`
   rows:

   | hour | station | report |
   |---|---|---|
   | 04–05 | KNUQ 3.8 km | `10SM CLR 06/06` |
   | 06–07 | **KSJC 15.9 km** | `1/4SM FG` … `M1/4SM FG VV001`, until 07:50 |
   | 08–09 | KNUQ | `10SM CLR` |

5. **Why KNUQ had no NWS rows for 06:15–08:35.** `app_logs` is empty from 06:03 to 09:28; the app
   wasn't running observation fetches. At 09:28 the gap fill asked each station for 72 h:
   ```
   OBS_HOURLY_WEB_FALLBACK station=KNUQ reason=stale
   OBS_HOURLY_BACKFILL_STATION station=KNUQ rows=1 web=true
   ```
   api.weather.gov's KNUQ history was stale (newest 05:55), so the web fallback ran. That fallback is
   NWS's latest-METAR text file (`NwsMetarTextWeb.fetchReading`), which returns **one** report, the
   newest. KSJC's API history was complete, so its fog reports were stored.
6. KNUQ's real reports exist: the `METAR` (aviationweather.gov, fetched 10:10) and `SYNOPTIC` rows have
   `10SM CLR` at 06:15, 06:35, 06:55, 07:15, 07:35 and 07:55.

## Root causes

### 1. The persist path's stored-hours read starts at 08:00, so today's forecast fog cannot clear

`ForecastPersistRun.persistDaily` builds each day's summary as
`carryForward(carryForward(fromPayload, provider), fromStored)`. `DailyForecastWrite` then falls back
to the prior row (`fog = incoming.fog ?: prior.fog`).

- **fromPayload is null.** The 10:09 download's hours start at 01:00, so
  `windowCovered(00:00–12:00)` fails and `ForecastMorningFog.forDate` returns null ("can't tell").
- **fromStored is null too.** The read is `host.storedHourly(DailyPrecipPeriods.readStartMs(first), …)`,
  and `readStartMs` is **08:00** (the rain day window). That can never cover the fog window.
- So the 01:10 fetch's LIGHT/53.5 carries forward, even though NWS has dropped the fog.

### 2. The NWS history backfill recovers only the newest report from a stale station

`NwsStationHistoryFetcher.fetch` falls back when the API's history is stale. Both platforms pass the
latest-METAR text file as the `web` leg, so a station whose API history stopped hours ago gets back one
report instead of the hours it is missing. In those hours the fog reader correctly takes the nearest
station that has rows, which was KSJC.

## Proposed fix (`:shared`, so both platforms change together)

### Fix 1: read stored hours from the fog window's start

In `ForecastPersistRun.persistDaily`, start the stored read at
`minOf(DailyPrecipPeriods.readStartMs(first), MorningFogWindow.startMs(first, zone))`.

- The DAO returns the newest fetch per hour. 00:00 comes from the 01:10 fetch ("Mostly Clear") and
  01:00–12:00 from the 10:09 fetch, so the window is covered and fog becomes `MorningFog.NONE`. NONE
  replaces the stale fog.
- The rain and cloud summaries filter their own windows, so the extra 00:00–08:00 rows don't change
  them.
- No schema change. The stale stored row corrects itself on the next fetch.

### Fix 2: the history fallback fetches the station's METAR history, not just its latest report

1. **New shared web leg** `NwsMetarHistoryWeb.fetchWindow(api: AviationWeatherApi, station, lat, lon,
   startMs, nowMs)`:
   - Only for `NwsMetarTextWeb.isMetarStation(station)`; others return null, as today.
   - Calls `AviationWeatherApi.fetchMetars(listOf(station.id), hours)`, the same client the METAR source
     already uses. `hours` covers `startMs..nowMs`, rounded up.
   - Maps the results through the existing `MetarObservationMapper` and stamps each row as this NWS
     station's web row (`NwsStationLatestFetcher.asNwsWebRow`: `api='NWS'`, `isWebFallback=1`).
     This is the same row shape the live path already stores.
   - On failure or no data, falls back to the current single latest-METAR-text report, so nothing is
     lost compared with today.
2. **Both platforms pass it as the history `web` leg**, with no other per-platform logic:
   - Android: `NwsObservationSource.fetchHistorical` (inject the existing `AviationWeatherApi` from
     `AppModule`).
   - Desktop: `DesktopWeatherService.fetchNwsStationWindow` (it already has `aviationWeatherApi`).
3. **Keep the API rows next to the web rows.** `NwsStationHistoryFetcher.fetch` currently returns the
   web rows *instead of* the API rows. It will return the API rows plus the web rows for any
   timestamps the API lacks, so a partial API window is no longer thrown away.
4. Fix the stale class comment ("Android asks Synoptic for the backfill window"). Android now uses
   NWS's METAR text file, and after this change both platforms use the METAR history.

Unchanged: the trigger (`ObservationFallbackPolicy.shouldUseWebFallback`: one of the five nearest
stations, API newest more than 60 min old), the fog reader, `nearestPerHour`, and the "one actuals
provider" rule. These are the station's own METAR reports, stored as NWS rows exactly as the live
fetch-both path already stores them.

**Expected result for 11-17:** the 09:28 backfill stores KNUQ 06:15–09:15 as `api='NWS'`. The fog reader
then takes KNUQ for every hour, all `10SM CLR`, so observed fog is NONE. With fix 1, the forecast fog
from the current hour to noon is NONE as well. **No ellipse.** The temperature graph and observed rain
also gain the three hours.

**Not covered, noted for later:** a gap *inside* an API window whose newest report is fresh (the
trigger is based on the newest report's age). Also, a later text-less API row replacing a web copy
(`OnConflictStrategy.REPLACE` on `(stationId, timestamp, lat, lon, api)`) can still blank a row that
had text. Neither caused this morning.

## Tests

Each test below fails on the current code.

1. `NwsStationHistoryFetcherTest`: the API's newest report is at 05:55 and the web leg returns 06:15 to
   09:15. The result holds the API rows plus the web rows, with web rows stamped `api='NWS'`,
   `isWebFallback`. Today's code returns the web rows only, and on the real platforms just one.
2. `NwsMetarHistoryWebTest`: the hours span is rounded up to cover the window, a non-METAR station
   returns null, and a failure falls back to the latest-METAR-text report.
3. **Integration (Ktor pipeline + real SQLite):** `NwsGapFillRun` with the desktop host on a real
   `DesktopWeatherDatabase`. A Ktor `MockEngine` serves api.weather.gov (KNUQ stale at 05:55, KSJC with
   fog at 06:00–07:50) and aviationweather.gov (KNUQ `10SM CLR` 06:15–09:15). Afterwards,
   `ObservedMorningFog.forDate` on the stored `api='NWS'` rows gives `MorningFog.NONE`. Today's code
   gives DENSE.
4. `ForecastPersistRunTest`: stored hours include an old fetch with "Patchy Fog" at 06–09 and a newer,
   all-clear fetch at 01–12. The new download starts at 01:00 and the prior row holds LIGHT/53.5. The
   written fog must be NONE; today's code keeps 53.5. Repeat through the real DAO in
   `DesktopForecastPersistHostContractTest` and `RoomForecastPersistHostContractTest`.
5. `DailyColumnFogTest`: observed NONE plus forecast row NONE gives no fog for today.
6. `./scripts/unit-tests.sh`, then
   `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`. Then on the emulator:
   a screenshot of the NWS Today column, no `DAILY_FOG_DRAWN date=2026-11-17` line, and
   `OBS_HOURLY_BACKFILL_STATION station=KNUQ rows=N web=true` with N > 1 on the next stale backfill.

## Docs

- `arch/forecast-and-observation-cadence.md` (gap-fill section): the history web fallback now fetches
  the station's METAR history from Aviation Weather, falls back to the latest METAR text, and keeps
  the API rows.
- `arch/ARCHITECTURE.md` daily fog section: the forecast fog summary reads stored hours from the fog
  window's start.
- `arch/daily-history-extremes.md` "Observed morning fog": no rule change. Check that it doesn't
  describe the backfill.
