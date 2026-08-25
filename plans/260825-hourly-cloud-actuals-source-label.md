# Implementation Plan: Hourly Cloud Cover Actuals Source API Label

## Overview
For hourly cloud cover on sources that borrow actual data from a different API (e.g., Silurian or Open-Meteo where actual cloud cover is sourced from Synoptic or METAR), display small text:
`"Actual cloud cover data from <source>"` (e.g., `"Actual cloud cover data from Synoptic"` or `"Actual cloud cover data from METAR"`), rendered in the actual cloud curve font color (`CloudCoverGraphPalette.CURVE_ACTUAL` / `#F5DBE3`).

---

## Architecture & Implementation

### 1. Label Formatting in `DominantStationLabel.kt`
- Add `formatCloudSourceLabelText(sourceName: String?): LabelText?` to [`DominantStationLabel.kt`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/graph/DominantStationLabel.kt):
  ```kotlin
  fun formatCloudSourceLabelText(sourceName: String?): LabelText? {
      val name = sourceName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
      val text = "Actual cloud cover data from $name"
      return LabelText(
          fullText = text,
          segments = listOf(Segment(text, Part.STATION)),
      )
  }
  ```

### 2. Android (`:app`)
- In [`CloudCoverViewHandler.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt):
  - When `ActualsProviderResolver.borrows(effectiveDisplaySource)` is true:
    - Determine provider: `val provider = WeatherSource.fromId(ActualsProviderResolver.providerIdFor(effectiveDisplaySource))`
    - Format label: `dominantStationLabel = DominantStationLabel.formatCloudSourceLabelText(provider.displayName)`
    - Pass to `CloudCoverGraphRenderer.renderGraph(...)`.
- In [`CloudCoverGraphRenderer.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt):
  - Place and draw the label using `DominantStationLabel.place(...)` and `dominantStationTextPaint` with color `COLOR_CLOUD_ACTUAL_ARGB` (`#F5DBE3`).

### 3. Linux Desktop (`:desktop`)
- In [`CloudCoverGraph.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/CloudCoverGraph.kt):
  - When `ActualsProviderResolver.borrows(displaySource)` is true:
    - `val provider = WeatherSource.fromId(ActualsProviderResolver.providerIdFor(displaySource))`
    - `val label = DominantStationLabel.formatCloudSourceLabelText(provider.displayName)`
    - Measure, place, and draw with `COLOR_CLOUD_ACTUAL` (`#F5DBE3`).

---

## Verification Plan
1. Unit tests in `:shared`, `:app`, and `:desktop`.
2. Run `./scripts/unit-tests.sh`.
3. Capture emulator screenshot and verify desktop app rendering for Silurian with Synoptic.
