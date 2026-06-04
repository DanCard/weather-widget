# Desktop Hourly Cloud Cover Graph Implementation Plan

## Objective
Bring the desktop companion app's hourly view to full parity with the Android widget by implementing the **Hourly Cloud Cover Graph** (0-100%). It will run on the Compose Desktop application, reuse existing shared weather data, and mimic Android's rendering logic, styling, collision detection, and tap interactions.

## Scope & Impact
- **Affected Module**: `:desktop` (`desktop/src/main/kotlin/com/weatherwidget/desktop/`)
- **Key Files to Modify**:
  - [DesktopConfig.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopConfig.kt): Update `viewMode` values to support `"CLOUD_COVER"` (alongside `"DAILY"` and `"HOURLY"`/`"TEMPERATURE"`).
  - [Main.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt):
    - Update `WidgetHeader` to support graph cycling (Temperature 🌡️, Cloud Cover ☁️).
    - Handle view rendering for `"CLOUD_COVER"`.
    - Adjust hourly navigation targets.
  - [WeatherIcon.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/WeatherIcon.kt): Add logic to determine "home graph" categories for icons.
- **New File to Create**:
  - `desktop/src/main/kotlin/com/weatherwidget/desktop/CloudCoverGraph.kt`: Composable rendering the graph.

---

## 1. View & State Management

### 1.1 Support `"CLOUD_COVER"` View Mode
In [DesktopConfig.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopConfig.kt), define `"CLOUD_COVER"` as a valid `viewMode` value. Keep `"HOURLY"` as an alias/fallback for `"TEMPERATURE"` to maintain backward compatibility.

### 1.2 Identify Icon Graph Homes
In [WeatherIcon.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/WeatherIcon.kt), implement Android-like routing helpers:
```kotlin
fun isRainIndicator(iconRes: String): Boolean {
    return iconRes.contains("rain") || iconRes.contains("storm") || iconRes.contains("snow")
}

fun isCloudForecastEligible(iconRes: String): Boolean {
    return iconRes.contains("cloudy") || iconRes.contains("mostly_clear") || iconRes.contains("fog")
}

fun resolveIconHome(iconRes: String): String {
    return when {
        isRainIndicator(iconRes) -> "TEMPERATURE" // Or "PRECIPITATION" if added later
        isCloudForecastEligible(iconRes) -> "CLOUD_COVER"
        else -> "TEMPERATURE"
    }
}
```

---

## 2. Graph Renderer (`CloudCoverGraph.kt`)

Create a new file `CloudCoverGraph.kt` matching the structure of `TemperatureGraph.kt`. The graph will draw onto a Compose `Canvas` with:

### 2.1 Styling & Colors
- **Curve Stroke**: Color `Color(0xFFAAAAAA)` or `Color(0xFF8E99A4)`.
- **Gradient Fill**: Vertical gradient from `Color(0xFF8E99A4).copy(alpha = 0.22f)` to `Color.Transparent`.
- **Y-Axis Mapping**: Map percentages `0` to `100` dynamically, with scale headroom matching Android's vertical scale strategy:
  - Dynamic `topScale` = `(visibleMax + 12f).coerceIn(85f, 100f)`.
  - Draw bottom padding at the footer icon top line.

### 2.2 Extrema Detection
Extract label candidates based on Android's filtering rules:
1.  **Global Max / Min**: Placed first.
2.  **Edges**: First and last points.
3.  **Local Maxima / Minima**: Local peaks/valleys.
4.  **Soft Dips**: Plateaus where cloud cover drops at least 15% below surrounding hours (max 85%).
5.  Apply density filtering with a `maxCandidates = 5` limit and distance threshold checking.

### 2.3 Collision Detection & Curve Avoidance
- Use `TextMeasurer` to compute exact label `Rect` dimensions.
- Check overlaps using `rect.overlaps(otherRect.inflate(4.dp.toPx()))`.
- Place maxima **ABOVE** the curve, and minima **BELOW** the curve.
- Avoid collisions with bottom weather icons and other text labels.
- Draw a subtle vertical leader line (`Color.White.copy(alpha = 0.35f)`) if a label was shifted from its baseline to avoid overlap.

### 2.4 Cloud Watermark
- Compute the emptiest horizontal window (size = points / 5, min 3, max 6 hours) above the curve by finding the window with the lowest average cloud cover.
- Draw the mostly cloudy icon (`drawable/ic_weather_mostly_cloudy.xml`) centered in that window:
  - Y position = `graphTop + (curveY - graphTop) * fraction` (testing vertical fractions `0.5f`, `0.65f`, `0.35f`).
  - Set alpha to `0.08f` (Android uses `WATERMARK_ALPHA` = 20/255).
  - Ensure it fits above the curve and does not collide with labels.

### 2.5 Data Missing Diagnostic
If `hours.size` is less than the expected window size (due to database gaps or source change):
- Render a centered diagnostic string using `drawText`:
  - `"Cloud data unavailable"` (if all missing).
  - `"Cloud data missing for X of Y hrs"` (if partial gaps).
- Add secondary dimmer failure reason if available (e.g. NWS failure tags).

### 2.6 Bottom Day & Hour Strips
- Render weather icons and hour labels (e.g. `12a`, `4p`) at the bottom.
- Draw left and right day labels (e.g., `Wed`, `Thu`) at the graph edges.
- Draw a dashed vertical "Now" indicator line.

---

## 3. UI Interactions & Integration (`Main.kt`)

### 3.1 Click Routing in Graphs
Update `TemperatureGraph.kt` and `CloudCoverGraph.kt` to make bottom-row weather icons clickable:
- Tapping a bottom icon changes `config.viewMode` to its "home" view mode:
  - Clear/sunny icon -> `"TEMPERATURE"` (or `"HOURLY"`)
  - Cloud-cover icon -> `"CLOUD_COVER"`

### 3.2 Graph Cycle Button
In the `WidgetHeader` row of `Main.kt`:
- Add a graph switcher icon (e.g., emoji: ☁️ when in temperature mode, 🌡️ when in cloud cover mode).
- Wire it to cycle the `viewMode` state:
  - If in `"HOURLY"` or `"TEMPERATURE"` -> switches to `"CLOUD_COVER"`
  - If in `"CLOUD_COVER"` -> switches to `"TEMPERATURE"`

### 3.3 Main Render Branch
In `Main.kt`, expand the main container rendering logic:
```kotlin
if (config.viewMode == "HOURLY" || config.viewMode == "TEMPERATURE") {
    TemperatureGraph(...)
} else if (config.viewMode == "CLOUD_COVER") {
    CloudCoverGraph(...)
} else {
    // DAILY rendering
}
```

---

## 4. Verification Steps

1.  **Build and Run**: Run the desktop app via `./gradlew :desktop:run`.
2.  **Navigation**: Open the hourly view and tap the header graph switcher emoji to toggle between temperature and cloud cover views.
3.  **Visual Parity**: Verify the cloud cover graph displays:
    - Slate/gray smooth bezier curve with semi-transparent gray gradient fill underneath.
    - Day labels at edges, hour labels, and weather icons at the bottom.
    - Cloud percentage labels (e.g., `10%`, `90%`) placed above peaks and below valleys without overlapping icons or other text.
    - A faint, large cloud watermark icon positioned in the emptiest space above the curve.
4.  **Bottom Zone Interactions**: Tap a cloudy icon on the bottom strip of the temperature graph, and verify it navigates to the cloud cover graph. Tap a sunny/clear icon on the bottom strip of the cloud cover graph, and verify it navigates back to the temperature graph.
5.  **Missing Data Diagnostic**: Simulate gaps in local database data and ensure the centered diagnostic reads "Cloud data missing..." appropriately.
