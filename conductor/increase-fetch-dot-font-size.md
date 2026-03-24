# Plan - Increase Font Size for Fetch Dot Labels

Match the font size of the "Last Fetch Dot" labels with the standard labels in each graph type for a consistent and readable display.

## Objective
The current observation label (next to the fetch dot) is currently smaller than the other labels on the graph. This plan increases its font size to match the standard labels of the respective graph type.

## Key Files & Context
- **`TemperatureGraphRenderer.kt`**: Standard temperature labels are `19.5f`.
- **`PrecipitationGraphRenderer.kt`**: Standard percent labels are `11.0f`.
- **`CloudCoverGraphRenderer.kt`**: Standard percent labels are `11.0f`.

## Implementation Steps

### 1. Update TemperatureGraphRenderer
- Locate the `ageTextPaint` initialization in the "Last Fetch Dot" logic.
- Change `textSize` from `dpToPx(context, 10f * labelScale)` to `dpToPx(context, 19.5f * labelScale)`.

### 2. Update PrecipitationGraphRenderer
- Locate the `ageTextPaint` initialization in the "Last Fetch Dot" logic.
- Change `textSize` from `dpToPx(context, 10f * labelScale)` to `dpToPx(context, 11f * labelScale)`.

### 3. Update CloudCoverGraphRenderer
- Locate the `ageTextPaint` initialization in the "Last Fetch Dot" logic.
- Change `textSize` from `dpToPx(context, 10f * labelScale)` to `dpToPx(context, 11f * labelScale)`.

## Verification & Testing

### Manual Verification
- Deploy to an emulator or device.
- Observe the hourly graphs for Temperature, Precipitation, and Cloud Cover.
- Verify that the label next to the fetch dot (e.g., `72.1° (12m)`) has the same font size as the peak/valley labels on the same graph.

### Automated Testing
- No changes to existing tests are expected, but run `TemperatureGraphRendererFetchDotTest` to ensure no unexpected failures.
