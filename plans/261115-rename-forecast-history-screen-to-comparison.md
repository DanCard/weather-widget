# Rename the Forecast History screen to Forecast Comparison in code

Follow-up to 713d99f1f, which renamed the user-visible title only.

## Scope

Rename the **screen** identifiers (Android, desktop, `:shared` view logic, tests, docs):

- Classes: `ForecastHistoryActivity`, `ForecastHistoryWindow`, `ForecastHistoryReload`,
  `ForecastHistoryContent`, `ForecastHistoryHeader`, `ForecastHistoryViewLogic` → `ForecastComparison*`
- Test classes for those (`ForecastHistory*Test` → `ForecastComparison*Test`;
  `*SourceComparison*Test` → `*Sources*Test` to avoid "ComparisonSourceComparison")
- Resources: `activity_forecast_history`, `ic_forecast_history_line`, `forecast_history*` string keys
  (all 20 locales), `open_forecast_history*` and `forecast_history_activity_touch_zone*` view ids
- Log tags `ForecastHistoryActivity` / `ForecastHistoryViewLogic` (logcat/app_logs queries on the old
  tag stop matching new rows)
- Living docs (`arch/`) and code comments saying "History of Forecasts"
- Delete the unused `forecast_show_evolution` string ("Back to Forecast History") from all locales

## Kept (stored data, genuinely a history)

- `hourly_forecast_history` table, `HourlyForecastHistoryDao`, `upsertHourlyForecastHistory`,
  `ForecastHistoryStorageTest`, `DailyHistoryMaintenance.ForecastHistoryRow` — no schema change
- Dated `plans/`, `summaries/`, `session-logs/`, `notes/`, `performance/` (historical records)

## Verification

- `./scripts/unit-tests.sh`
- `./gradlew assembleDebug :desktop:createDistributable`
- Install on connected devices; open the screen from the widget; restart desktop and open the window
