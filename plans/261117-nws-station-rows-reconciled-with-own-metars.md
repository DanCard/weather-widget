# A hole in the nearest NWS station's rows is never refilled while other stations cover it

User (2026-11-17): "now fix the per-station gap rule". This follows
`plans/261117-today-fog-stale-forecast-and-nws-metar-history-fill.md`, which recovers a station's METAR
history only when its api.weather.gov history is **stale**.

## Evidence (emulator-5554, backup `backups/20261117_101124_sdk_gphone64_x86_64_emulator-5554`)

1. KNUQ (3.8 km, the nearest station) has `api='NWS'` rows at 05:55 and 08:55, and none in between.
   Its own METARs exist for every 20 min of that gap (`10SM CLR`, in the `METAR` and `SYNOPTIC` rows).
2. KSJC (15.9 km) has `api='NWS'` rows through the gap, in fog. The fog reader takes the nearest station
   that has rows in each hour, so KSJC stands for 06:00–07:59 and Today draws dense fog.
3. The gap fill never runs for today: `incompleteDays` → `dayHasOfficialGap` looks at **all official
   stations merged**, and KSJC's reports leave no merged gap over 75 min.
4. Even if it ran, the METAR history leg triggers only when the station's **newest** API report is over
   60 min old. KNUQ's newest is now 09:55, so the hole in the middle isn't seen.

## Options considered

1. **Per-station hole in the need check.** Rejected: part-time stations would loop. KPAO (a tower
   station) sends no reports overnight, so every day would show a "hole" that no fetch can fill. The
   gap fill would re-run five NWS station requests on every sync, indefinitely (the
   unfillable-coverage-gap loop again). Stopping that needs per-hole attempt state.
2. **Trigger only when another feed's stored rows show the station reporting in the hole.** Rejected:
   those rows exist only on some devices. The emulator stores METAR and Synoptic rows, the Pixel only
   Synoptic, and the Samsung neither (checked for 11-13 through 11-17). Two devices would repair
   different days.
3. **Reconcile (proposed).** Each gap-fill pass asks Aviation Weather once for the nearby METAR
   stations' last 72 h and stores every report the NWS rows lack. No need check, no hole detection, no
   attempt state. A report that doesn't exist is never fetched again, because there's nothing to add.

## Proposed fix (`:shared`, so both platforms change together)

1. **`NwsGapFillRun.reconcileMetars(host, lat, lon, nowMs, zone)`**, run at the start of
   `dailyForSources` (before the need check), on every trigger it already has: Android full sync and
   Refresh, desktop launch/wake and Refresh.
   - Stations: `host.stations()` (already cached by both hosts), the first `MAX_STATIONS`, filtered by
     `NwsMetarTextWeb.isMetarStation`.
   - **One** request: `fetchMetars(ids, hours = 72)` across all of them.
   - Each report is mapped with `MetarObservationMapper` and stamped as that NWS station's web row
     (`NwsStationLatestFetcher.asNwsWebRow`: `api='NWS'`, `isWebFallback=1`).
   - Kept only where the stored NWS rows (`host.observations`) have no row for that station and time.
     An API row keeps its time.
   - Stores the rows, recomputes the affected days' extremes, and bumps the stored-data version, as the
     fill already does.
   - One log line per pass: `NWS_METAR_RECONCILE stations=… fetched=… added=… days=…`
     (`unreachable=` on failure). A failure is logged and the daily pass carries on as before.
2. **Host:** one new call, `fetchMetars(stationIds, hours)`, which both hosts implement with the
   `AviationWeatherApi` they already hold (Android: inject it into `NwsObservationBackfiller`'s host;
   desktop: `DesktopWeatherService.aviationWeatherApi`).
3. **Unchanged:** the need check, the per-station API fill, the stale-history leg from the previous plan
   (it still covers a station the reconcile request misses), the fog reader and `nearestPerHour`.

**Expected result:** on the next gap-fill pass after install, the emulator stores KNUQ 06:15–08:35 as
NWS rows and recomputes 11-17. The fog reader takes KNUQ for every hour (all clear), observed fog
becomes NONE, and with fix 1 the NWS Today column shows no fog. Days back to 11-14 (72 h) get the same
repair, as do the temperature line and observed rain.

**Cost:** one Aviation Weather request per gap-fill pass (about 5 stations × 72 h, a few hundred
reports), on the triggers that already run the pass. Aviation Weather is free and keyless, and the
METAR source already calls it.

**Not covered:** a later text-less API row replacing a recovered copy (same primary key,
`REPLACE`). The next reconcile sees a row at that time and leaves it. This is noted for later; it isn't
needed for 11-17.

## Tests

Each test below fails on the current code.

1. `NwsGapFillRunTest` (fake host): stored NWS rows have KNUQ 05:55 and 08:55 plus KSJC every 20 min.
   The METAR answer has KNUQ every 20 min. Reconcile adds exactly KNUQ's 06:15–08:35 (7 rows, not
   05:55 or 08:55), recomputes 11-17, and logs `added=7`. Also: a failed request logs `unreachable` and
   the daily pass still runs, and a personal station is never asked.
2. **Integration, Ktor + real SQLite:** `DesktopNwsGapFillHostContractTest` (and
   `RoomNwsGapFillHostContractTest`). The real `AviationWeatherApi` on a `MockEngine` serves
   `aviationweather/KNUQ_261117.json`. After `dailyForSources`, the stored `api='NWS'` rows give
   `ObservedMorningFog.forDate(11-17) == MorningFog.NONE`; before the change they give DENSE.
3. `./scripts/unit-tests.sh`, then
   `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`. On the emulator:
   `NWS_METAR_RECONCILE … added>0`, KNUQ `api='NWS'` rows 06:15–08:35 in a fresh backup, no
   `DAILY_FOG_DRAWN date=2026-11-17`, and a screenshot of the NWS Today column.

## Docs

- `arch/forecast-and-observation-cadence.md`, gap-fill section: the reconcile step, its single request,
  and its log line. Link here.
