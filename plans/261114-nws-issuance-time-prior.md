# NWS: judge "before the hour" by issuance time, not fetch time

**User, 2026-11-13/14:** after "rain: any prior wins" (plans/261113-rain-no-hindcast-any-prior.md) a
side note warned that devices can disagree again: a device that slept keeps its old prior. Options
offered: (1) pick by provider issuance time, (2) Open-Meteo Previous Runs. User: **"option 1"**.

## Evidence

- The 2026-11-04 incident: desktop asleep 19:33–03:10 drew NWS 3 am as 4% (18:45 fetch); the Fold
  drew 25%. The desktop's own 03:10 fetch carried a forecast NWS had **issued** before 3 am, which was a
  real prior. The rule only saw that it was *fetched* after 3 am.
- Live probe (generic location, 2026-11-14 08:14Z): `/forecast/hourly` has `updateTime`
  2026-11-14T06:46:20Z (issuance) and `generatedAt` 07:26:26Z (render). The raw grid has the same
  `updateTime`.
- `NwsApi.getForecast` logs `properties.updated`, which is always null. The field is `updateTime`.
- No other source offers an issuance time we can use. Open-Meteo: default model blend, metadata
  endpoint 404 in the probe. Google: no such field in its hourly fixture. Tomorrow.io, Silurian,
  WeatherAPI, OWM: none in the parsed fields (not probed live, to avoid spending quota). Those keep
  fetch time.

## Change

1. **Parse** `updateTime` from NWS hourly and the raw grid; log it (fix the `updated` log key).
2. **Store** a nullable `issuedAt` on `hourly_forecasts` for NWS rows, live and elapsed
   (`ElapsedForecastBackfill`). Room migration + desktop migration (`ADD COLUMN` is fine; no
   rename/drop), exported schema, migration test with real SQLite. Clamp to `<= fetchedAt` (clock skew).
3. **Pick by it:** `SettledForecastPick` callers use `issuedAt ?: fetchedAt` as the "made at" time:
   stitcher (base and rain pools), `HistorySnapshotRetention` (its `Row` gains `issuedAt`; the keep rules
   use the same key so the prune stays exact), `DailyNoonCloudCover`, `CurrentTemperatureResolver`.
   A fetch at 03:10 of a 02:30 issuance is then a prior for 3 am, on every device that holds it.
   `fetchedAt` keeps its other meanings (freshness, refresh skips, labels).
4. **Dimming:** stale age measured from the made-at time (issuance for NWS).
5. **Docs:** `arch/hourly-forecast-update-cadence.md`.

Out of scope: today's NWS daily low/high (the `forecasts` table) still settles by fetch time;
Open-Meteo Previous Runs (option 2) remains a possible follow-up.

## Expected effect

Two devices that hold the same NWS issuance draw the same value for an hour. The Nov 4 replay:
desktop 3 am = 25% (issuance before 3 am, fetched 03:10) instead of 4%. They can still differ when one
device never downloaded the later issuance at all.

## Tests (each fails on the current code)

1. Nov 4 replay through `ForecastPersistRun` + stitcher: 18:45 fetch (4%), 03:10 fetch of a 02:30
   issuance (25%) → 3 am = 25%, rain and temperature; a 03:40 issuance fetched 03:50 stays a hindcast.
2. Retention: full vs pruned stitch identical with `issuedAt` rows, for several guard ages.
3. NWS parse: `updateTime` → `issuedAt` on hourly and elapsed rows; clamp when after the fetch.
4. Migrations: Room (instrumented, emulator) and desktop.
5. `./scripts/unit-tests.sh`; `./scripts/emulator-tests.sh -c` for the migration test.

## Verification

Install on all devices, restart desktop. After the next NWS fetch, query `issuedAt` vs `fetchedAt` on
the emulator and desktop; compare the NWS rain graph for the same past hours on desktop and emulator.
