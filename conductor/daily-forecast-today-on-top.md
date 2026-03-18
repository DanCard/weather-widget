# Plan - Fix Daily Forecast "Today" Label Z-Order

Ensure the temperature labels and other elements for "Today" in the daily forecast are rendered on top of other days' elements.

## Objective
The goal is to fix a rendering issue where "Today's" temperature label might be obscured by elements from adjacent days. By rendering "Today" last, we ensure its elements have the highest Z-order.

## Proposed Changes

### 1. `DailyForecastGraphRenderer.kt`
- Refactor the `renderGraph` function to use a two-pass rendering approach.
- Extract the per-day rendering logic into a local function (lambda) to avoid code duplication while allowing it to capture the necessary rendering context (paints, canvas, etc.).
- **Pass 1**: Iterate through the `days` list and render all days where `isToday == false`.
- **Pass 2**: Iterate through the `days` list and render all days where `isToday == true`.
- Maintain the `firstRainShown` flag across both passes to preserve correct rain summary display logic.

## Verification Plan

### Automated Tests
- Run `DailyForecastGraphRendererTest.kt` to ensure no regression in bar types, positions, and counts.
- Add a new test case (if possible) or manually verify that "Today" elements are indeed rendered last by checking the order of `onBarDrawn` callbacks.

### Manual Verification
- Deploy the app to the emulator.
- Observe the daily forecast widget.
- Verify that the "Today" temperature labels (especially the high label) are clearly visible and rendered on top of any potentially overlapping adjacent elements.
