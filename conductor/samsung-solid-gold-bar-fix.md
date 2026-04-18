# Fix Samsung Solid Gold Bar (LinearGradient Bug)

## Objective
Fix the bug causing the "Chance Rain Showers" (and other mixed conditions) vertical bar on Monday to render as a solid gold color instead of a Gold-to-Blue (or Gold-to-Grey) gradient on Samsung devices.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt`: Generates the `LinearGradient` for the daily forecast bars.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Draws the vertical bars using the gradient shader.

## Background & Motivation
On certain Samsung devices (particularly with Mali GPUs), drawing a vertical line using `Canvas.drawLine(x, topY, x, bottomY)` with a `Paint` that has a `LinearGradient` defined at `(0f, topY) -> (0f, bottomY)` causes the gradient to collapse into a single color (the first color, which is Gold). This happens because the GPU driver strictly bounds the shader's valid X coordinates to `0f`, and since the line is drawn at `centerX`, it incorrectly evaluates to the first color stop. 

The logs from the Samsung device confirm that Monday's forecast is evaluated properly:
`isMixed=true`, `cloudRatioOverride=0.66`, `gradient=true`, and `bottomColor=BLUE`.
Thus, the solid gold bar is purely a rendering bug that can be fixed by supplying the actual `centerX` to the `LinearGradient`.

## Implementation Steps
1. **Update `WeatherConditionColors.kt`**
   - Update `forecastBarGradient` to accept an `x: Float` parameter (inserted before `topY`).
   - Change the `LinearGradient` instantiation from:
     ```kotlin
     LinearGradient(0f, topY, 0f, bottomY, colors, stops, Shader.TileMode.CLAMP)
     ```
     to:
     ```kotlin
     LinearGradient(x, topY, x, bottomY, colors, stops, Shader.TileMode.CLAMP)
     ```

2. **Update `DailyForecastGraphRenderer.kt`**
   - Locate the three calls to `WeatherConditionColors.forecastBarGradient` and pass the correct X-coordinate:
     - For the main bar: pass `centerX`.
     - For the overlay bar: pass `forecastX`.
     - For the today forecast bar: pass `centerX + layout.tripleBarOffset`.

## Verification & Testing
- **New Tests**: Update `WeatherConditionColorsTest.kt` to include test coverage for `forecastBarGradient` with the new `x` coordinate. Specifically, write a test (potentially using Robolectric) to extract the `ShadowLinearGradient` and assert that `x0` and `x1` are both equal to the passed `x` parameter, ensuring the shader is correctly positioned in the drawing space.
- **Fix Broken Tests**: The existing test suite currently fails to compile due to removed/renamed icons (e.g., `ic_weather_clear_chance_rain`). Fix `WeatherConditionColorsTest.kt` and `WeatherIconMapperTest.kt` by substituting valid current icons (such as `ic_weather_partly_cloudy_chance_rain`).
- **On-Device Verification**: Deploy to the Samsung emulator/device and visually verify that Monday's bar renders correctly as a Gold-to-Blue gradient.