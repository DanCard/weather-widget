# Implementation Plan: Hourly Cloud Cover Dominant Station Label

## Overview
Implement a dominant station reading annotation (e.g. `knuq 44% @ 10:15 am`) for the hourly cloud cover view, specifically for sources where actual cloud cover data is borrowed from a different API (e.g. Silurian, Open-Meteo where actuals are supplied by Synoptic or METAR). The font color will match the actual cloud cover line (`CloudCoverGraphPalette.CURVE_ACTUAL` / `#F5DBE3`).

---

## Design & Architecture

### 1. Dominant Station Cloud Contribution Calculation
- In [`MetarCloudBlender.kt`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/actuals/MetarCloudBlender.kt):
  - When blending candidate timestamps, determine the dominant station contribution at the most recent candidate point (`ts <= nowMs`).
  - The dominant station is the one holding the highest weight (nearest distance $1/d^2$).
  - Store `dominantContribution: BlendContribution?` on `MetarCloudBlender.Result`:
    ```kotlin
    data class Result(
        val hours: Map<Long, Int>,
        val stats: Stats,
        val isMetarBlend: Boolean,
        val dominantContribution: BlendContribution? = null,
    )
    ```
  - For non-synthetic blends (real station observations from Synoptic or METAR), record `dominantContribution` with `stationId`, `rawTemp = rawCloudPercent.toFloat()`, `lastReadingMs`, `isSynthetic = false`.
  - For synthetic model backfills (`HistoricalActualsBackfill`), `dominantContribution` remains `null`.

### 2. Label Formatting in `DominantStationLabel.kt`
- In [`DominantStationLabel.kt`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/graph/DominantStationLabel.kt):
  - Add `formatCloudLabelText(contribution: BlendContribution?, zoneId: ZoneId)`:
    - Extracts `stationId`, `rawCloudPercent = contribution.rawTemp?.roundToInt()`, and `lastReadingMs`.
    - Returns `LabelText` formatted as e.g. `knuq 44% @ 10:15 am` with segments:
      - `knuq ` (Part.STATION)
      - `44%` (Part.TEMPERATURE / value)
      - ` @` (Part.AT)
      - ` 10:15` (Part.TIME)
      - ` am` (Part.AMPM)

### 3. Android Rendering (`:app`)
- **Paints in [`CloudCoverGraphStyle.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/CloudCoverGraphStyle.kt)**:
  - Add paints for dominant station text in cloud cover using `COLOR_CLOUD_ACTUAL_ARGB` (`CloudCoverGraphPalette.CURVE_ACTUAL` = `0xFFF5DBE3`):
    - `dominantStationTextPaint` (station id & `@`/am-pm, ~7.5sp)
    - `dominantValueTextPaint` (cloud %, ~10sp)
    - `dominantTimeTextPaint` (time digits, ~8.5sp)
- **View Handler in [`CloudCoverViewHandler.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt)**:
  - When `ActualsProviderResolver.borrows(effectiveDisplaySource)` is true:
    - Format `dominantStationLabel = DominantStationLabel.formatCloudLabelText(retroActual.dominantContribution)`
    - Pass `dominantStationLabel` to `CloudCoverGraphRenderer.renderGraph(...)`.
- **Placement & Drawing in [`CloudCoverGraphRenderer.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt)**:
  - In `renderGraph`, place `dominantStationLabel` using `DominantStationLabel.place(...)` with curve avoidance (`curveYsAt` sampling forecast and actual curves) and label collision detection against `drawnLabelBounds`.
  - Draw the multi-segment label in color `COLOR_CLOUD_ACTUAL_ARGB` and register its bounds in `drawnLabelBounds`.

### 4. Desktop Rendering (`:desktop`)
- **Data Pipeline in [`DesktopWeatherRepository.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt)**:
  - Pass `dominantCloudContribution: BlendContribution? = retroCloudResult.dominantContribution` into `ForecastSnapshot`.
- **Graph Drawing in [`CloudCoverGraph.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/CloudCoverGraph.kt)**:
  - When `ActualsProviderResolver.borrows(source)` is true:
    - Format `dominantLabel = DominantStationLabel.formatCloudLabelText(dominantCloudContribution)`.
    - Measure annotated string with `COLOR_CLOUD_ACTUAL` (`CloudCoverGraphPalette.CURVE_ACTUAL`).
    - Place with `DominantStationLabel.place(...)` and draw via `drawText`.
    - Register bounds in `drawnLabels` to prevent overlap.

---

## Unit Testing & Verification Plan

1. **Unit Tests**:
   - `MetarCloudBlenderTest`: Verify `dominantContribution` is captured with correct station ID, cloud %, and reading timestamp for real station blends, and null for synthetic backfills.
   - `DominantStationLabelTest`: Verify `formatCloudLabelText` formats e.g. `knuq 44% @ 10:15 am` with correct segments.
   - `CloudCoverGraphRendererTest` / `RobolectricTest`: Verify dominant station label is placed and drawn in `CloudCoverGraphRenderer` when `dominantStationLabel` is provided.
   - Run `./scripts/unit-tests.sh` to ensure all tests pass.

2. **On-Device / Desktop Verification**:
   - **Android Emulator (`emulator-5554`) & Samsung**:
     - Switch view mode to Hourly Cloud Cover.
     - Under Silurian / Open-Meteo, confirm the small dominant station text (e.g. `knuq 44% @ 10:15 am`) appears in pale pink (`#F5DBE3`) matching the actual cloud curve.
     - Under NWS (where actuals come from the same API), confirm no borrowed dominant station text appears.
     - Take a screenshot via `adb exec-out screencap` and visually verify.
   - **Desktop App**:
     - Launch Desktop app, switch to Cloud Cover view under Silurian.
     - Verify dominant station text renders in pale pink.
