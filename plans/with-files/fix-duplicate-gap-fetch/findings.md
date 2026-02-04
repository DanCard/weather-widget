# Findings - Fix Duplicate Gap Data Fetch

## Research & Discoveries
- Current issue: Gap data fetching is triggered inside `fetchFromNws` and `fetchFromOpenMeteo`, leading to duplicate fetches.
- Gap data should be generic and fetched once.
- `fetchClimateNormalsGap` uses `WidgetStateManager.SOURCE_GENERIC_GAP`.
- `getCachedDataBySource` and `getForecastForDateBySource` correctly prioritize specific sources over `GENERIC_GAP`.
- **Decision**: Gap data (climate normals) is static and should **not** be saved to the `forecast_snapshots` table. It only needs to be in the `weather` table for UI display.

## Refactoring Plan
1. Remove `fetchClimateNormalsGap` calls from `fetchFromNws` and `fetchFromOpenMeteo`.
2. Add a single `fetchClimateNormalsGap` call in `getWeatherData` after both APIs are tried.
3. Use the minimum `lastDate` from successful API results to ensure all potential gaps are covered.
4. Save the gap data only to `weatherDao`.
5. Update `saveForecastSnapshot` to explicitly filter out `isClimateNormal` entries.

## File Analysis
| File | Purpose | Notes |
|------|---------|-------|
| `WeatherRepository.kt` | Core logic for fetching and saving weather data. | Main target for refactoring. |
