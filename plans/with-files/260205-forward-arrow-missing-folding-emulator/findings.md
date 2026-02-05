# Findings & Decisions

## Requirements
- Diagnose missing forward arrow on folding phone.
- Issue occurs after hitting back arrow (history navigation).
- Determine if tests should be created.
- API: NWS.
- Use Teach and Explain mode.
- Get explicit consent before execution.

## Research Findings
- **Navigation Logic Discrepancy:** There is a mismatch between how many days are displayed in the graph (`buildDayDataList`) and how the navigation bounds are calculated (`setupNavigationButtons` and `handleDailyNavigationDirect`).
- **Graph Offsets:** For larger widgets (folding phones), `numColumns` can be 8 or more. 
    - `buildDayDataList` caps the display at 7 days (offsets -1 to +5 relative to center).
    - `setupNavigationButtons` calculates `maxOffset = numColumns - 2`. For 10 columns, `maxOffset = 8`.
- **The Bug:** 
    - `canRight` checks if we have data for `centerDate + 1 + maxOffset`.
    - If `numColumns = 10`, it checks for `centerDate + 9`.
    - If the user moves to the past (e.g., `dateOffset = -1`), `centerDate = today - 1`.
    - `canRight` checks for `(today - 1) + 1 + 8 = today + 8`.
    - Since forecast data typically only goes up to `today + 6` (for NWS), `canRight` is `false`.
    - Result: The user moves back one day, and the forward arrow disappears because the navigation logic thinks we need 8 days of future data to move forward, even though the graph only shows 5 days of future data.

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Unify offset logic | Both navigation and display logic must use the same calculation for the day range. |
| Add tests | This logic is complex enough to benefit from unit tests for the offset calculation. |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Navigation bounds mismatch | Identified discrepancy between `maxOffset` and `dayOffsets`. |

## Resources
- `WeatherWidgetProvider.kt`: Contains both `setupNavigationButtons` and `buildDayDataList`.

## Visual/Browser Findings
- (No images viewed yet, but codebase analysis is conclusive.)