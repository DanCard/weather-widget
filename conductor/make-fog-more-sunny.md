# Plan: Make 'Fog then Sunny' more sunny

The goal is to ensure that conditions like "Fog then Sunny" (common from NWS) result in a more optimistic, sun-dominant icon rather than a fog-dominant one.

## Proposed Changes

### 1. Update `WeatherIconMapper.kt`
- Improve `normalizePatchyFogTransitionCondition` to handle any "then" transition, not just those starting with "patchy fog".
- This will allow "Fog then Sunny" to be normalized to "Sunny", which naturally maps to sunnier icons.

### 2. Update `ic_weather_fog_sunny.xml`
- Reorder the layers so the Sun is drawn **after** (on top of) the fog layers.
- Increase the Sun's visual prominence.

## Implementation Steps

1.  **Modify `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt`**:
    - Rename `normalizePatchyFogTransitionCondition` to `normalizeTransitionCondition`.
    - Update logic to split on " then " and take the second part.
2.  **Modify `app/src/main/res/drawable/ic_weather_fog_sunny.xml`**:
    - Move the Sun path to the end of the file.
    - (Optional) Adjust scale/position to ensure it's not obscured.

## Verification

- Run unit tests for `WeatherIconMapper`.
- Verify on the emulator that the "Today" icon for "Fog then Sunny" now shows the sunnier variant.
