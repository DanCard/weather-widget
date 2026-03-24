# Plan - Unified Temperature and Age Label for Last Fetch Dot

Position the temperature label for the "Last Fetch Dot" to its right, unified with the age text (e.g., "72.1° (12m)"), providing a cleaner look for the hourly graph.

## Objective
The "Last Fetch Dot" is a unique point on the graph where the actuals line ends. Instead of placing its temperature label above or below the line like other points, we will place it to the right of the dot, combined with the staleness indicator (age text).

## Key Files & Context
- **File**: `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
- **Logic**: The "Last Fetch Dot" rendering logic near the end of the `renderGraph` method.
- **Logic**: The `specialCandidates` generation logic which currently adds an "ACTUAL_END" label.

## Implementation Steps

### 1. Refine Fetch Dot Label Logic in TemperatureGraphRenderer
- Locate the "Last Fetch Dot" drawing logic in `TemperatureGraphRenderer.kt`.
- Update the `ageText` generation to include the temperature if available.
  - New format: `${resolvedFetchTemp}° (${ageText})` or just `${resolvedFetchTemp}°` if age text is not available/shown.
- Ensure the label is formatted to one decimal place for consistency (e.g., `String.format("%.1f° (%s)", temp, age)`).
- Adjust the `textY` and `textWidth` calculations to accommodate the longer text.

### 2. Prevent Duplicate Label in main Label Logic
- Locate where `specialCandidates` are generated.
- Ensure that if a "Last Fetch Dot" is being drawn (which now includes the temperature), we skip drawing the standard "ACTUAL_END" temperature label to avoid duplication and clutter.

### 3. Apply similar logic to Precipitation and Cloud Cover (Optional but Recommended)
- Check `PrecipitationGraphRenderer.kt` and `CloudCoverGraphRenderer.kt` to see if they can also benefit from this unified "value + age" label to the right of the fetch dot.

## Verification & Testing

### Manual Verification
- Deploy to an emulator or device.
- Observe the "Last Fetch Dot" on the hourly graph.
- Verify the label appears to the right of the dot as `XX.X° (Ym)`.
- Confirm no separate temperature label is drawn above or below that same dot.

### Automated Testing
- Run `TemperatureGraphLabelPlacementRobolectricTest` and `TemperatureGraphRendererFetchDotTest` to ensure the new label format doesn't break existing logic or assertions.
- Update tests if they specifically check for the old `ACTUAL_END` label position or the old age text format.
