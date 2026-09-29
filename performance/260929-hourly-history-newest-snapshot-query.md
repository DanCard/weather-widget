# Hourly render load: read the newest snapshot per hour, not every snapshot

Status: **plan, not started.** Follows `performance/260929-cold-process-location-change-repaint.md`,
which left one cold hourly load (5.5 s for Kyiv) on the location-change critical path.

## Problem

`HourlyForecastLoader.load` (Android) and `DesktopWeatherDao.getHourlyWithHistory` (desktop) read
**every stored snapshot** of every hour from `hourly_forecast_history`, then `HourlyForecastStitcher`
keeps one row per (source, hour). For a site the device fetched often, almost everything read is
thrown away.

Measured on the Pixel 7 Pro DB (2026-09-29, Kyiv, 72 h back / 168 h forward, 3 sources):

| History rows returned | Count |
|---|---|
| Today (`LocationMatch.ROOM_WHERE`, ±0.1°) | 20,168 |
| Box narrowed to the stitcher's reach (±0.01°) | 18,516 (−8 %) |
| **Newest snapshot per (source, hour, site), ±0.01°** | **4,164 (−79 %)** |

- The volume is **snapshot depth** (35 fetch buckets for the same hours), not the box: the two big
  Kyiv sites are both within 0.01°.
- The query plan walks the `dateTime` primary-key prefix across **all sites** (81,055 index entries
  in the window) and sorts the result in a temp B-tree.
- Cost on device: `historySqlMs` 2.9 s and `stitchMs` 2.2 s cold (~0.36 s + 0.25 s warm). Both scale
  with rows returned.

## What the stitcher actually uses (the invariant to preserve)

From `shared/.../HourlyForecastStitcher.kt`, per source, per hour:
1. Rows are restricted to `LocationMatch.sameSite` (0.002°) of the centre; only if **none** exists
   may it borrow from `withinNearbyFallback` (0.01°). **Nothing beyond 0.01° can ever be selected.**
2. `pick` = `maxByOrNull { fetchedAt }` over those rows (all sites and buckets together).
3. Nullable fields (`cloudCover*`, `precipProbability`, `precipAmountMm`) missing on the picked row
   are coalesced with `firstNotNullOfOrNull` over the **same candidate list, in DAO order**
   (`dateTime ASC, timestampToGroupPredictions DESC`). That is the NWS near-term skyCover case.
   Kyiv (Silurian/Open-Meteo) had **0** hours needing it; NWS sites will have some.
4. Live (`hourly_forecasts`) wins per hour; history fills hours live lacks and the same nullable fields.

A reduced read is correct only if `stitchBySource(reduced) == stitchBySource(full)` for every input,
including ties on `fetchedAt`, multi-site hours, and null fields.

## Design

A two-part read that returns a superset of everything steps 1–3 can consult:

1. **Newest rows:** for each (source, dateTime, locationLat, locationLon) inside ±0.01°, the rows whose
   `fetchedAt` equals that group's max. Use a `JOIN` on a `GROUP BY … MAX(fetchedAt)` subquery, not
   the bare-column shortcut: ties must return *all* tied rows, and window functions are unavailable on
   API 26–29's SQLite (3.18).
2. **Coalescing supplement:** for groups whose newest row has a null in any coalesced field, also
   return that group's older rows (bounded: those groups only). Empty for Silurian/Open-Meteo; a few
   dozen near-term hours for NWS.

Box: pass a second, tighter box (`NEARBY_FALLBACK_TOLERANCE_DEG`) to these render-path queries only.
`ROOM_WHERE` stays for everything else (Forecast History, accuracy, backfills read full depth on
purpose).

Whether step 2 exactly preserves step 3's DAO-order coalescing across *sites* (an older bucket at site
A vs a newer one at site B) is the open question. The equivalence harness decides it, not argument.
If it can't be made exact, fall back to "newest rows + all rows for any (source, dateTime) with a
null", which is exact by construction and still small.

## Phases

1. **Equivalence harness (`:shared`, pure).** Model the reduction in Kotlin (`reduceForStitch(rows)`),
   and property-test `stitchBySource(reduce(full)) == stitchBySource(full)` over generated data:
   1–15 sites inside and outside 0.002/0.01°, 1–40 buckets, `fetchedAt` ties, random nulls in each
   coalesced field, multiple sources, and live-row overlap. Add a named case per stitcher regression
   in the memory index (borrowed fragment, stale fragment delta, midnight straddle).
2. **Real-data check (local only).** A script that runs the full and reduced reads against a pulled
   device DB (`backups/…/weather_database`) for every site with rows and diffs the stitched output.
   **Not committed as a fixture:** the DB is the user's GPS trail.
3. **Android DAO + loader.** New `getNewestHistoryForStitch` (+ supplement) in
   `HourlyForecastHistoryDao`, used by `HourlyForecastLoader.load` only. Robolectric test on a real
   in-memory Room DB: seed a Kyiv-shaped dataset (13 sites, 35 buckets, NWS null skyCover hours) and
   assert the loader's output is identical before and after. Keep `HOURLY_LOAD` and add
   `historyRowsBeforeReduce` only if it's cheap.
4. **Index, if the plan still scans.** Check `EXPLAIN QUERY PLAN` on the pulled DB. If the new query
   still walks the whole `dateTime` range, try an index on
   `(source, locationLat, locationLon, dateTime, fetchedAt)`; measure on the pulled DB before adding a
   Room migration (version bump + schema export; see memory "Room rename needs version bump").
5. **Desktop.** Same reduction in `DesktopWeatherDao.getHourlyWithHistory` (JDBC), same harness.
6. **Device measurement.** Cold Kyiv switch on the Pixel (force-stop → `am start` ConfigActivity →
   pick Kyiv): record `historySqlMs`/`stitchMs` and Save→drawn. Baseline 9.2 s; the target is the
   probe under 2 s cold.

## Out of scope

- Pruning old snapshots from `hourly_forecast_history` (a retention change, and other features read
  full depth).
- The fetch itself, and the `onUpdate` repaint after an app update.
