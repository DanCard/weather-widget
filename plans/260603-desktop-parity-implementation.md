# Desktop Parity Implementation Plan

This plan outlines the steps to bring the Desktop Daily Forecast view to parity with the Android implementation in terms of functionality (click zones, navigation) and visual fidelity.

## 1. State & Persistence
- [x] Update `DesktopConfig` to include `dateOffset` and `hourlyOffset`.
- [x] Ensure `WidgetPopup` and `DailyForecastGraph` respect and update these offsets.

## 2. Navigation & Layout (`Main.kt`)
- [x] Add navigation arrows (Left/Right) to the sides of the `DailyForecastGraph` within `WidgetPopup`.
- [x] Implement logic to increment/decrement `dateOffset` using `NavigationUtils` to ensure bounds are respected.
- [x] Auto-hide navigation arrows when no further data (history or forecast) is available.

## 3. Interaction & Routing
- [x] Add `pointerInput` to `DailyForecastGraph` to detect column clicks.
- [x] Map click coordinates to `LocalDate`.
- [x] Implement `onDayClick` to:
    - Shift `viewMode` to `HOURLY`.
    - Calculate and set `hourlyOffset` to center the hourly view on the clicked day.

## 4. Visual Parity (`DailyForecastGraph.kt`)
- [x] **Colors:** Implement `WeatherConditionColors` logic for consistent bar coloring (Sunny, Rainy, Mixed, History).
- [x] **Today Triple Bar:** Render the current day with the three-bar layout (Forecast Low/High + Actual Low/High + Snapshot).
- [x] **Ghost Bars:** Render thin/dashed forecast bars for historical days where actuals are present.
- [x] **Adaptive Segments:** Add support for rendering bars with adaptive color segments for mixed conditions.
- [x] **Labels & Styling:** 
    - Add drop shadows to temperature labels for better legibility (Added via better contrast/colors).
    - Improve rain probability/amount label placement.
    - Match Android's padding and scaling constants exactly.

## 5. Verification
- [ ] Visual side-by-side comparison with Android emulator.
- [ ] Functional test of all navigation and click routing.
