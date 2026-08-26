# Plan: Open-Meteo Historical Actuals Backfill

## Problem Statement
When viewing past dates/hours on Open-Meteo (e.g. in the hourly temperature graph or forecast history/accuracy viewer), historical actual observations may be missing if not previously cached or if historical depth was not pulled. Currently, on-demand backfill mechanisms (`ensureHistory` / `needsDeeperHistory` on Desktop, and `backfillDailyExtremesIfNeeded` on Android) only handle NWS or WeatherAPI, leaving Open-Meteo with gaps in historical actuals rather than invoking a backfill.

---

## Root-Cause Analysis

1. **Desktop History Ingestion (`needsDeeperHistory` / `ensureHistory`)**:
   - In `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt`:
     - `needsDeeperHistory(neededBackHours)` only checks `NWS` and `WEATHER_API`. For `OPEN_METEO`, it returns `false`.
     - `ensureHistory(neededBackHours)` returns `false` immediately for any source other than `NWS` and `WEATHER_API`.
     - `weatherService.fetchHistory(historyDays)` calls Open-Meteo with `historyDays`, but its returned history is not converted via `HistoricalActualsBackfill.build(...)` or stored as observations when `ensureHistory` is invoked.

2. **Android History Backfill (`ForecastHistoryActivity`)**:
   - In `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`:
     - `backfillDailyExtremesIfNeeded(lat, lon)` hardcodes checks exclusively for `WeatherSource.NWS.id`, ignoring missing historical extremes for other active actuals sources like `OPEN_METEO`.
   - In `ForecastFetchCoordinator.kt`:
     - Forecast fetches must consistently pass `historyDays = 7` (or configured history depth) so past hours are always included in the initial fetch and backfilled to `ObservationEntity`.

---

## Proposed Changes

### 1. Desktop App (`:desktop`)
- **`DesktopWeatherRepository.kt`**:
  - Update `needsDeeperHistory(neededBackHours: Int)`:
    - Include `WeatherSource.OPEN_METEO.id`, returning `true` if `neededHistoryDays(neededBackHours) > deepestHistoryDaysFetched`.
  - Update `ensureHistory(neededBackHours: Int)`:
    - When `weatherSource == WeatherSource.OPEN_METEO.id`:
      - Compute `neededDays = neededHistoryDays(neededBackHours)`.
      - Guard with `historyFetchMutex` and `deepestHistoryDaysFetched`.
      - Call `weatherService.fetchHistory(neededDays)` to fetch Open-Meteo forecast with `historyDays = neededDays`.
      - Run `HistoricalActualsBackfill.build(hourly = rawFetch.subHourly.ifEmpty { rawFetch.hourly }, ...)` for past hours.
      - Save observations via `weatherDao.upsertObservations(...)`.
      - Call `recomputeDailyExtremes(currentTimeMillis())` so daily accuracy and extremes update immediately.
      - Advance `deepestHistoryDaysFetched = neededDays`.
  - If missing observations are detected during graph loading or refresh under Open-Meteo, trigger `ensureHistory()`.

### 2. Android App (`:app`)
- **`ForecastHistoryActivity.kt`**:
  - Generalize `backfillDailyExtremesIfNeeded(lat, lon)` to inspect all enabled sources supporting temperature actuals (`source.supportsTemperatureActuals`), including `OPEN_METEO`.
  - If any enabled actuals source has past forecast dates without corresponding `daily_history` extremes, enqueue an immediate sync (`history_missing_extremes_<SOURCE>`) so missing actuals are fetched and daily extremes derived.
- **`ForecastFetchCoordinator.kt`**:
  - Ensure Open-Meteo full forecast fetches supply `historyDays = 7` to retrieve and persist 7 days of 15-minute / hourly historical observations via `HistoricalActualsBackfill`.

---

## Verification Plan

### Automated Tests
- **Desktop Unit Tests (`:desktop`)**:
  - In `DesktopBackfillIntegrationTest.kt` (or dedicated test class), add tests verifying:
    - `needsDeeperHistory` returns `true` when Open-Meteo graph requires more hours than `deepestHistoryDaysFetched`.
    - `ensureHistory` fetches Open-Meteo history, builds backfill observations, upserts to DB, and recomputes daily extremes.
- **Android Unit Tests (`:app`)**:
  - Verify `ForecastHistoryActivity` / backfiller tests pass and detect missing Open-Meteo history dates.
- **Full Suite**:
  - Run `./gradlew test` to ensure all tests across `:shared`, `:desktop`, and `:app` pass cleanly.

### Manual / Device Verification
- On Desktop:
  - Select Open-Meteo as active source.
  - Open temperature graph and scroll/zoom back past today.
  - Verify "Fetching older data…" toast appears, history backfills, and past actuals line renders continuously.
  - Verify `weather.db` contains `OPEN_METEO_MAIN` observation rows for the backfilled past days.
