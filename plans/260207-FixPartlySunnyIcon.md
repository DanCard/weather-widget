# Fix "Partly Sunny" Icon Mapping and Tinting

## Issue
The user reported that the NWS icon for "today" showed as sunny (Clear icon) when it was not sunny (likely Partly Sunny). They requested icons that depict "Partly Sunny".

## Findings
1.  **Mapping Issue**: `WeatherIconMapper.kt` mapped "Partly Sunny" to `ic_weather_clear` because it matched the "sunny" keyword but failed the "partly cloudy" check (which looked for "partly" AND "cloudy").
2.  **Tinting Issue**: `DailyForecastGraphRenderer` and `WeatherWidgetProvider` applied a global yellow tint to any icon flagged as `isSunny`. This turned mixed icons (like `ic_weather_partly_cloudy`) completely yellow, obscuring the gray cloud.

## Resolution
1.  **Update Mapper**: Modified `WeatherIconMapper.kt` to catch "partly" independently, mapping "Partly Sunny" and "Partly Cloudy" to `ic_weather_partly_cloudy`.
2.  **Preserve Native Colors**:
    *   Added `isMixed` flag to `DayData` and `HourData` to identify multi-color icons (`partly_cloudy`, `mostly_clear`, `mostly_cloudy`).
    *   Updated `DailyForecastGraphRenderer.kt` and `HourlyGraphRenderer.kt` to skip tinting for icons that are `isMixed` or `isRainy`, preserving their native yellow sun and gray cloud colors.
    *   Updated `WeatherWidgetProvider.kt` (text mode) to similarly skip tinting for mixed icons.
    *   Ensured `ic_weather_partly_cloudy_night` is NOT treated as mixed, so it receives the standard gray tint for consistency with other night icons.
3.  **Tests**: Added a unit test for "Partly Sunny" in `WeatherIconMapperTest.kt`.

## Verified
*   `WeatherIconMapperTest` passed.
*   Logic ensures "Partly Sunny" shows the correct icon with correct colors.
