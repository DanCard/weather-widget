# Nuanced Precipitation Icon Matrix Plan

## Background & Motivation
The widget currently relies on a text-based condition parser ("Chance Rain Showers") to determine the weather icon, causing definitive, heavy rain icons to appear even when the numeric chance of rain is quite low (e.g., 26%). This feels misleading and overly aggressive. A more nuanced, data-driven approach is needed to blend the numeric `precipProbability` with the `cloudCover` percentage to create an accurate visual representation.

## Scope & Impact
- **Impacted Files:**
  - `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt`
  - `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`
  - `app/src/main/res/drawable/` (Adding new vector assets)
- **Visual Changes:** The widget will now display specific 1-drop and 2-drop variants based on exact rain probability, combined with the underlying cloud cover (Clear, Partly Cloudy, Cloudy).

## Proposed Solution
Introduce `precipProbability` into `WeatherIconMapper.getIconResource()`. Instead of relying purely on the text condition strings containing "rain", the logic will use a matrix threshold:

- **< 10% Chance:** Suppress drops entirely; rely on cloud cover icons (Sun, Moon, Clouds).
- **10% - 34% Chance:** Display 1 rain drop, combined with the corresponding sun/cloud base icon.
- **35% - 49% Chance:** Display 2 rain drops, combined with the corresponding sun/cloud base icon.
- **≥ 50% Chance:** Display the definitive, heavy rain icon.

*(Note: In the event `precipProbability` is null, the system will fall back to its legacy text-parsing behavior).*

## Implementation Steps

**1. Create New Vector Drawables:**
Draft and add the missing SVG variants to `/app/src/main/res/drawable/`:
- `ic_weather_partly_cloudy_slight_chance_rain.xml` (Day + 1 drop)
- `ic_weather_partly_cloudy_chance_rain_night.xml` (Night + 2 drops)
- `ic_weather_partly_cloudy_slight_chance_rain_night.xml` (Night + 1 drop)
- `ic_weather_cloudy_slight_chance_rain.xml` (Cloudy + 1 drop)
- `ic_weather_cloudy_chance_rain.xml` (Cloudy + 2 drops)
- *(We will also audit the existing `ic_weather_partly_cloudy_chance_rain.xml` to ensure it fits the 2-drop motif).*

**2. Update `WeatherIconMapper.kt`:**
- Add the new parameter `precipProbability: Int? = null` to the `getIconResource` function.
- Update the condition parsing to prioritize this probability. For example, if the text matches "rain", intercept the decision based on `precipProbability`. If it's `< 50`, divert to a new logic block that selects between the 1-drop and 2-drop assets using the `cloudCover` percentage.

**3. Update Call Sites:**
- Modify `TemperatureStateResolver.kt` (and any other callers) to pass the resolved `precipProbability` down to the `WeatherIconMapper`.

## Verification & Testing
- Unit tests will be updated/added to ensure `WeatherIconMapper` returns the exact correct drawable given varying percentages of rain chance and cloud cover.
- Manual testing via emulator will confirm the drawables render correctly on the RemoteViews, and that the widget correctly falls back to legacy behavior when the probability is null.
