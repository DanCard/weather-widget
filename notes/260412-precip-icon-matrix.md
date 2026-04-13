# Precipitation & Cloud Cover Icon Matrix

This matrix splits Cloud Cover into standard meteorological tiers and Chance of Rain into distinct probability tiers.

| Chance of Rain | Mostly Clear (0-30% Cloud Cover) | Partly Cloudy (31-70% Cloud Cover) | Mostly Cloudy/Overcast (71-100% Cloud Cover) |
| :--- | :--- | :--- | :--- |
| **< 10%**<br>*(None/Trace)* | ☀️ Sun / 🌙 Moon<br>*(No drops)* | ⛅ Sun+Cloud / ☁️🌙 Moon+Cloud<br>*(No drops)* | ☁️ Solid Cloud<br>*(No drops)* |
| **10% - 34%**<br>*(Slight Chance)* | 🌦️ Sun + **1 Drop**<br>*(Sun shower)* | 🌦️ Sun+Cloud + **1 Drop**<br>*(Light scattered)* | 🌧️ Solid Cloud + **1 Drop**<br>*(Light rain/drizzle)* |
| **35% - 49%**<br>*(Chance)* | 🌦️ Sun + **2 Drops**<br>*(Sun shower)* | 🌦️ Sun+Cloud + **2 Drops**<br>*(Scattered showers)* | 🌧️ Solid Cloud + **2 Drops**<br>*(Moderate rain)* |
| **≥ 50%**<br>*(Likely/Definite)* | 🌧️ **Standard Rain Icon**<br>*(Overrides clear skies)* | 🌧️ **Standard Rain Icon**<br>*(Overrides partly cloudy)* | 🌧️ **Standard Rain Icon**<br>*(Heavy rain)* |

## Implementation Notes
- This requires creating new Day and Night vector variants for 1-drop and 2-drop combinations.
- `WeatherIconMapper.kt` will be updated to accept `precipProbability` as a parameter to evaluate these states, falling back to text parsing if probability is null.
