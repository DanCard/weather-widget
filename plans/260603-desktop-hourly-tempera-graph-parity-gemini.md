# Desktop Hourly Graph Parity Plan

## Objective
Bring the desktop hourly temperature graph up to parity with the Android widget implementation. This includes implementing header row functionality (delta, precipitation probability), adding the ability to zoom (NARROW vs WIDE), and ensuring visual and functional consistency.

## Scope & Impact
- **Affected Module**: `desktop/src/main/kotlin/com/weatherwidget/desktop/`
- **Key Files**: 
  - `DesktopConfig.kt` (State management)
  - `Main.kt` (`WidgetHeader`, `WidgetPopup`)
  - `TemperatureGraph.kt` (Rendering logic)
- **Impact**: Enhances the desktop app's hourly view to match the rich data and interactability of the Android widget without affecting shared business logic or the Android app itself.

## Proposed Solution

### 1. State Management (`DesktopConfig.kt`)
- Add a new property `val zoomLevel: String = "WIDE"` to `DesktopConfig`.
- This ensures the user's zoom preference is persisted and drives the Compose UI.

### 2. Header Parity (`Main.kt` -> `WidgetHeader`)
- Currently, the desktop header shows Current Temp, Condition, API Source, Date, Location, and View Mode chips (H/D).
- **Updates Required**:
  - Add **Delta Temperature** (e.g., "+2°" or "-1°") next to the current temperature. We will need to compute this by comparing the current temp to a recent baseline or by passing it down if it's already computed in `DesktopDailyForecastModel` or similar. *(Note: Need to verify how Android computes `deltaText` for the header and replicate that logic in the desktop data extraction).*
  - Add **Precipitation Probability** (e.g., "30%") if > 0, mimicking the Android header's behavior.
  - Add a **Zoom Toggle**. The Android app allows tapping specific zones to cycle zoom. For the desktop UI, we can add a clickable element (e.g., a "🔍" icon or a text button like "WIDE" / "NARROW") in the header or near the view mode chips to allow the user to toggle `zoomLevel` between "WIDE" and "NARROW".

### 3. Graph Parity (`TemperatureGraph.kt`)
- The current desktop graph hardcodes `WIDE_BACK_HOURS = 12` and `WIDE_FORWARD_HOURS = 12`.
- **Updates Required**:
  - Accept the current `zoomLevel` string (or a resolved enum/sealed class) as a parameter in the `TemperatureGraph` composable.
  - Dynamically calculate the window bounds based on the zoom level:
    - **WIDE**: `backHours = 12`, `forwardHours = 12`, `labelInterval = 4` (or 6 if narrow width, though desktop is usually fixed/resizable), `smoothIterations = 3`.
    - **NARROW**: `backHours = 2`, `forwardHours = 2`, `labelInterval = 1`, `smoothIterations = 1`.
  - Ensure the X-axis mapping (`xAtTime`) and the visible points list adapt correctly to these dynamic bounds.
  - Apply the `smoothIterations` to the Catmull-Rom curve building logic if the current implementation (`buildCurve`) doesn't already support iterative smoothing. (Android's `GraphRenderUtils` uses `smoothIterations` to subdivide the curve).

## Implementation Steps

1.  **Update Config**: Add `zoomLevel` to `DesktopConfig` and `DesktopConfigStore`.
2.  **Extract Header Data**: Update the data extraction in `Main.kt` (perhaps creating a dedicated `DesktopHourlyViewState` or passing the required fields) to supply `delta` and `precipProbability` to `WidgetHeader`. Use `DesktopTemperatureInterpolator` or similar existing logic to compute the delta if not already available in `ForecastResult`.
3.  **Update `WidgetHeader`**: Add the UI elements for Delta, Precip, and the Zoom toggle button. Wire the Zoom toggle to `onUpdateConfig` to cycle the `zoomLevel`.
4.  **Update `TemperatureGraph`**: Modify the composable signature to accept `zoomLevel`. Implement the dynamic window bounds (back/forward hours) and label intervals based on the selected zoom state.
5.  **Refine Smoothing**: Check `TemperatureGraph.buildCurve` and implement iterative smoothing if needed to match Android's `smoothIterations` behavior.

## Verification
- Run the desktop app (`./gradlew :desktop:run`).
- Navigate to the Hourly view.
- Verify the header displays the correct Current Temp, Delta, and Precipitation Probability.
- Click the Zoom toggle and verify the graph smoothly transitions between the NARROW (5-hour window, every hour labeled) and WIDE (25-hour window, sparse labels) views.
- Ensure the center offset navigation (left/right arrows) continues to function correctly in both zoom levels.