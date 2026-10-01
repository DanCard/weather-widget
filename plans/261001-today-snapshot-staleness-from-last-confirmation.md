# Today snapshot bar: judge staleness from last confirmation, not first sighting

## Symptom
Pixel 7 Pro, 2026-10-01 09:09: the today column's "yesterday's forecast" (left, yellow) bar drew
dashed, the >48h stale cue (`StandInBarStyle`), though the device was online throughout.

## Root cause
- Today's NWS rows at the Mountain View site (37.417, −122.089): 84/58 fetched 09-29 08:39 and
  84/58 fetched 10-01 01:12. `selectPriorDaySnapshot` picks the newest row older than 24h, so it
  picks 09-29 08:39. That row is 48.5h old, so `isStale` returned true.
- But the value was re-fetched unchanged on 09-29 20:24 (`SNAPSHOT_SKIP date=1790812800000
  existing_high=84.0 new_high=84.0`) and again later. Android's `ForecastSnapshotStore` deduplicates:
  an unchanged re-fetch writes no new row, it only re-stamps the existing row's
  **`batchFetchedAt`** (`existing.copy(batchFetchedAt = batchFetchedAt)`). The device row shows
  `fetchedAt` 09-29 08:39 and `batchFetchedAt` 09-30 23:43.
- So on Android `fetchedAt` = first sighting and `batchFetchedAt` = last confirmation. `isStale`
  read `fetchedAt`, so a forecast that stays the same ages into "stale" while it is being re-confirmed.

## Fix
- The "last confirmed" timestamp the user approved adding already exists: `batchFetchedAt`. No
  schema change or migration.
- `DailySnapshotSelector.isStale(lastConfirmedAtMillis, now)`: rename the parameter and document
  the meaning.
- Android `DailyTodayResolver`: pass `snapshot.batchFetchedAt` (via a small testable helper).
- Desktop `DesktopDailyForecastModel`: unchanged in behaviour. Desktop's `upsertForecasts` writes a
  row per fetch (no dedup), so `fetchedAt` there *is* last confirmation. Add a comment saying so.
- Selection (`selectPriorDaySnapshot` on `fetchedAt`) is unchanged. Out of scope; the values agree.

## Tests
- Shared: the `isStale` boundary test still holds (pure age math).
- Android unit test for the helper: a row with `fetchedAt` 50h ago and `batchFetchedAt` 10h ago
  is not stale; with both 50h ago it is stale; null snapshot is not stale.
- On-device: install on the Pixel 7 Pro; the today bar is solid.
