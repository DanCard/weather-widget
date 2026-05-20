# Wide Mode Header Scaling Plan

## Objective
Increase the header row icons and fonts by 35% when the widget has a lot of horizontal space (wide mode, e.g., on Samsung full-width widgets), applying this scaling consistently across all graph modes (Daily, Temperature, Precipitation, and Cloud Cover).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/handlers/HeaderWidthChecker.kt`: Contains the existing but unused `computeHeaderScale` logic that returns `1.35f` when header occupancy is under 50%.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` & `DailyForecastHeaderRenderer.kt`: Handle custom bitmap drawing for the daily graph's header.
- `app/src/main/java/com/weatherwidget/widget/handlers/HeaderRemoteViewsBinder.kt`: Responsible for binding text sizes to `RemoteViews` for hourly graphs.
- `app/src/main/java/com/weatherwidget/widget/handlers/*ViewHandler.kt` & `*ViewBinder.kt`: The main handler classes that compose the headers for each graph type.

## Implementation Steps

### 1. Daily Graph Bitmap Rendering
- In `DailyForecastHeaderRenderer.kt`, update `drawHeader()` to multiply the `labelScale` by `header.headerScale`. This ensures that all fonts and icons drawn to the daily header bitmap scale up natively.

### 2. RemoteViews Header Scaling Helper
- In `HeaderRemoteViewsBinder.kt`, introduce a new parameter `scale: Float = 1.0f` to all text binding methods (`bindCurrentTemp`, `bindDelta`, `bindPrecipProbability`). Multiply the configured `textSizeDp` by this scale before applying it via `setTextViewTextSize`.
- Add a new helper method `bindScaledIcon(context, views, viewId, iconRes, scale)` to handle scaling of vector icons (like `weather_icon` and `settings_icon`) in `RemoteViews`. If `scale > 1.0f`, decode the vector drawable, draw it to a scaled `Bitmap`, and use `setImageViewBitmap`. Otherwise, fall back to `setImageViewResource`.

### 3. Wire Up Handlers
- Update `DailyViewHandler.kt` to populate `HeaderRenderData.headerScale` by calling `HeaderWidthChecker.computeHeaderScale`.
- Update `TemperatureViewBinder.kt`, `PrecipViewHandler.kt`, and `CloudCoverViewHandler.kt` to call `computeHeaderScale` and pass the returned scale factor down to the updated `HeaderRemoteViewsBinder` methods and the new icon scaling helper.

## Verification & Testing
- Deploy to a physical device or emulator (e.g., Pixel or Samsung profile).
- Resize the widget to occupy the full width of the screen. Verify that the header text and icons visibly increase in size by 35%.
- Shrink the widget horizontally to force occupancy above 50% and verify that the header text and icons return to their normal 1.0x scale.
- Toggle between all view modes (Daily, Temperature, Precipitation, Cloud Cover) to ensure the scaling applies uniformly.