# Plan - Remove Horizontal Padding from Hourly Graphs

Remove the bar-chart style half-hour padding on the left and right edges of the hourly Temperature, Precipitation, and Cloud Cover graphs to allow the curves to span the full width of the widget.

## Objective
The current graph implementation uses `hourWidth / 2f` as an offset for the first point, which was appropriate for bar charts but creates unwanted "padding" for continuous line/curve graphs. This plan removes that offset and adjusts the `hourWidth` calculation so the first point starts at `x=0` and the last point ends at `x=widthPx`.

## Key Files & Context
- **`TemperatureGraphRenderer.kt`**
- **`PrecipitationGraphRenderer.kt`**
- **`CloudCoverGraphRenderer.kt`**
- **`GraphRenderUtils.kt`**: Used for "NOW" line and "Last Fetch Dot" placement.

## Implementation Steps

### 1. Update TemperatureGraphRenderer
- Change `hourWidth` calculation:
  ```kotlin
  val hourWidth = widthPx.toFloat() / timeRangeHours.coerceAtLeast(1f)
  ```
- Update `x` coordinate calculation for points:
  ```kotlin
  val x = ((pointEpoch - minTimeEpoch) / 3600f) * hourWidth
  ```
- (Remove the `hourWidth / 2f +` part).

### 2. Update PrecipitationGraphRenderer
- Change `hourWidth` calculation:
  ```kotlin
  val hourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)
  ```
- Update `x` coordinate calculation for points:
  ```kotlin
  val x = index * hourWidth
  ```
- (Remove the `+ hourWidth / 2f` part).

### 3. Update CloudCoverGraphRenderer
- Change `hourWidth` calculation:
  ```kotlin
  val hourWidth = widthPx.toFloat() / (hours.size - 1).coerceAtLeast(1)
  ```
- Update `x` coordinate calculation for points:
  ```kotlin
  val x = index * hourWidth
  ```
- (Remove the `+ hourWidth / 2f` part).

## Verification & Testing

### Manual Verification
- Deploy to emulator.
- Observe the hourly graphs in both Wide and Narrow zooms.
- Verify that the graph line starts exactly at the left edge and ends exactly at the right edge.
- Verify that the "NOW" line and "Last Fetch Dot" are still correctly positioned relative to the curve.

### Automated Testing
- Run all unit tests.
- **Note**: Many tests specifically check for `hourWidth / 2f` offsets or specific X coordinates. These tests WILL fail and must be updated to the new "zero-padded" expectations.
- Update `TemperatureGraphRendererContinuityTest`, `TemperatureGraphJunctionTest`, `TruthCurveLinearRenderingTest`, etc.
