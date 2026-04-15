# Plan: Redesign Sunset Icon (Rich Amber Gradient)

The current "horizon sun" icon used for sunrise/sunset transitions looks poor because the sun is floating above the horizon line, and it is flattened by global amber tinting. This plan redesigns the icon to sit on the horizon with a rich gradient and updates the mapping logic to preserve these colors.

## Objective
- Redesign `ic_weather_horizon_sun.xml` with improved geometry and a rich amber gradient.
- Update `WeatherIconMapper.kt` to classify this icon as "mixed" to prevent flat global tinting.
- Ensure the forecast line and labels remain amber during twilight for visual continuity.

## Key Files & Context
- `app/src/main/res/drawable/ic_weather_horizon_sun.xml`: The sunset/transition icon.
- `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt`: Determines icon categories (`isSunny`, `isMixed`, etc.).
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`: Uses `isMixed` to decide whether to skip global tinting.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Also uses `isMixed` to skip tinting.

## Implementation Steps

### 1. Redesign `ic_weather_horizon_sun.xml`
- **Sun Path**: Increase radius to 5, center at `x=12, y=17` (half-submerged).
- **Gradient**: Add a vertical linear gradient from `y=12` (Amber `#FFA726`) to `y=17` (Deep Orange `#FF7043`).
- **Horizon**: Draw a 1dp line at `y=17` across the viewport using Blue Grey (`#546E7A`).
- **Rays**: Reposition rays to fan out above the horizon line.
- **Glow**: Add a large, faint path behind the sun for atmospheric depth.

### 2. Update `WeatherIconMapper.kt`
- Add `R.drawable.ic_weather_horizon_sun` to the `MIXED_ICONS` set.
- **Note**: Keep it in `isSunny()` as well, as this drives the amber color of the *forecast line* and *temperature labels* during twilight (via `WeatherConditionColors.forecastColor()`).

### 3. Update Tests
- **`WeatherIconMapperTest.kt`**: Add `assertTrue(WeatherIconMapper.isMixed(R.drawable.ic_weather_horizon_sun))`.

## Verification & Testing
1. **Unit Tests**:
   - Run `./gradlew test` to ensure `WeatherIconMapperTest` passes.
2. **Visual Audit (Emulator)**:
   - Capture a screenshot at a twilight hour (e.g., 8:00 PM on April 14 in Mountain View).
   - Verify the icon shows the gradient sun and grey horizon line.
   - Verify the forecast line segments connecting to this hour are still amber (`#FFA726`).
3. **Daily View Verification**:
   - Verify the "Today" icon (if viewed at sunset) shows the new gradient design correctly.
