# Findings & Decisions

## Requirements
- Clicking a history bar in daily forecast graph should navigate to a forecast-vs-actual comparison graph.
- Comparison must use same source for forecast and actual:
- `NWS forecast` compares to `NWS actual`.
- `Open-Meteo forecast` compares to `Open-Meteo actual`.
- Current behavior mixes or ignores source when building comparison.

## Research Findings
- Skill `planning-with-files` was requested and applied.
- Session catchup found unsynced prior-session context unrelated to this bug fix; planning files in repo were absent and created.
- `ForecastHistoryActivity` loads snapshots via `forecastSnapshotDao.getForecastEvolution(targetDate, lat, lon)` and splits them by source (`NWS`, `OPEN_METEO`).
- `ForecastHistoryActivity` currently loads `actualWeather` using `weatherDao.getWeatherForDate(targetDate, lat, lon)` which is source-agnostic.
- `WeatherWidgetProvider` click handlers (`setupTextDayClickHandlers`, `setupGraphDayClickHandlers`) launch `ForecastHistoryActivity` with target date and coordinates only, no source extra.
- Therefore, actual temperatures can come from the wrong source row in `weather_data`, causing forecast-vs-actual mismatch.
- Implemented fix: widget click intents now include selected source, and history activity filters both snapshots and actual lookup to that source.

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Trace end-to-end click flow before editing | Avoid fixing wrong layer and reduce regressions |
| Add explicit source extra when launching history activity | Preserve selected widget source through navigation boundary |
| Add source-filtered weather DAO method and use it in history activity | Guarantees source-matched actual selection |
| Keep fallback to source-agnostic actual only when source extra missing | Backward-compatible for older intents/tests |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Planning files missing in project root | Initialized via skill script and populated for this task |

## Resources
- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- `app/src/main/java/com/weatherwidget/data/local/WeatherDao.kt`
- `task_plan.md`
- `progress.md`

## Visual/Browser Findings
- Not applicable for this task.
