# Code Review Queue — Prioritized by Risk Score

Generated from metrics gathered on 2026-07-25.

## Scoring Method

Each file scored on 4 axes (0-3 each), summed for a total risk score (max 12):

| Metric | 3 pts | 2 pts | 1 pt |
|--------|-------|-------|------|
| **Size** | >1000 lines | 500-1000 | <500 |
| **Churn** (6mo commits) | >100 | 50-100 | <50 |
| **Complexity** (branch count) | >150 | 75-150 | <75 |
| **No tests** | No test file | — | Has tests |

## Priority 1 — Critical (score ≥ 9)

| Score | File | Lines | Churn | Branches | Tests | Notes |
|-------|------|-------|-------|----------|-------|-------|
| **11** | `app/.../widget/handlers/DailyViewHandler.kt` | 1211 | 187 | 107 | ✅ | Highest churn + very large + complex |
| **11** | `app/.../widget/WeatherWidgetProvider.kt` | 1256 | 187 | 145 | ✅ | Entry point, high churn, very complex |
| **11** | `app/.../widget/TemperatureGraphRenderer.kt` | 1010 | 159 | 133 | ✅ | High churn, complex rendering |
| **10** | `app/.../widget/DailyForecastGraphRenderer.kt` | 1396 | 138 | 171 | ✅ | Very large, complex rendering |
| **10** | `app/.../widget/handlers/WidgetIntentRouter.kt` | 1234 | 107 | 134 | ✅ | Large, high churn, complex routing |
| **10** | `app/.../data/repository/ForecastRepository.kt` | 1406 | 89 | 239 | ✅ | Largest file, highest branch count |
| **9** | `app/.../widget/WeatherWidgetWorker.kt` | 936 | 103 | 108 | ❌ | No tests, high churn, large |
| **9** | `app/.../widget/PrecipitationGraphRenderer.kt` | 931 | 96 | — | ✅ | Large, high churn |
| **9** | `app/.../widget/handlers/PrecipViewHandler.kt` | 658 | 101 | — | ✅ | High churn |

## Priority 2 — High (score 7-8)

| Score | File | Lines | Churn | Branches | Tests | Notes |
|-------|------|-------|-------|----------|-------|-------|
| **8** | `app/.../widget/GraphRenderUtils.kt` | 1199 | 45 | 168 | ✅ | Large, complex, moderate churn |
| **8** | `app/.../widget/handlers/TemperatureViewHandler.kt` | 521 | 98 | — | ✅ | High churn |
| **8** | `app/.../widget/handlers/CloudCoverViewHandler.kt` | 611 | 74 | — | ✅ | High churn |
| **8** | `app/.../widget/handlers/DailyViewLogic.kt` | 690 | 82 | 114 | ✅ | High churn, complex |
| **8** | `app/.../widget/WidgetStateManager.kt` | 992 | 81 | 92 | ✅ | Large, high churn |
| **8** | `app/.../data/repository/ObservationRepository.kt` | 957 | 55 | 123 | ✅ | Large, complex |
| **8** | `app/.../widget/handlers/TemperatureStateResolver.kt` | 694 | 45 | — | ❌ | No tests, large |
| **8** | `app/.../widget/CloudCoverGraphRenderer.kt` | 538 | 48 | — | ✅ | Moderate churn |
| **7** | `app/.../data/repository/CurrentTempRepository.kt` | 626 | 45 | — | ✅ | Large |
| **7** | `app/.../ui/ForecastHistoryActivity.kt` | 792 | 51 | 156 | ✅ | Large, complex |
| **7** | `app/.../widget/handlers/TemperatureTouchTargets.kt` | 461 | 22 | — | ❌ | No tests |
| **7** | `app/.../widget/DailyForecastRainLabelRenderer.kt` | 419 | — | — | ❌ | No tests |

## Priority 3 — Medium (score 5-6)

| Score | File | Lines | Churn | Branches | Tests | Notes |
|-------|------|-------|-------|----------|-------|-------|
| **6** | `app/.../widget/handlers/TemperatureHourDataBuilder.kt` | 362 | 29 | — | ❌ | No tests |
| **6** | `app/.../widget/handlers/DailyGraphRenderer.kt` | 402 | — | — | ❌ | No tests |
| **6** | `app/.../widget/handlers/DailyHeaderBinder.kt` | 279 | — | — | ❌ | No tests |
| **6** | `app/.../widget/DailyForecastHeaderRenderer.kt` | 282 | — | — | ❌ | No tests |
| **6** | `app/.../widget/ObservationResolver.kt` | — | 36 | — | ❌ | No tests |
| **6** | `app/.../widget/WidgetRenderer.kt` | — | 29 | — | ❌ | No tests |
| **6** | `app/.../widget/handlers/TemperatureViewBinder.kt` | — | 26 | — | ❌ | No tests |
| **6** | `app/.../di/AppModule.kt` | 339 | 47 | — | ❌ | No tests, high imports |
| **6** | `app/.../data/local/WeatherDatabase.kt` | 387 | 64 | — | ❌ | No tests, high churn |
| **6** | `app/.../ui/ConfigActivity.kt` | 561 | — | 77 | ✅ | Large activity |
| **6** | `app/.../ui/WeatherObservationsActivity.kt` | 617 | 40 | 73 | ✅ | Large activity |

## Desktop Files (cross-module)

| Score | File | Lines | Churn | Branches | Tests | Notes |
|-------|------|-------|-------|----------|-------|-------|
| **9** | `desktop/.../Main.kt` | 1602 | 97 | 280 | ❌ | Largest file, no tests, complex |
| **8** | `desktop/.../DaemonProcess.kt` | 771 | — | 160 | ❌ | No tests, complex |
| **8** | `desktop/.../DesktopGraphUtils.kt` | 715 | — | 80 | ❌ | No tests |
| **8** | `desktop/.../DesktopWeatherService.kt` | 637 | 33 | 76 | ❌ | No tests |
| **8** | `desktop/.../DesktopWeatherRepository.kt` | 656 | 39 | 120 | ❌ | No tests |
| **8** | `desktop/.../TemperatureGraph.kt` | 878 | 54 | 126 | ❌ | No tests |
| **8** | `desktop/.../DailyForecastGraph.kt` | 765 | 38 | 138 | ❌ | No tests |
| **8** | `desktop/.../ForecastHistoryWindow.kt` | 585 | — | 100 | ❌ | No tests |
| **7** | `desktop/.../DesktopDailyForecastModel.kt` | — | 21 | 85 | ❌ | No tests |
| **7** | `desktop/.../PrecipitationGraph.kt` | 487 | — | — | ❌ | No tests |
| **7** | `desktop/.../ObservationsWindow.kt` | 467 | — | — | ❌ | No tests |
| **7** | `desktop/.../SettingsWindow.kt` | 416 | — | — | ❌ | No tests |
| **7** | `desktop/.../DesktopProcess.kt` | 352 | — | — | ❌ | No tests |

## Shared Files (cross-module)

| Score | File | Lines | Churn | Branches | Tests | Notes |
|-------|------|-------|-------|----------|-------|-------|
| **8** | `shared/.../graph/TemperatureLabelEngine.kt` | 1108 | — | 212 | ❌ | No tests, very large, complex |
| **8** | `shared/.../graph/TemperatureLabelResolver.kt` | 930 | 25 | 213 | ✅ | Complex |
| **7** | `shared/.../actuals/ActualTemperatureSeriesBuilder.kt` | 587 | — | 119 | ✅ | Complex |
| **7** | `shared/.../data/local/desktop/DesktopWeatherDao.kt` | 1082 | 38 | 127 | ❌ | No tests, very large |
| **6** | `shared/.../remote/NwsApi.kt` | 603 | — | — | ✅ | Large API client |
| **6** | `shared/.../util/WeatherConditionResolver.kt` | — | — | 72 | ✅ | Complex logic |

## Recommendations

### Immediate review targets (top 5)
1. **`DailyViewHandler.kt`** — highest churn + largest + most complex. Likely a god class.
2. **`WeatherWidgetProvider.kt`** — entry point, 187 changes in 6 months, 1256 lines.
3. **`TemperatureGraphRenderer.kt`** — 159 changes, 1010 lines, rendering bugs are high-visibility.
4. **`ForecastRepository.kt`** — largest file (1406 lines), highest branch count (239). Refactor candidate.
5. **`WeatherWidgetWorker.kt`** — 936 lines, 103 changes, **no tests**. Background fetch is critical path.

### Refactor candidates
- **`ForecastRepository.kt`** (1406 lines) — split by responsibility
- **`DailyViewHandler.kt`** (1211 lines) — extract rendering from logic
- **`TemperatureLabelEngine.kt`** (1108 lines, shared) — shared code, no tests
- **`DesktopWeatherDao.kt`** (1082 lines, shared) — no tests, very large

### Test gap closures
- **`WeatherWidgetWorker.kt`** — critical path, no tests
- **`TemperatureStateResolver.kt`** — 694 lines, no tests
- **`TemperatureTouchTargets.kt`** — 461 lines, no tests
- **`DailyForecastRainLabelRenderer.kt`** — 419 lines, no tests
- **`TemperatureHourDataBuilder.kt`** — 362 lines, no tests
- **All desktop files** — zero test coverage across the entire module
