# Plan: Desktop Temperature Graph Parity with Android

This document outlines the design and implementation steps required to bring the Compose-based desktop temperature graph (`TemperatureGraph.kt`) to visual and functional parity with the Android widget's custom rendering (`TemperatureGraphRenderer.kt`).

---

## 1. Current Discrepancies

While the current desktop `TemperatureGraph` renders a smooth Bezier curve using Skia, it lacks several key visual details and logical refinements present in the Android widget:

| Feature | Android Widget (`TemperatureGraphRenderer`) | Desktop App (`TemperatureGraph.kt`) |
| :--- | :--- | :--- |
| **Forecast Line Coloring** | Segment-colored based on weather conditions (sunny/rainy/night/twilight) via `WeatherConditionColors`. | Uses a single vertical temperature gradient. |
| **"Ghost" Forecast Line** | Draws a dashed expected forecast curve shifted by `appliedDelta` to visualize deviation when observations differ from forecast. | Not supported; only shows solid/dashed forecast line without delta adjustments. |
| **Curve-Aware Label Placement** | Uses curve intrusion detection (`combinedCurveIntrusion`) to push labels above/below the Bezier path dynamically. | Simple rectangle-intersection checking; labels can overlap the curve itself. |
| **Leader Lines** | Draws vertical connector lines from shifted labels to their corresponding points on the temperature curve. | Not supported. |
| **Icon Tinting** | Tints bottom-row weather icons dynamically based on day/night/twilight status. | Renders untinted resource drawables. |
| **Time Indicator Guide** | Dashed vertical guide line at the current time (`now`). | Solid vertical line with low opacity. |

---

## 2. Implementation Workstreams

### Task 1: Implement Segment-Colored Forecast Curve
To mirror Android, the forecast curve must be colored according to the hourly condition rather than a vertical temperature gradient.
* **Algorithm**:
  * Instead of drawing the forecast line as one long path, construct individual segment paths between adjacent hours (already computed on Skia via Catmull-Rom or cubic Bezier segments).
  * Map each hourly point to its condition flags: `isSunny`, `isRainy`, `isMixed`, `isNight`, and `isTwilight`.
  * Define or reuse a Kotlin-native equivalent of `WeatherConditionColors.forecastColor()` for the Compose desktop module.
  * Draw each path segment with a dashed pattern and its condition-dependent color.

### Task 2: Implement "Ghost" Expected Line & Delta Adjustments
When actual observations deviate from the forecast, Android draws a ghost/expected line shifted by `appliedDelta`.
* **Algorithm**:
  * Retrieve the computed `anchorDelta` / `appliedDelta` from the data models.
  * If the delta is significant (e.g., $\ge 0.1^\circ\text{F}$), build the `expectedPath` (forecast points shifted vertically by the delta).
  * Clip the drawing region to the right of the `transitionX` (observations transition point) and draw the expected/ghost curve using a white dashed paint with low opacity (`GHOST_ALPHA`).

### Task 3: Curve intrusion & Dynamic Label Positioning (Dynamic Alignment)
Avoid overlapping the temperature curve or other labels.
* **Algorithm**:
  * Port the curve intrusion calculation logic from Android: check for segments intersecting the bounding box of text labels (`curveIntrusionInLabel`).
  * Determine if a label should be placed above or below the curve based on the local curvature (e.g. peak/valley).
  * If a label collides with the curve, shift it vertically until it clears the path by a safe margin, up to a maximum displacement limit.
  * If a label is displaced, draw a thin, vertical leader line (`actualLeaderLinePaint` or `forecastLeaderLinePaint`) connecting the center of the label back to its curve point.

### Task 4: Dynamic Icon Tinting & Guide Lines
* Apply tints to the footer weather icons:
  * Night: `#8A8A8F` (or corresponding low-light tint).
  * Twilight: `#C5C5C7`.
  * Day: Clear/no tint (full colors).
* Convert the vertical "now" marker guide line from solid to a dashed line pattern.

---

## 3. Recommended Code Updates

### Phase A: `DesktopWeatherService` & shared helpers
Ensure we expose condition flags (such as night/twilight/rainy/sunny) on the `HourlyForecast` model to support styling.

### Phase B: DrawScope extensions in `TemperatureGraph.kt`
Create helper drawing functions within `TemperatureGraph.kt`:
* `DrawScope.drawForecastSegments(...)`
* `DrawScope.drawGhostLine(...)`
* `DrawScope.resolveLabelPlacements(...)` with curve avoidance.
* `DrawScope.drawLeaderLine(...)`

---

## 4. Verification Plan

1. **Visual Parity Audit**:
   * Inspect the popup graph in the desktop app (`./gradlew :desktop:run`) to verify:
     * Color segment transitions on the dashed forecast curve.
     * The ghost line appears correctly when a temperature offset is active.
     * Labels are pushed away from the Bezier curve and connected with leader lines.
     * Icons are tinted appropriately for night/twilight hours.
2. **Regression Testing**:
   * Run the test suite: `./gradlew :desktop:test` to ensure no UI layouts or window lifecycle behaviors are broken.
