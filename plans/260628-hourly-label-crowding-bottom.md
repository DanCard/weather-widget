# Hourly graph: prevent overlapping/crowded bottom hourly labels and icons

## Context

On emulator-5556 (and potentially other screen sizes), the hourly labels and weather icons at the bottom of the 24-hour hourly temperature graph (and other hourly graphs) are crowded or overlapping. The user requested:
1. Remove the weather indicator icon when heavy overlap is detected.
2. Or show fewer hourly labels (by increasing the spacing/interval).

Currently, `GraphRenderUtils.drawHourLabels` uses a fixed `minHourLabelSpacing` threshold to decide whether to show a label. However:
- Inline icons (`<hour><a|p><icon>`) take significant extra horizontal space.
- Labels near the edges are clamped inward, which can push them into neighboring labels.
- This results in visual overlap/crowding when the canvas width is narrow relative to the number of labeled hours.

## Proposed Change

We will introduce an **adaptive layout simulation** inside the shared `GraphRenderUtils.drawHourLabels` function in [GraphRenderUtils.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt).

### Adaptive Algorithm
Before drawing any labels, we will run a simulation pass with a list of layout configurations.
We will try the following configurations in order of preference:
1. Standard `minHourLabelSpacing`, with icons enabled.
2. Standard `minHourLabelSpacing`, with icons disabled.
3. Increased spacing (`minHourLabelSpacing * 1.4f`), with icons enabled.
4. Increased spacing (`minHourLabelSpacing * 1.4f`), with icons disabled.
5. Further increased spacing (`minHourLabelSpacing * 1.8f`), with icons enabled.
6. Further increased spacing (`minHourLabelSpacing * 1.8f`), with icons disabled.
7. Maximum spacing (`minHourLabelSpacing * 2.2f`), with icons disabled.

For each configuration, we will:
1. Simulate the exact layout positions and boundaries of each label group (text + icon gap + icon size).
2. Check if any adjacent label groups overlap or are too close (e.g. gap < 3dp).
3. The first configuration that has **no overlaps** will be chosen. If all configurations have overlaps, we fall back to the last one (least dense).

### Implementation Details in `GraphRenderUtils.kt`
- Define a private helper method `checkOverlap(...)` that simulates the rendering of labels and returns `true` if there is any overlap or gap < 3dp.
- In `drawHourLabels`, evaluate the configurations to find the optimal `effectiveSpacing` and `drawIconsEnabled`.
- Use the selected `effectiveSpacing` for checking spacing between labels, and `drawIconsEnabled` to decide whether to draw the weather icon.

This ensures all hourly graphs (Temperature, Precipitation, Cloud Cover) automatically inherit the layout refinement, maintaining consistency across the widget.

## Verification

### 1. JVM Unit Tests
Run existing tests to ensure no regressions:
- `./gradlew test`
- Focus on tests in `com.weatherwidget.widget.handlers` and `com.weatherwidget.widget` related to graph rendering.

### 2. On-Device/Emulator Verification
- Look at the emulator (emulator-5556 or similar) on the 24-hour view.
- Take a screenshot using `adb` to visually confirm that the labels and icons at the bottom are clean, non-overlapping, and beautifully spaced.
