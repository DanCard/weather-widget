# Fix Missing Peak Label on Hourly Graph

## User Feedback Integration
The user noted that there is visually "tons of space at the top" of the widget, so the `bounds.top >= 0f` check is likely overly restrictive or incorrectly preventing the label from drawing. We will remove this check to allow the peak label to render in that available space.

## Objective
Ensure the peak temperature label is consistently displayed on the hourly forecast graph by relaxing the overly strict top boundary check and fixing the plateau detection logic.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

## Background
Currently, the peak of the forecast graph often goes unlabeled. This occurs because the primary attempt to place the label above the peak (`Attempt 1`) fails the `bounds.top >= 0f` check. Even though there is visual space at the top of the widget, the internal coordinate system causes this check to fail for the absolute peak. 

When Attempt 1 fails, it falls back to Attempt 2 (placing the label below the line). However, on vertically constrained graphs, placing the label below pushes it into the icon area at the bottom (`drawnIconBounds`), causing Attempt 2 to also fail. Consequently, the label is completely omitted.

Additionally, the `isPeak` logic evaluates to false for local extrema that form a plateau, causing the algorithm to incorrectly prefer drawing below the line.

## Implementation Steps

1. **Remove `bounds.top >= 0f` Check**:
   - In `TemperatureGraphRenderer.kt`, modify the label placement condition to remove `bounds.top >= 0f`. This allows the text to be drawn above the peak, utilizing the available visual space without being artificially constrained by the `0f` coordinate boundary.
   - Updated condition:
     ```kotlin
     if (bounds.bottom <= heightPx && drawnLabelBounds.none { RectF.intersects(it, bounds) } && drawnIconBounds.none { RectF.intersects(it, bounds) }) {
     ```

2. **Improve `isPeak` and `isValley` Plateau Logic**:
   - Update the local extremum peak detection in the label drawing loop to look past identical values (plateaus) to properly identify if it's a peak or valley:
     ```kotlin
     val leftVal = smoothedLabelTemps.subList(0, idx).findLast { it != smoothedLabelTemps[idx] } ?: 0f
     val isPeak = (idx == dailyHighIndex || (idx in significantLocalExtrema && smoothedLabelTemps[idx] > leftVal))
     ```

## Verification
- Run the emulator to verify that the highest point on the hourly graph is successfully labeled.
- Verify that the label appears above the peak in the visual space at the top of the widget.