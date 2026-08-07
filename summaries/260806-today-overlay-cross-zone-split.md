# Summary: Today-Column Overlay Cross-Zone Split

Date: 2026-08-06

## Summary of Changes

Implemented the Today-column overlay cross-zone split planning specified in [`plans/260806-today-overlay-cross-zone-split-kimi.md`](file:///home/dcar/projects/weather-widget/plans/260806-today-overlay-cross-zone-split-kimi.md).

1. **Cross-Zone Split Candidates (`TodayColumnOverlayPlanner.kt`)**:
   - Added support for 2-group cross-zone split placements across valid top-to-bottom zone pairs:
     - `(ABOVE, BELOW)` – clean split above and below the bars
     - `(ABOVE, ON_COLUMN)` – head clean above the bars, tail overlaying the column
     - `(ON_COLUMN, BELOW)` – head overlaying the column, tail clean below the bars
   - Seam selection when multiple seams fit prioritizes keeping fewer rows on the forecast bars.

2. **Candidate Search Order – Content Completeness First (`TodayColumnOverlayPlanner.kt`)**:
   - Updated search sequence per variant to evaluate content completeness before dropping content rows:
     1. Same-zone clean (`ABOVE`, `BELOW`)
     2. Clean split `(ABOVE, BELOW)`
     3. Split with bars `(ABOVE, ON_COLUMN)`, `(ON_COLUMN, BELOW)`
     4. Same-zone `ON_COLUMN` (whole stack on bars)
   - If no configuration fits for the richest variant, the search degrades to the next variant (dropping a row).

3. **Prevent Last-Resort Zone Persistence**:
   - Added `fromLastResort` flag to `TodayColumnOverlayPlanner.Layout`.
   - Updated desktop (`DailyForecastGraph.kt`) and Android (`DailyGraphRenderer.kt`) to skip saving hysteresis zone memos when placements originate from `lastResort`.

4. **Tests (`TodayColumnOverlayPlannerLayoutTest.kt`)**:
   - Added tests verifying the desktop geometry layout (splitting `delta` `ABOVE` and `dominant_temp_age` `ON_COLUMN`), clean split preference order, content completeness over row dropping, inverted pair exclusion, and `fromLastResort` behavior.

## Verification

- `./gradlew :shared:test :desktop:test` – Passed (681 tests passing).
- `./gradlew :app:testDebugUnitTest` – Passed.
