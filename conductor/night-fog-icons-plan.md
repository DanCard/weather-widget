# Plan: Night-time Moon Fog Icons

## Objective
Introduce night variants of the fog icons that incorporate a moon, enhancing visual intuition for night-time fog conditions (e.g., "Patchy Fog", "Fog").

## Approach
We will extract the moon vector path from `ic_weather_night.xml` and layer it behind the mist waves in our fog icons. We'll create specific night versions for standard fog and light fog. As discussed, dense fog will remain moon-less to convey total obscurity.

## Key Files
- `app/src/main/res/drawable/ic_weather_fog_night.xml` (New)
- `app/src/main/res/drawable/ic_weather_fog_light_night.xml` (New)
- `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt`
- `app/src/test/java/com/weatherwidget/util/WeatherIconMapperTest.kt`

## Implementation Steps

### 1. Create `ic_weather_fog_night.xml`
- Copy the structure of `ic_weather_fog.xml`.
- Insert the moon path (`M12,3c-4.97,0 -9...`) from `ic_weather_night.xml` at the top of the file (drawing it first, in the background).
- Keep the existing fog layers overlapping it.

### 2. Create `ic_weather_fog_light_night.xml`
- Copy the structure of `ic_weather_fog_light.xml`.
- Insert the same moon path in the background.

### 3. Update `WeatherIconMapper.kt`
- Update the fog mapping rules to check the `isNight` parameter:
  - **Dense Fog**: Always `ic_weather_fog_dense`.
  - **Light/Patchy Fog**: `if (isNight) R.drawable.ic_weather_fog_light_night else R.drawable.ic_weather_fog_light`
  - **Standard Fog**: `if (isNight) R.drawable.ic_weather_fog_night else R.drawable.ic_weather_fog`
- Update the "fog then clear/sunny" mapping:
  - If `isNight` is true, use `R.drawable.ic_weather_fog_night` instead of `ic_weather_fog_sunny`.

### 4. Update Tests
- Add tests to `WeatherIconMapperTest.kt` validating that night-time fog requests return the new moon variants.

## Verification
- Run `./gradlew testDebugUnitTest`.
- Visually verify the vector drawables in Android Studio.