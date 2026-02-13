# Add "Fog + Cloudy" Icon

**Objective:** Add a specific icon for days described as "Fog then Cloudy" or "Fog and Cloudy" to better represent the transition or combination of weather.

## Implementation
1.  **Created `ic_weather_fog_cloudy.xml`:**
    - Combined the standard cloud path (`ic_weather_cloudy`) with the lower layers of the rolling fog design (`ic_weather_fog`).
    - The cloud sits in the background/top, while the fog rolls in at the bottom (layers 2, 3, 4).
    - Fog alpha increased slightly (0.65 -> 0.75 -> 0.85) to blend with the grey cloud.

2.  **Updated `WeatherIconMapper.kt`:**
    - Added a specific check: `lowerCondition.contains("fog") && (lowerCondition.contains("cloudy") || lowerCondition.contains("overcast"))`.
    - This rule is placed **before** the generic "fog" check, so it captures combined conditions like "Areas Of Fog then Mostly Cloudy".

## Verification
- Build successful.
- "Areas Of Fog then Mostly Cloudy" (tomorrow's forecast) should now trigger this specific icon instead of the generic Fog icon.
