# Findings & Decisions

## Requirements
- User asked to verify tenth-degree storage, then requested implementation to support it across stored temperatures.

## Research Findings
- `hourly_forecasts.temperature` already used decimal precision before this change (`Float`/`REAL`).
- `weather_data.highTemp/lowTemp/currentTemp` and `forecast_snapshots.highTemp/lowTemp` were integer-backed.
- Open-Meteo and WeatherApi daily/current parsing rounded values to `Int`, truncating source precision.

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Convert `WeatherEntity` and `ForecastSnapshotEntity` temperature fields to `Float?` | Preserve decimal precision in persisted daily/current/snapshot data |
| Add `MIGRATION_17_18` converting affected columns to `REAL` via table rebuild | Safe SQLite-compatible type migration preserving existing rows |
| Parse Open-Meteo and WeatherApi daily/current values as `Float` | Stop precision loss during ingestion |
| Preserve integer-oriented chart/stat rendering in a few callsites via explicit rounding | Minimize UI behavior churn while enabling precise storage |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Unit test fixtures and assertions expected integer temps | Updated fixtures and assertions to float-aware expectations |

## Resources
- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/local/WeatherEntity.kt`
- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/local/ForecastSnapshotEntity.kt`
- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`
- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`
- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/remote/OpenMeteoApi.kt`
- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/remote/WeatherApi.kt`

## Visual/Browser Findings
- N/A

---
*Update this file after every 2 view/browser/search operations*
*This prevents visual information from being lost*
