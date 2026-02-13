# Findings - Rain Tomorrow Information

## Architecture Overview
- **Widget Update:** `WeatherWidgetWorker` fetches data; `WeatherWidgetProvider` updates UI.
- **View Modes:** DAILY (text/graph), HOURLY (graph/text), PRECIPITATION (graph/text).
- **Rain Data:** Available in `WeatherEntity` (daily) and `HourlyForecastEntity` (hourly).

## Key Components
- `widget_weather.xml`: Main layout. Contains `precip_probability` TextView.
- `DailyViewHandler.kt`: Currently only shows *today's* precipitation probability in `precip_probability`.
- `PrecipitationGraphRenderer.kt`: Renders a 24-hour graph (8h back, 16h forward).

## Opportunities
- `precip_probability` field is currently hidden if today's rain chance is 0. This space can be used to show "Tomorrow: 80%" or "Rain tomorrow 2pm".
- `hourlyForecasts` contains data for the next 24+ hours, which can be scanned to find the first significant rain event.
- The `DailyViewHandler`'s text mode (Day 3) shows the rain icon but not the percentage or time.

## Design Constraints
- `precip_probability` text size is `26sp`. If adding words, it should be reduced (e.g., to `16sp` or `14sp`) to fit.
- The widget has limited space, especially in 1-row mode.
