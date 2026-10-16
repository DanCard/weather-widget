# A LONG fetch keeps its 8 days of live hourly; snapshots stay at 72 h

Status: **approved** (user, 2026-10-16: "do it"). Amends
`performance/261010-daily-view-summaries-instead-of-far-hourly.md` (hourly stored to 72 h) under the
cadence of `performance/261016-unified-long-short-forecast-horizons.md`.

## Problem

Since 2026-10-16 a LONG fetch downloads ≈ 8 days of hourly (Google asks 192 h, billed per 24 h page;
the free sources return their whole horizon in one call) and then stores only 72 h. Opening day 4–8
in the hourly view then fetches again on demand:

- **Google pays twice.** LONG paid for pages 4–8; a tap on day 5 pays pages 1–5 again, against the
  60/day `forecast/hours` quota.
- Days 4–8 show "Fetching hourly forecast for {day}…" although the data was downloaded hours ago.

## Why the 72 h trim was right on 2026-10-10 and is only half right now

The bytes were in two places (Pixel, measured 2026-10-10):

| Table | Key | Cost of a deeper horizon |
|---|---|---|
| `hourly_forecasts` (live) | `(dateTime, source, lat, lon)` — REPLACE in place | one row per hour per source per site, **bounded** |
| `hourly_forecast_history` | + `timestampToGroupPredictions` | a new copy of every hour on every fetch, **grows** (36 MB of 52) |

The 384 h Open-Meteo horizon at ~100 travel sites was the other half; the live store is now capped at
192 h, not 384.

## Change

1. **`HourlyHorizon.longHours`** = `min(192, maxHours)` (never below `routineHours`):
   Google 192, Open-Meteo/Silurian 192, NWS 156, Tomorrow.io/OWM 120, WeatherAPI 72.
   `ForecastHorizonCadence.LONG_ASK_HOURS` refers to the same 192 (`HourlyHorizons.LONG_HOURS`).
2. **Two horizons per fetch** (`:shared` `HourlyOnDemand.keep(source, request, horizon)` →
   `Keep(liveHours, snapshotHours)`):
   - live: LONG → `longHours`; SHORT → 72; on-demand → as today (request depth / source max);
   - snapshot: exactly what is stored today (72, or the on-demand depth). Unchanged.
   SHORT keeps 72 live even when a free source's download reaches further: days 4+ (daily rows *and*
   hourly) stay the last LONG fetch's, as the cadence decided.
3. **Saves** (Android `HourlyForecastStore.saveHourlyEntities`, desktop `persistForecastResult`): the
   live write keeps `< liveUntil`, the snapshot write `< snapshotUntil`. `HOURLY_TRIM` logs both:
   `kept=… snapshotKept=… dropped=… horizonH=… snapshotH=…`.
4. **`HourlyOnDemand.hoursToCover`** — when stored hours count as covering a day:
   - inside 72 h: any age (unchanged);
   - 72 h – `longHours`: fetched within `ForecastHorizonCadence.MAX_CARRIED_ROW_AGE_HOURS` (36 h) —
     the same age the daily view accepts for that day's row (LONG runs every 8–24 h);
   - past `longHours`: within 12 h (`MAX_EXTENSION_AGE_MS`, unchanged — on-demand only).
   "A free source's fresh fetch already brought everything" now needs a fresh row **past `longHours`**
   (only an on-demand fetch stores there), or past 72 h for a source whose whole horizon fits in
   `longHours` (NWS, Tomorrow.io, OWM). Without this, every routine Open-Meteo fetch would claim day
   9–10 cannot be fetched.
5. Readers unchanged: the daily view still reads the forecast row first (the row and the stored hours
   now come from the same LONG download); both hourly loaders already read now + 240 h.

Not changed: retention (30 days), snapshot pruning, the routine 72 h window that `extensionStartMs`
uses (rows past it are always rewritten, which keeps their `fetchedAt` current).

## Expected cost

≈ 120 extra live rows per source per site (Google, Open-Meteo, Silurian; fewer for the rest),
overwritten in place, aging out at 30 days. No change to the snapshot table.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `HourlyHorizons.longHours`: Google/Open-Meteo/Silurian 192, NWS 156, Tomorrow.io/OWM 120, WeatherAPI 72; never < routine | unit (:shared) |
| 2 | `keep`: LONG → live = longHours, snapshot 72; SHORT → 72/72; no horizon → 72/72 | unit |
| 3 | `keep`: on-demand request → live = snapshot = today's on-demand depth (Google request, free source max) | unit |
| 4 | `storeHours(LONG)` = longHours, `askHours(LONG)` Google still 192 (no request-count change) | unit |
| 5 | `hoursToCover`: Google day 5, rows from a LONG fetch 20 h ago → null (no billed fetch) | unit |
| 6 | `hoursToCover`: same rows 40 h old → fetch | unit |
| 7 | `hoursToCover`: Open-Meteo day 9, routine LONG rows to 192 h fetched 1 h ago → fetch (not "nothing more") | unit |
| 8 | `hoursToCover`: NWS day 7, LONG rows to 156 h fetched 1 h ago → null (whole horizon in hand) | unit |
| 9 | `hoursToCover`: inside-72 h and past-`longHours` behaviour unchanged (existing tests stay green) | unit |
| 10 | Android: LONG fetch of a 16-day download → live max hour ≥ now+180 h and < now+192 h; snapshot < now+72 h | integration (Robolectric: coordinator + store + Room) |
| 11 | Android: SHORT fetch after it → live hours 72–192 h keep the LONG fetch's values | integration |
| 12 | Android: after the LONG fetch, `hoursToCover` over the stored rows for day 5 → null | integration |
| 13 | Desktop: LONG refresh → live hourly to 192 h, `hourly_forecast_history` to 72 h | integration (repository + DAO) |
| 14 | Existing on-demand integration tests (Android + desktop) unchanged | integration |

## Verification

- Tests 1–13 implemented (`ForecastHorizonCadenceTest`, `HourlyOnDemandTest`,
  `DailySummaryStorageIntegrationTest`, `ForecastHorizonStorageIntegrationTest`,
  `DesktopDailySummaryStorageTest`); `:shared:test`, `:desktop:test`, `:app:testDebugUnitTest` green
  (2559 tests).
- Two existing tests pinned the old rule and were updated: Android "first fetch is LONG … 72 h of
  hourly" (now 8 days live), and "on-demand hours stop counting after 12 h" (Monday is inside the LONG
  window → 36 h; the 12 h rule is now tested past 192 h).
- Installed on the Fold (SM-F936U1); desktop rebuilt and restarted 2026-10-16 13:59. On-device
  confirmation (`HOURLY_TRIM … horizonH=192 snapshotH=72` on the next LONG fetch) pending.
