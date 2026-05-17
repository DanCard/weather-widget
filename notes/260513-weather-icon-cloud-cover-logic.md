# Weather Icon Cloud Cover Logic & Saturday Discrepancy

## Analysis of Saturday Discrepancy (Tomorrow.io)
- **Reported Issue**: Vertical bar shows >50% cloudy, but the weather icon shows "Sunny".
- **Condition**: `"Clear"` (Code `1000`)
- **Cloud Cover**: `79%` (Noon)
- **Root Cause**: `WeatherIconMapper.getIconResource` prioritizes the text condition "Clear" during the day and explicitly ignores numeric `cloudCover` data when choosing the sunny icon (`ic_weather_clear`).

## Daytime Cloudy Icon Tiers
Based on the available resources and `WeatherIconMapper.getCloudCoverIcon` logic:

| Cloud Cover % | Icon Resource | Visual Description |
| :--- | :--- | :--- |
| **0% - 25%** | `ic_weather_mostly_clear` | Mostly sun with a tiny cloud |
| **26% - 74%** | `ic_weather_partly_cloudy` | Sun with a medium cloud (Standard "Partly Cloudy") |
| **75% - 90%** | `ic_weather_mostly_cloudy` | Heavy clouds with just a bit of sun |
| **91% - 100%** | `ic_weather_cloudy` | Full overcast |

*Note: Night versions exist for the first three tiers.*

## Proposed Fix
Update `WeatherIconMapper.getIconResource` to check the `cloudCover` value even when the condition is "Clear", "Sunny", "Fair", or "Observed" during the day. If `cloudCover > 25`, it should delegate to `getCloudCoverIcon(false, cloudCover)` to ensure visual consistency between the icon and the data-driven graph bars.
