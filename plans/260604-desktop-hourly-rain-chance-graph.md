# Plan: Desktop Hourly Rain Chance Graph

This plan outlines the design and implementation for the hourly rain chance (precipitation) graph on the Linux desktop companion app (Compose Desktop). It will mirror the features, visuals, and behavior of the Android widget's hourly precipitation graph.

---

## 1. Objectives

- **Visual Fidelity**: Recreate the blueish precipitation probability curve (`#5AC8FA`) and vertical linear gradient under the curve, matching the Android widget's aesthetic.
- **Data Integration**: Correctly display hourly precipitation probability (0-100%) and rain amount labels (using locale-aware metric/imperial units) for forecast data.
- **Actual Precipitation (Observations)**: Draw actual precipitation totals from observation readings when available (past hours), with a solid line/label in orange (`#FF9F0A`) and forecast values with a dashed line/label in white (`#FFFFFF`).
- **Layout Logic**: Implement a Compose Desktop `Canvas`-based rendering approach mimicking `PrecipitationGraphRenderer.kt`'s peak/valley candidate search, label collision detection, now marker, day labels, day/night dividers, and watermark.
- **User Navigation**: Expand view mode toggling in the header and chips to support the new `"PRECIPITATION"` mode.

---

## 2. Proposed Changes

### 2.1. Create `PrecipitationGraph.kt`
Create a new file [PrecipitationGraph.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/PrecipitationGraph.kt) in the `:desktop` module to house the Composable precipitation graph.

#### Composable Signature:
```kotlin
@Composable
fun PrecipitationGraph(
    hourly: List<HourlyForecast>,
    observations: List<ObservationReading> = emptyList(),
    displaySourceId: String = "NWS",
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    modifier: Modifier = Modifier,
    centerOffsetHours: Int = 0,
    zoomLevel: String = "WIDE",
    onViewModeChange: (String) -> Unit = {},
)
```

#### Key Layout & Drawing Tasks inside the Composable:
1. **Window Alignment & Filtering**: Filter the hourly forecast points using the same `backHours`/`forwardHours` window as `TemperatureGraph`.
2. **Tangent & Spline Drawing**: Use the Catmull-Rom tangent calculation (`computeTangents`) to draw a smooth curve of precipitation probabilities.
3. **Probability & Rain Amount Labels**:
   - Extract peaks/valleys and soft dips from the probability curve.
   - Run collision detection using text bounds measured via `rememberTextMeasurer`.
   - Calculate and display rain amounts (and actual rain amounts from observations) for contiguous rain periods.
4. **Grid Lines / Dividers**:
   - Draw day/night dividers at 8AM and 8PM boundaries (`#66FFFFFF` dashed lines).
   - Draw the "NOW" indicator dashed line and circle on the curve.
   - Draw the day/date labels at the top of the canvas.
5. **Watermark**:
   - Draw the rain cloud icon watermark in the center of the graph canvas.

---

### 2.2. Update `WeatherIcon.kt`
Modify [WeatherIcon.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/WeatherIcon.kt) to map rain conditions to the new `"PRECIPITATION"` mode.

```diff
     fun resolveIconHome(iconRes: String): String {
         return when {
-            isRainIndicator(iconRes) -> "HOURLY" // Or "PRECIPITATION" if added later
+            isRainIndicator(iconRes) -> "PRECIPITATION"
             isCloudForecastEligible(iconRes) -> "CLOUD_COVER"
             else -> "HOURLY"
         }
     }
```

---

### 2.3. Update `Main.kt`
Modify [Main.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt) to integrate the new view mode.

#### A. Extend view mode checks:
Add `"PRECIPITATION"` to `isHourly` checks to treat it as an hourly graph view.
```diff
-val isHourly = config.viewMode == "HOURLY" || config.viewMode == "TEMPERATURE" || config.viewMode == "CLOUD_COVER"
+val isHourly = config.viewMode == "HOURLY" || config.viewMode == "TEMPERATURE" || config.viewMode == "CLOUD_COVER" || config.viewMode == "PRECIPITATION"
```

#### B. Embed `PrecipitationGraph` in the layout:
Render the graph Composable inside `WidgetPopup`:
```diff
                     val isHourly = config.viewMode == "HOURLY" || config.viewMode == "TEMPERATURE" || config.viewMode == "CLOUD_COVER" || config.viewMode == "PRECIPITATION"
                     if (isHourly) {
                         Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag("hourly_temperature_surface")) {
                             if (config.viewMode == "CLOUD_COVER") {
                                 CloudCoverGraph(...)
+                            } else if (config.viewMode == "PRECIPITATION") {
+                                PrecipitationGraph(
+                                    hourly = snapshot.hourly,
+                                    observations = snapshot.rawObservations,
+                                    displaySourceId = config.weatherSource,
+                                    latitude = config.lat,
+                                    longitude = config.lon,
+                                    modifier = Modifier.fillMaxSize(),
+                                    centerOffsetHours = config.hourlyOffset,
+                                    zoomLevel = config.zoomLevel,
+                                    onViewModeChange = { targetView ->
+                                        onUpdateConfig(config.copy(viewMode = targetView))
+                                    }
+                                )
                             } else {
                                 TemperatureGraph(...)
                             }
```

#### C. Hook up the header precipitation click:
Make the precipitation text in `WidgetHeader` clickable to jump directly to precipitation view:
```diff
                 if (precipProb != null) {
                     Spacer(Modifier.width(6.dp))
                     Text(
                         text = "$precipProb%",
                         style = MaterialTheme.typography.labelMedium,
                         color = Color(0xFF4FC3F7),
-                        modifier = Modifier.align(Alignment.CenterVertically).offset(y = 2.dp)
+                        modifier = Modifier
+                            .align(Alignment.CenterVertically)
+                            .offset(y = 2.dp)
+                            .clickable {
+                                onUpdateConfig(config.copy(viewMode = "PRECIPITATION"))
+                            }
                     )
                 }
```

#### D. Update hourly view cycle logic:
Cycle through all three graphs when clicking the emoji icon:
```diff
                 if (isHourly) {
                     Spacer(Modifier.width(6.dp))
-                    val isCloud = config.viewMode == "CLOUD_COVER"
-                    val emoji = if (isCloud) "🌡️" else "☁️"
+                    val nextMode = when (config.viewMode) {
+                        "CLOUD_COVER" -> "HOURLY"
+                        "PRECIPITATION" -> "CLOUD_COVER"
+                        else -> "PRECIPITATION"
+                    }
+                    val emoji = when (config.viewMode) {
+                        "CLOUD_COVER" -> "🌡️"
+                        "PRECIPITATION" -> "☁️"
+                        else -> "🌧️"
+                    }
                     Text(
                         text = emoji,
                         fontSize = 11.sp,
                         modifier = Modifier.clickable {
-                            val nextMode = if (isCloud) "HOURLY" else "CLOUD_COVER"
                             onUpdateConfig(config.copy(viewMode = nextMode))
                         }
                     )
                 }
```

#### E. Update Day click view mode resolution:
Resolve clicked day's view mode based on its weather conditions (rain / cloudiness):
```diff
                             onDayClick = { clickedDate ->
                                 val now = LocalDateTime.now()
                                 val hours = java.time.Duration.between(now, clickedDate.atStartOfDay()).toHours().toInt()
-                                onUpdateConfig(config.copy(viewMode = "HOURLY", hourlyOffset = hours))
+                                val clickedDay = snapshot.daily.find { it.date == clickedDate }
+                                val clickedIcon = clickedDay?.iconCondition
+                                val nextView = clickedIcon?.let { WeatherIcon.resolveIconHome(WeatherIcon.getIconResource(it)) } ?: "HOURLY"
+                                onUpdateConfig(config.copy(viewMode = nextView, hourlyOffset = hours))
                             }
```

---

## 3. Verification & Testing

1. **Compilation**: Run `./gradlew :desktop:run` to confirm the changes compile without errors.
2. **Visual Inspection**:
   - Tapping the precipitation probability percentage in the header opens the `"PRECIPITATION"` graph.
   - The cycle icon transitions correctly: 🌡️ (Temp) -> 🌧️ (Rain) -> ☁️ (Cloud) -> 🌡️ (Temp).
   - Tapping a rainy day in the daily view immediately opens the `"PRECIPITATION"` graph centered on that day.
   - The precipitation curve, watermark, now line, day labels, day/night boundaries, and rain amount annotations (forecast vs. observed) are drawn cleanly without overlaps.
