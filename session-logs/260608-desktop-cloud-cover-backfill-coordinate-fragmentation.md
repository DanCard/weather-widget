# Desktop cloud-cover backfill — root cause was coordinate fragmentation

**Date:** 2026-06-08
**Area:** `:desktop` / `:shared` — desktop hourly cloud-cover graph
**Outcome:** Fixed. Past-window cloud-cover graph now fully populated.

## Problem
On the desktop app, the hourly **cloud-cover graph was flat/empty for past hours** and showed
"Cloud data missing for 9 of 25 hrs". Prior attempts by codex/gemini (one-time Open-Meteo →
`"Generic"` backfill, plans prefixed `260608-`) did not fix it.

## Investigation (debugging-first, against the live DB)
Queried `~/.local/share/weather-widget/weather.db` rather than only reading source.

1. **Backfill never ran.** No `Generic` source rows exist in `hourly_forecast_history`. The gate
   `getHourlyHistoryCount < 24` is false on any populated DB (NWS history is plentiful), so the
   backfill is dead code on a real install. Not the cause.

2. **Secondary bug — snapshot selection dropped cloud.** `DesktopWeatherDao.getHourlyHistory`
   deduped per hour keeping the **highest `snapshotBucket`** (freshest snapshot). NWS near-term
   hourly rows often arrive with `cloudCover=null`, so the freshest snapshot of a now-past hour
   lacked cloud — only 3 of 72 past hours had cloud in the picked rows, though all 72 had a
   cloud-bearing *earlier* snapshot. Fixed by coalescing nullable fields across buckets. Real, but
   not sufficient on its own.

3. **Primary cause — coordinate fragmentation.** `hourly_forecasts` and `hourly_forecast_history`
   held **three slightly different (lat,lon) precisions** for the same fixed location
   (`37.4167/-122.089` from config vs geocoded `37.416883/-122.089009` vs `37.416669/-122.089195`).
   `locationLat`/`locationLon` are part of each table's primary key and reads matched them with
   exact float `=`. So data splintered into per-precision silos: the cloud-rich history lived under
   `37.416883`, but the app queried the config's `37.4167` and saw almost no cloud. For the exact
   on-screen window: exact-match = 16 hrs / 4 with cloud; proximity box = 24 hrs / 24 with cloud.

   (Note: the graph's "missing N of 25" counts absent *rows* in the window; null cloud renders as
   `0` — the flat line. Two symptoms, one cause.)

## Fixes (both shipped)
- **`shared/.../desktop/DesktopWeatherDao.kt`**
  - `getHourlyHistory`: coalesce `cloudCover`/`precipProbability`/`precipAmountMm` across snapshot
    buckets per `(dateTime, source)` instead of single-row pick (keep freshest temp/condition).
  - **Proximity match** on all 11 location-filtered queries: replaced
    `locationLat = ? AND locationLon = ?` with
    `ABS(locationLat - ?) <= 0.08 AND ABS(locationLon - ?) <= 0.10`
    (consts `LOCATION_LAT/LON_TOLERANCE_DEG`, ~5 miles at 37°N). Same two bound params → no index
    renumbering. Writes still store under the live config coord; the loose read reunites the silos.
    Self-healing — no data migration.
- **Tests** (`shared/.../desktop/DesktopWeatherDaoTest.kt`): nearby-coordinate match, far-away
  coordinate ignored, snapshot-bucket cloud coalesce.

## Verification
- `:shared` + `:desktop` test suites green.
- Deployed bytecode confirmed to contain the fix (checked the jlink runtime jar).
- Live-DB simulation: screenshot window 16/4 → 24/24 hours with cloud.
- Rebuilt distributable + restarted; screenshot confirms a fully-populated cloud curve
  (`11→100→54→6→56→9→48→51→17 … 47%`), "data missing" message gone.

## Notes / follow-ups
- The dead one-time backfill (`DesktopWeatherRepository.hasAttemptedBackfill` + `fetchHistory`) was
  left in place as a harmless fresh-install safety net; could be removed to reduce surface area.
- Coordinate fragmentation likely affects Android too if it keys on raw lat/lon; not investigated.
- Memories: `desktop_coordinate_fragmentation`, `desktop_history_snapshot_drops_cloud`.
