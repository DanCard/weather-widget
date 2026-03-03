## Implement NWS-First Actuals in Forecast History (No Forecast-as-Actual Bug)

### Summary
Implement the fix now as: in Forecast History, when source is NWS and date is past, compute `actual` from NWS forecast-endpoint-derived daily values first, then fallback to NWS observations only. Never label generic forecast snapshots as actual for this path.

### Behavior Spec
1. Trigger scope:
- `ForecastHistoryActivity`
- `requestedSource == NWS`
- `targetDate < today`

2. Actual resolution order:
- Priority 1: NWS daily value from `forecasts` table for `targetDate`/location, choosing the latest usable NWS row.
- Priority 2: Aggregate NWS-only rows in `observations` for that local date (`max(temp)` / `min(temp)`).
- Priority 3: No actual shown.

3. Prohibitions:
- Do not use non-NWS observations for NWS actual fallback.
- Do not use `current_temp` for daily high/low.
- Do not use generic source-specific forecast fallback labeled as actual in this NWS path.

### Code Changes

1. `ForecastHistoryActivity.kt`
- Add private resolver:
  - `resolveNwsActualForPastDate(targetDate, lat, lon): ForecastEntity?`
- Update `loadData(...)`:
  - For `ActualLookupMode.SOURCE_SPECIFIC` + NWS, call new resolver.
  - Keep existing behavior unchanged for non-NWS sources.
- Keep `actual_temps_text` rendering path; ensure hidden when resolver returns null.

2. `ObservationDao.kt` (if needed for clean filtering)
- Add query helper for date+location window to avoid repeated conversion logic, or reuse existing range method.
- Filter NWS-only in Kotlin:
  - include station IDs not starting with `OPEN_METEO_` and not starting with `WEATHER_API_`.

3. Optional helper extraction
- If activity gets too large, extract resolver into `ui/history/ActualWeatherResolver.kt` (internal object).

### Selection Rules (Decision-Complete)
- NWS forecast-derived candidate set:
  - `source='NWS'`, matching `targetDate` and location tolerance.
  - Sort `fetchedAt DESC`.
  - Use first row with both high and low present.
  - If none have both, proceed to fallback (no partial actual display).
- Observation fallback:
  - Build local-day `[start, end)` epoch window in system zone.
  - Query observations in range/location.
  - Keep NWS-only rows by station-id prefix filter.
  - Require at least 1 row; compute `high=max`, `low=min`.
  - Require both values (always true if at least one row).

### Tests

1. New unit tests:
- `ForecastHistoryActualResolverTest`
- Cases:
  1. Uses NWS forecast-derived actual when complete.
  2. Falls back to NWS observations when NWS forecast-derived is incomplete.
  3. Excludes non-NWS observation rows.
  4. Returns null when no usable NWS data.
  5. Non-NWS source behavior unchanged.

2. Regression scenario:
- Recreate Saturday mismatch pattern:
  - NWS forecast high 77.
  - Non-NWS observed spike 80.9.
  - NWS mode must not show non-NWS value as actual.

### Acceptance Criteria
- In NWS history for past days, “actual” is no longer sourced from generic forecast snapshot logic.
- NWS-first actual resolution works with NWS-only fallback.
- If unavailable, actual line is hidden.
- Existing non-NWS history behavior is unchanged.

### Assumptions
- NWS forecast-endpoint-derived day values in `forecasts` are the preferred truth for NWS mode.
- `observations` table is valid fallback source for daily highs/lows; `current_temp` is not.
