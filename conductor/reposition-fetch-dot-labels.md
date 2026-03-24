# Plan - Reposition Fetch Dot Labels (Multi-Directional Placement)

Reposition the "Last Fetch Dot" labels for better clarity: move the staleness indicator (age) underneath the dot and the value (temperature/probability) to the side (preferring right, then left, then top).

## Objective
The current fetch dot label is a unified string to the right/left. This plan breaks it into two independent components:
1.  **Staleness**: Placed centered underneath the dot.
2.  **Value**: Placed to the side, with a priority sequence: Right -> Left -> Top.

## Key Files & Context
- **`TemperatureGraphRenderer.kt`**
- **`PrecipitationGraphRenderer.kt`**
- **`CloudCoverGraphRenderer.kt`**

## Implementation Steps

### 1. Refactor Fetch Dot Logic in TemperatureGraphRenderer
- **Staleness (Age)**:
  - Format without parentheses: e.g., "12m".
  - Position: `x = clampedFetchX`, `y = fetchY + dotRadius + 4dp`, `textAlign = CENTER`.
  - Ensure it stays within `heightPx`.
- **Value (Temp)**:
  - Attempt 1 (Right): `x = clampedFetchX + dotRadius + 4dp`, `textAlign = LEFT`.
  - Attempt 2 (Left): If Attempt 1 exceeds `widthPx`, use `x = clampedFetchX - dotRadius - 4dp`, `textAlign = RIGHT`.
  - Attempt 3 (Top): If Attempt 2 goes below 0, use `x = clampedFetchX`, `y = fetchY - dotRadius - 2dp`, `textAlign = CENTER`.
- Update `FetchDotDebug` to include the final placement choices for testing.

### 2. Apply Consistency to Precipitation and Cloud Cover
- Use identical multi-directional logic for `%` values and age indicators.
- Maintain the smaller font sizes established in the previous task (11dp value, 8dp age).

### 3. Update Affected Tests
- **`TemperatureGraphRendererStalenessTest.kt`**: Update `verify` calls to look for standalone strings like "60°" and "25m" (no parentheses).
- **`TruthCurveLinearRenderingTest.kt`**: Update the expected `ageText` debug format.
- **`TemperatureFetchDotIntegrationTest.kt`**: Update assertion for the new debug label format.

## Verification & Testing

### Manual Verification
- Deploy to emulator.
- Verify "Value" appears to the right of the dot.
- Verify "Age" (e.g., 12m) appears small and centered below the dot.
- Test edge cases: Move "Now" to the far right of the graph to trigger the Left and Top fallback logic.

### Automated Testing
- Run all 517 unit tests and 156 emulator tests.
