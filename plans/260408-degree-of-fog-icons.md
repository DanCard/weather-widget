# Plan: Degree of Fog Icons

## Objective
Create and map different weather indicator icons based on the degree of fog, primarily derived from NWS textual conditions (e.g., "Patchy Fog", "Areas of Fog", "Dense Fog").

## Approach
We will rely on Text-based Severity, extracting the severity from the NWS forecast description strings. This avoids complex architectural changes to our data models (like plumbing through visibility metrics) and directly addresses how NWS communicates fog severity.

## Key Files & Context
- `app/src/main/res/drawable/ic_weather_fog.xml` (Existing base fog icon)
- `app/src/main/res/drawable/ic_weather_fog_light.xml` (New file)
- `app/src/main/res/drawable/ic_weather_fog_dense.xml` (New file)
- `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt` (Logic to map strings to resources)
- `app/src/test/java/com/weatherwidget/util/WeatherIconMapperTest.kt` (Validation)

## Implementation Steps

### 1. Design New Vector Icons
- **Light Fog (`ic_weather_fog_light.xml`)**: Clone the existing fog icon and reduce the number of mist layers and their opacities (alphas) to convey a lighter, "patchy" fog condition.
- **Dense Fog (`ic_weather_fog_dense.xml`)**: Clone the existing fog icon and increase layer density. This can be achieved by adding an extra mist wave at the top, or increasing the alpha values significantly so the background is more obscured.

### 2. Update `WeatherIconMapper.kt`
Modify `getIconResource` to parse severity from the condition string before falling back to generic fog:
- **Dense Fog**: If `normalizedCondition` contains `"dense fog"`, map to `R.drawable.ic_weather_fog_dense`.
- **Light/Patchy Fog**: If `normalizedCondition` contains `"patchy fog"` or `"light fog"`, map to `R.drawable.ic_weather_fog_light`.
- **Standard Fog**: If `normalizedCondition` contains `"areas of fog"` or simply `"fog"` without modifiers (or `"mist"`, `"haze"`), map to `R.drawable.ic_weather_fog` (existing behavior).
- *Note:* We must ensure these rules properly coordinate with the existing mixed fog conditions (e.g., `ic_weather_fog_sunny` and `ic_weather_fog_cloudy`). For instance, if it's "Patchy Fog then Sunny", the current logic normalizes it to "Sunny" or handles it via "fog and sunny" checks. We will refine this precedence to ensure density modifiers don't unintentionally break the "then sunny" transitions.

### 3. Add Tests
Update `WeatherIconMapperTest.kt` to verify the mapping logic:
- `"Dense Fog"` -> `ic_weather_fog_dense`
- `"Patchy Fog"` -> `ic_weather_fog_light`
- `"Areas of Fog"` -> `ic_weather_fog`
- Test transitions like `"Dense Fog then Sunny"` to ensure intended priority.

## Verification & Testing
- Run `./gradlew testDebugUnitTest` to ensure all `WeatherIconMapper` logic behaves correctly and doesn't break existing conditions.
- Visually inspect the newly added drawables in Android Studio to ensure the alpha layering creates the intended visual impact.
- Deploy to emulator and check the "Today" daily forecast and hourly forecast segments to confirm the icons render gracefully at widget scale.