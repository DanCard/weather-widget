# Database Retention Cutoffs

Summary of automated database retention cutoff policies across Android and Desktop platforms.

## Retention Cutoffs Table

| Platform | Target Table(s) | Retention Cutoff | Execution Trigger | Primary Rationale & Purpose | Code Location |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Android** | `daily_history` | **13 Months (395 days)** | End of full forecast network sync (`cleanOldData`) | Long-term daily history browsing in Forecast History Activity & accuracy stats | [`ForecastRepository.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt#L1288) |
| **Android** | `forecasts`, `hourly_forecasts`, `hourly_forecast_history` | **30 Days** | End of full forecast network sync (`cleanOldData`) | Rolling 30-day forecast and hourly graph cache | [`ForecastRepository.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt#L1284-L1286) |
| **Android** | `observations` | **10 Days** | End of full forecast network sync (`cleanOldData`) | Station observations used for actual highs/lows and delta interpolation | [`ForecastRepository.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt#L1287) |
| **Android** | `app_logs` | **72 Hours (3 days)** | End of full forecast network sync (`cleanOldData`) | Sparse diagnostic events & sync tracking | [`ForecastRepository.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt#L1289) |
| **Desktop** | `forecasts`, `hourly_forecasts`, `hourly_forecast_history`, `daily_history`, `observations`, `app_logs`, `station_cache` | **18 Months (547 days)** | End of forecast refresh (`weatherDao.cleanup`) | Comprehensive multi-season graph zooming, actuals analysis & history | [`DesktopWeatherRepository.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt#L215-L225) |

---

## Related Constants & Navigation Boundaries

### Android
- **Max History Navigation Ceiling**: `MAX_HISTORY_DAYS_BACK = 395L` (13 months)
  - [`ForecastHistoryActivity.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt#L67)
  - [`ForecastHistoryViewLogic.kt`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/graph/ForecastHistoryViewLogic.kt#L13)

### Desktop
- **Max Retention & Lookback Limits**: `547L` (18 months)
  - `DB_RETENTION_DAYS = 547L`
  - `MAX_HISTORY_DAYS = 547`
  - `ACTUALS_HISTORY_DAYS = 547L`
  - `CHANCE_BACKFILL_LOOKBACK_DAYS = 547L`
  - [`DesktopWeatherRepository.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt#L623-L635)
