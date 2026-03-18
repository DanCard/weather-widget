# Samsung Daily WAPI Label Overlap

## Summary
- Investigated daily forecast label overlap on the Samsung device in graph mode with WAPI selected.
- Confirmed the problem from a live Samsung screenshot and runtime logs.
- Tried a tiny responsive text-scaling adjustment first; it was too subtle to matter.
- Replaced that with a simpler renderer change: reduce the daily temperature label font size globally, without adding new column-based logic.

## Evidence Collected
- Device verified as Samsung `SM-F936U1`.
- Captured a fresh Samsung home-screen screenshot showing overlap in daily WAPI mode.
- Runtime logs for live widget `327` showed:
  - `widthDp=574`, `heightDp=401`
  - `cols=8`, `rows=5`
  - `mode=GRAPH`
  - `source=WEATHER_API`
- WAPI daily highs in the live render included decimal-heavy values such as `85.1`, `88.1`, and `89.2`, matching the observed crowding in the screenshot.

## Changes Made
- Added a plan file:
  - `plans/260316-samsung-daily-label-minimal-shrink.md`
- First implementation:
  - Added conservative width-based shrink logic for daily graph labels.
  - Result: user reported no visible change; evidence suggested the adjustment was too small.
- Final implementation:
  - Kept day-label behavior unchanged.
  - Reduced the daily temperature label base size in `DailyForecastGraphRenderer` from `11.5f` to `10.5f`.
  - Removed the earlier temp-label width-scaling helper so the change is simple and global.
- Updated unit coverage to keep only the remaining day-label width-scale checks.

## Verification
- `./gradlew test` passed after the final renderer change.
- Samsung visual re-check has not yet been performed after the final global temp-label reduction.

## Notes
- The main lesson from this session is that the original “tiny responsive shrink” did not meaningfully affect the Samsung/WAPI overlap case.
- The current code now follows the narrower user request: just shrink the daily temperature label font a little, without any new column-count clause.
