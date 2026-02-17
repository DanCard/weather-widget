# Track T-UI-02: Change 'Today' label to day name and highlight

## Problem Description
The daily forecast graph currently labels the current day as "Today". The user prefers to see the abbreviated day of the week (e.g., "Tue") instead, and wants this label to be highlighted (bright white or distinct color) to differentiate it.

## Implementation Plan
- [x] **Modify `DailyViewHandler.kt`**:
    -   Update `buildDayDataList` to use `dayOfWeek.getDisplayName` instead of returning "Today".
    -   Update `updateTextMode` (and `getLabelForDate`) to do the same for the text-only view.
- [x] **Modify `DailyForecastGraphRenderer.kt`**:
    -   Create a `todayTextPaint` with a brighter color (e.g., `#FFFFFF` or `#FF9F0A`).
    -   Update drawing logic to use `todayTextPaint` when `day.isToday` is true.
- [x] **Iterate (Orange Tint)**:
    -   Change `todayTextPaint` color to a light orange (e.g., `#FFD080`).
    -   Add `todayTempTextPaint` with the same tint.
    -   Apply `todayTempTextPaint` to high/low labels for the current day.
- [x] **Iterate (Brighter)**:
    -   Average `#FFD699` with White -> `#FFEACC`.
    -   Update `todayTextPaint` and `todayTempTextPaint`.
- [x] **Verify**:
    -   Build and install.
    -   Check that today's label shows the day name (e.g. "Tue") and is brighter than other days.
