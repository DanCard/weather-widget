# Plan: Prioritize Lower Dip Label for Below-Graph Placement

The temperature graph currently labels both actual and forecast extrema. When multiple "dip" labels (valleys) are near each other, they compete for the preferred "below" position. Currently, the first label in index order (usually forecast) takes the spot, forcing the lower actual dip label above the line or to displace. We will update the placement priority so that the lower temperature value always gets priority for the "below" position.

## Objective
- Ensure the lowest temperature dip (valley) is prioritized for placement below the graph line.
- Reduce visual noise on the actuals line by suppressing non-essential "local" labels (e.g., intermediate fluctuations like 55° and 53°).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Main rendering logic.

## Implementation Steps

### 1. Define Placement Priority Logic (Done)
- Sorted `specialCandidates` by role and temperature value.

### 2. Suppress Noise on Actuals Line (Done)
- Skip `LOCAL` extrema on the actuals line.

### 3. Center Labels Over Plateaus (Dips/Peaks)
- Update the horizontal position (`sx`) calculation in `placeTemperatureLabels`.
- Ensure `FORECAST_LOW` and `LOCAL` roles use `centerOfRun` just like `LOW` and `HIGH` do.
- This ensures that if a dip spans multiple hours (a plateau), the label is centered over the entire flat section instead of just the first point.

### 4. Cleanup Debug Code (Postponed)
- Note: User requested to keep the current logging for now.

## Verification & Testing
- **Visual Check**: Deploy to emulator and verify:
    - 55° and 53° labels are gone (noise reduction).
    - 50.4° low remains and is placed below the line (priority fix).
    - 51° forecast label is centered over its dip (plateau centering fix).

## Migration & Rollback
- UI-only change. Rollback via code revert.
