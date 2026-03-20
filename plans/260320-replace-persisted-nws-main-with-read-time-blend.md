# Replace Persisted `NWS_MAIN` With Read-Time Blend

## Summary
- Stop writing synthetic `NWS_MAIN` rows to `observations`.
- Keep returning the same `List<ObservationEntity>` shape to widget/UI code, but assemble the NWS main row at read time inside the repository layer.
- Explicitly ignore legacy `NWS_MAIN` rows when recomputing daily extremes so the transition is correct immediately, not after retention cleanup.

## Key Changes
- In `ObservationRepository`, remove the `NWS_MAIN` insert from `fetchNwsCurrent()`. Continue storing only raw station observations.
- Add a repository method such as `getMainObservationsWithComputedNwsBlend(lat, lon, sinceMs, nowMs = System.currentTimeMillis())`.
- Add DAO support for:
  - Persisted `_MAIN` rows excluding `NWS_MAIN`
  - Latest real NWS observation per station within the freshness window used by `SpatialInterpolator`
- Build the synthetic in-memory `NWS_MAIN` row from those latest-per-station rows only.
- Set synthetic metadata from underlying data, not `now`:
  - `timestamp` = newest contributing observation timestamp
  - `fetchedAt` = newest contributing row `fetchedAt`
  - `condition` = closest contributing station condition
- Update current-temp read call sites to use the repository method instead of calling the DAO directly. When a worker/provider already loads shared data once, compute this list once and reuse it for all widgets in that pass.
- Update daily-extremes recomputation to exclude legacy `NWS_MAIN` rows before aggregation, preferably in `ObservationResolver.computeDailyExtremes()` so every caller is protected.

## API / Interface Changes
- `ObservationDao` gains one query for persisted main observations excluding `NWS_MAIN`.
- `ObservationDao` gains one query for latest real NWS observation per station.
- `ObservationRepository` gains one read method for "main observations plus computed NWS blend".
- No schema or migration change.

## Test Plan
- Repository test: read-time NWS blend uses only the latest row per station, not multiple rows from the same station.
- Repository test: if no fresh NWS station cohort exists, only persisted non-NWS main rows are returned.
- Repository test: synthetic `NWS_MAIN` preserves expected `timestamp` and `fetchedAt` from source rows.
- Resolver test: `computeDailyExtremes()` ignores legacy `NWS_MAIN` rows.
- Existing widget/current-temp tests updated to use in-memory `NWS_MAIN` rows without assuming persistence.

## Assumptions
- `OPEN_METEO_MAIN`, `WEATHER_API_MAIN`, and `SILURIAN_MAIN` remain persisted because they are direct readings, not computed values.
- Using an in-memory synthetic `ObservationEntity` is acceptable as a compatibility bridge; no new DTO is introduced in this refactor.
- Legacy persisted `NWS_MAIN` rows are allowed to age out naturally once all read and recompute paths ignore them.
