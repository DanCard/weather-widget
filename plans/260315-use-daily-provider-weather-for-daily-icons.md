# Use Daily Provider Weather For Daily-View Icons

## Problem
- For wapi current weather said cloudy, when there are not clouds in the sky.
- Meteo and silurian seem to follow the same fate.
- Want to see effect of ditching idea of using current weather indicator and using daily forecast indicator.

## Summary
- Change daily view so it never uses the current-hour hourly condition to choose icons.
- In daily view, both the top header icon and each per-day forecast icon use the selected provider's daily condition for that date.
- Keep the existing `WeatherIconMapper`; do not introduce provider-native icon assets or codes.

## Key Changes
- Remove the hourly-condition override from daily view logic.
- In the daily-view header, use today's selected-source daily forecast condition instead of the current hourly condition.
- In daily text mode, use `data.weather?.condition` for all dates, including today.
- In daily graph mode, use `weather?.condition ?: actual?.condition` for all dates, including today.
- Keep existing day/night behavior for the header icon only.
- Do not change hourly/temperature view behavior.

## Test Plan
- Add a logic test proving today's graph-mode icon uses the daily condition when the hourly condition differs.
- Add a Robolectric test proving the rendered daily-view header icon uses today's daily condition instead of the hourly condition.
- Add a Robolectric test proving today's per-day text icon uses the daily condition instead of the hourly condition.
- Run the focused daily-view test suite.

## Assumptions
- "Use APIs daily weather" means use each provider's daily condition text with the existing local mapper.
- The change applies to all icons shown while the widget is in daily view: header plus per-day icons.
