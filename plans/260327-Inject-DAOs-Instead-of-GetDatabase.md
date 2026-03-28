# Plan: Replace WeatherDatabase.getDatabase() with injected DAOs (Code Review #9)

## Problem

40 call sites across 15 files call `WeatherDatabase.getDatabase(context)` directly, bypassing Hilt DI.
This defeats testability and couples every caller to the singleton database pattern.

## Approach: Individual DAO params (no DaoProvider wrapper)

- **Strategy A** (Hilt-injected classes): Add `@Inject` DAO fields
- **Strategy B** (Kotlin `object` singletons): Thread DAO params from callers
- **Strategy C** (Non-Hilt classes): Use `EntryPointAccessors` or accept DAOs in constructor

## Phases

### Phase 1: View Handlers + WidgetRenderer (Strategy B)

These `object` singletons only need `appLogDao`. Add `appLogDao: AppLogDao` param to their public methods.

| File | getDatabase() calls removed |
|------|---------------------------|
| `DailyViewHandler.kt` | 3 |
| `TemperatureViewHandler.kt` | 3 |
| `PrecipViewHandler.kt` | 1 |
| `CloudCoverViewHandler.kt` | 1 |
| `WidgetRenderer.kt` | 1 |

Then update callers to pass `appLogDao`.

### Phase 2: WidgetIntentRouter (Strategy B)

10 `handle*` methods — add individual DAO params (forecastDao, hourlyForecastDao, appLogDao, etc.)
to each method that needs them. Remove 10 `getDatabase()` calls.

### Phase 3: Hilt-injected classes (Strategy A)

| File | DAOs to inject | getDatabase() calls removed |
|------|---------------|---------------------------|
| `WeatherWidgetProvider.kt` | appLogDao, forecastDao, hourlyForecastDao, dailyExtremeDao | 3 |
| `WeatherWidgetWorker.kt` | Check existing injection, add appLogDao if needed | 1 |
| `StatisticsActivity.kt` | forecastDao | 1 |

### Phase 4: Non-Hilt classes (Strategy C)

| File | Change | Calls removed |
|------|--------|--------------|
| `UIUpdateScheduler.kt` | Add forecastDao, hourlyForecastDao to constructor | 1 |
| `ScreenOnReceiver.kt` | Use EntryPointAccessors for appLogDao | 2 |
| `DataFreshness.kt` | Add DAO params to isDataStale() etc. | 3 |

### Phase 5: Tests

- Update test files to pass DAOs to changed APIs
- Tests can keep using getDatabase() for setup/verification (they control the DB instance)

### Phase 6: Cleanup

- Verify getDatabase() only called from AppModule and test utilities
- Build + test: ./gradlew compileDebugKotlin && ./gradlew test

## What stays

- `WeatherDatabase.getDatabase()` in WeatherDatabase.kt (Room builder, needed by AppModule + tests)
- `AppModule.kt` line 92 (DI provider)
- Test utilities (AndroidTestDatabase.kt, test setup)
