# METAR gap fetch window (desktop provider switch left METAR with 2 h of data)

## Evidence (desktop DB, 2026-11-21)

1. `07:30:38 CONFIG_SAVE source=observations-window actualsProviders: {…GOOGLE_WEATHER=NWS} -> {…GOOGLE_WEATHER=METAR}`
2. `07:30:38 LAUNCH_REFRESH_CHECK reason=actuals_provider_change source=GOOGLE_WEATHER actualsProvider=METAR
   action=OBSERVATIONS … forecastAgeMs=101345 lastObservationFetch=null`
3. `07:30:39 OBS_REFRESH source=GOOGLE_WEATHER obs=16` and
   `TODAY_LOW_UNCOVERED span=05:35..07:15 rows=16`
4. `observations` table, last 3 days: `METAR` = **16 rows, 05:35–07:15 today**; `NWS` = 1998 rows back to 11-18.
5. Google's today high fell `45.86 -> 41.08` (`DAILY_HISTORY_OVERWRITE date=2026-11-21 src=GOOGLE_WEATHER`):
   the day is now computed from 1 h 40 min of METAR.

## Root cause

The forecast was fresh, so the provider change chose `LaunchRefreshAction.OBSERVATIONS` →
`DesktopWeatherRepository.refreshObservations()` → `fetchObservationsOnly(recentOnly = true)` →
`DesktopWeatherService.fetchStationNetworkObservations` → METAR `hours = RECENT_BORROWED_METAR_HOURS` (2).
The recent window assumes earlier hours are already stored; for a feed that was never fetched here
nothing is. The 24 h `RECOVERY_BORROWED_METAR_HOURS` pull only rides a full forecast refresh (next one
~2 h later), and even then never reaches yesterday.

Android is not hit by the switch itself (`ActualsProviderChangeCoordinator` forces a full sync →
`FullSyncPipeline` uses `MetarObservationRefresher.DEEP_HOURS`), but its frequent loop has the same
fixed 2 h shallow pull, so a suspend/offline gap longer than 2 h stays a hole until the next full sync.

Synoptic already solved this with `SynopticFetchWindow` (gap since newest stored row + margin,
deep when nothing is stored).

## Proposed fix

1. **`:shared` `MetarFetchWindow.hours(newestStoredMs, nowMs)`** — same rule as `SynopticFetchWindow`:
   null or ≥ 24 h old → 24 h; otherwise `ceil(gap + 30 min margin)` clamped to [2, 24] hours.
2. **Desktop**: `fetchStationNetworkObservations` METAR branch reads
   `getNewestObservationTimestampForApi(METAR, lat, lon)` (already used for Synoptic) and asks for
   `MetarFetchWindow.hours(...)` on the recent path. A provider switch with no METAR rows therefore
   pulls 24 h immediately. Log `BORROWED_METAR_FETCH … hours=` already records the window.
3. **Android**: `MetarObservationRefresher.refreshIfDue` shallow path uses the same shared window
   (newest stored METAR row from `ObservationDao`); full sync keeps `DEEP_HOURS`.
4. Docs: `arch/forecast-and-observation-cadence.md` (METAR window), dedupe against any "2 h" mention.

Not in scope (Claude proposal, ask before doing): back-filling METAR for days before the last 24 h
on a provider switch (aviationweather serves more history; would make past-day actuals switch too).

## Tests

- `MetarFetchWindowTest` (pure JVM): null, fresh, 3 h gap, ≥24 h gap, clamp edges.
- Integration (desktop): real `DesktopWeatherDao` with no METAR rows + fake `AviationWeatherApi`
  recording `hours`; `refreshObservations()` for a METAR-borrowing source asks for 24; with a
  10-minute-old METAR row asks for 2. Fails on current code (always 2).
- Android Robolectric: `MetarObservationRefresher` with a real in-memory Room DB, same two cases.

## Verification

Desktop: switch Google's actuals NWS → METAR → NWS → METAR on a clean METAR site row set, confirm
`BORROWED_METAR_FETCH hours=24` and METAR rows spanning 24 h; end-of-task gate.

## Immediate data recovery

Settings → Refresh data (full refresh) now pulls the 24 h METAR recovery window, restoring today.
