# Plan - Fix Missing Forecast Start Label

Allow the forecast line to have a start label even when its index (e.g., idx=0) is already occupied by an actual data label (e.g., LOW/HIGH).

## Objective
Currently, `addCandidate` skips any label that shares an index with an existing candidate. When the first hour (idx=0) is both the "Daily Low" (actual) and the "Forecast Start", only the actual label is added. Since the actual line is often clipped, the forecast line appears without a starting label. This plan allows adding one candidate per series (actual vs. forecast) at the same index.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`**

## Implementation Steps

### 1. Update `addCandidate` logic in TemperatureGraphRenderer
- Modify the conflict check to include the series type:
  ```kotlin
  val conflictingCandidate = specialCandidates.find {
      (it.index == index && it.forceForecastSeries == forceForecastSeries) ||
      (kotlin.math.abs(it.index - index) <= 3 && 
       it.forceForecastSeries == forceForecastSeries &&
       labelTextFor(it.labelTemps, it.index) == candidateText)
  }
  ```
- This allows an "actual" label and a "forecast" label to coexist at the same index.

### 2. Verify Drawing Loop
- The existing drawing loop already handles `forceForecastSeries` correctly to determine which line/series to anchor the label to.
- It also uses `drawnLabelBounds` to prevent visual overlap, so if the labels are the same value and exactly on top of each other, one will still be skipped during the *drawing* phase (which is fine), but if they are different (e.g., because of an applied delta), both can now draw.

## Verification & Testing

### Manual Verification
- Deploy to emulator.
- Observe the hourly temperature graph.
- Verify that the start of the forecast line has a label, even if it overlaps with the beginning of the graph.

### Automated Testing
- Run all unit tests.
- Update `TemperatureGraphLabelPlacementRobolectricTest` to verify that both actual and forecast series can be labeled at the same point if space permits.
