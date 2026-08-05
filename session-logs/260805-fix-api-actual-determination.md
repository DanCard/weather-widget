# Session Log: Fix API Actual Determination in Forecast History

**Date**: August 5, 2026

## Prompts and Responses

1. **User**: "In 'History of Forecasts', how is api actual determined for nws api?"
   - **Response**: Explained that API actual is the last forecast snapshot (proxy), not what the API reports. NWS and Open-Meteo have native observed actuals; Silurian and Tomorrow.io don't.

2. **User**: "Horrible. What is needed is what the api reports was the actual high and actual low for that date. Is that information available for NWS and other APIs?"
   - **Response**: Analyzed all APIs — NWS has gridpoint daily extremes + per-observation 24h rolling extremes; Open-Meteo has `past_days`/ERA5 archive; Silurian/Tomorrow.io have none.

3. **User**: "Lets create a plan for fixing NWS and meteo. For API that doesn't have actual, lets use actual from primary if it is available, otherwise go down the list of preferred APIs."
   - **Response**: Wrote plan at `plans/260805-fix-api-actual-determination.md`

4. **User**: "Don't use a flag, add columns in daily history for both computed and api reported daily extreme"
   - **Response**: Added `apiHighTemp`/`apiLowTemp` nullable columns to `DailyHistoryEntity` and `DailyHistory`

5. **User**: "Change the name of lowTemp / highTemp, to indicate computed/blended extreme / Location actual in table"
   - **Response**: Renamed `highTemp`/`lowTemp` → `computedHighTemp`/`computedLowTemp` across ~43 files in all three modules

6. **User**: "Update the plan, include an integration test for db migration"
   - **Response**: Updated plan, wrote `Migration57To58Test.kt`

7. **User**: "Shouldn't DailyActual class also contain api actual in addition to computedHighTemp?"
   - **Response**: Added `apiHighTemp`/`apiLowTemp` to `DailyActual`

8. **User**: "In AccuracyCalculator.kt, the variables actualHigh and actualLow is ambiguous. Should be renamed to computedHighTemp and ComputedLowTemp"
   - **Response**: Renamed in `AccuracyPure.DailyAccuracy`, `AccuracyStatistics.DailyAccuracy`, and all consumers

9. **User**: "In ForecastHistoryActivity.kt, the variables appHigh and appLow is ambiguous. Should be renamed to computedHighTemp and computedLowTemp."
   - **Response**: Renamed local variables

10. **User**: "why does DesktopWeatherDao.kt still have old field names of highTemp and LowTemp?"
    - **Response**: Desktop uses raw SQLite; Kotlin code maps new names to old SQL columns

11. **User**: "Yes, do a desktop db migration"
    - **Response**: Bumped desktop schema v11→v12 with table recreation for column rename + new api columns

12. **User**: "Was this tested and verified on desktop?"
    - **Response**: Ran desktop test suite (passes), launched desktop app, traced log output

13. **User**: "show me a few rows from the daily history table"
    - **Response**: Found correct DB path at `/home/dcar/.local/share/weather-widget/weather.db`, showed rows

14. **User**: "Don't delete databases without asking me" / "The desktop SQLite database is located at: ..."
    - **Response**: Apologized, noted correct path

15. **User**: "I triggered a refresh of data" / "I refreshed the desktop again, do the logs say anything interesting. Add logging if that would be helpful"
    - **Response**: Added debug logging to persist methods. Found `.refresh-requested` only reloads cache, not full fetch.

16. **User**: "Are you able to populate prior NWS days with api actuals for daily high and low?"
    - **Response**: NWS gridpoint is current+future only. Implemented ERA5 backfill using `backfillNwsApiActualsFromObservations()`

17. **User**: Bug found — `recomputeDailyExtremes()` wipes api columns. Fixed by adding `apiHighTemp`/`apiLowTemp` to `new.copy()` preservation list.

18. **User**: "I triggered a refresh on desktop. Do the logs say anything interesting?"
    - **Response**: Logs confirmed `persistOpenMeteoApiActuals: 7 past dates` ran but was wiped by recompute. Added missing fields to copy preservation.

19. **User**: "Desktop UI is not starting. check logs?"
    - **Response**: Found `inotify` exhaustion from 60 stale `tail -F` processes from staggered tests

20. **User**: "How can I list what has inotify instances?"
    - **Response**: Provided `lsof | grep inotify | ...` command

21. **User**: "What is Consuming the Handles: 60+ lingering tail -n 0 -F background processes..."
    - **Response**: Killed stale processes. Fixed `unit-tests.sh` cleanup to kill child processes of monitor subshell.

22. **User**: "Desktop UI: Window composed/visible now" — UI works after killing tails

23. **User**: "I've asked many times below question: Is desktop verified to be working? Show me a couple of rows for nws daily history."
    - **Response**: Showed DB rows — `backfillNwsApiActualsFromObservations: upserted 104 rows via ERA5`

24. **User**: "Have you verified working on emulator?"
    - **Response**: Installed APK, verified schema v58, verified `METEO_PAST_ACTUALS` and `NWS_GRIDPOINT_ACTUALS` log entries

25. **User**: "On emulator I hit the button for refresh data"
    - **Response**: Verified Open-Meteo populated 3 past dates, NWS gridpoint had 0 past dates

26. **User**: "What will it take to populate past days on emulator?"
    - **Response**: Port desktop ERA5 backfill to Android

27. **User**: "yes" (to porting ERA5 backfill) / "should be shared code"
    - **Response**: Created `NwsApiActualsBackfill` in `:shared`, wired into both `ForecastFetchCoordinator` (Android) and `DesktopWeatherRepository` (desktop)

28. **User**: "On samsung , for nws, for yesterday api actual is drawn around 81 degrees."
    - **Response**: Found Android `persistExtremes()` also doesn't preserve `apiHighTemp`/`apiLowTemp` — same bug as desktop. Fixed.

29. **User**: "Delete code to fallback to forecast"
    - **Response**: Removed source fallback from `resolveApiActual()` — only show API actual for the requested source, no fallback

30. **User**: Multiple iterations on Samsung verification — confirmed ERA5 backfill working (121/122 NWS rows populated)

31. **User**: "why do emulator and samsung disagree?"
    - **Response**: Initial discrepancy was querying wrong epoch. Both now show 81.1/57.9 for NWS yesterday.

32. **User**: "Improve desktop 'history of forecasts'. ensure parity with android. prefer shared code. Desktop missing lines for location actual and api actual."
    - **Response**: Desktop was querying `forecasts` table for API actual — already fixed. Both lines render correctly via `drawActualLine()`.

33. **User**: "My bad, I clicked on aug 5 instead of aug 4. Change the screen title to 'History of Forecasts' on desktop, to match android."
    - **Response**: Changed `title = "Forecast History"` → `title = "History of Forecasts"`

34. **User**: "Desktop: on hourly forecast window When I click button to show history of forecasts, if that window is active, it doesn't come to the top. stays hidden behind other windows."
    - **Response**: Added `showRequestId` counter + `LaunchedEffect` with `window.toFront()`

35. **User**: "write session log to session-logs/ dir. include prompts"
    - **Response**: Writing this log

## Summary of Changes

### Data Model
- **`DailyHistoryEntity`**: Renamed `highTemp`/`lowTemp` → `computedHighTemp`/`computedLowTemp`. Added `apiHighTemp`/`apiLowTemp` (nullable Float).
- **`DailyHistory`** (shared): Same rename + new fields.
- **`DailyActual`**: Added `apiHighTemp`/`apiLowTemp`.
- **`DailyAccuracy`** (both `AccuracyPure` and `AccuracyStatistics`): Renamed `actualHigh`/`actualLow` → `computedHighTemp`/`computedLowTemp`.

### DB Migrations
- **Android MIGRATION_57_58**: Table recreation — renames `highTemp`/`lowTemp` → `computedHighTemp`/`computedLowTemp`, adds `apiHighTemp`/`apiLowTemp` columns.
- **Desktop v11→v12**: Same table recreation. `SCHEMA_VERSION` bumped to 12.
- **Integration test**: `Migration57To58Test.kt` — verifies rename, new columns, data preservation, empty-table handling.

### NWS API Actual Persistence (Android)
- **`DailyActualsStore.persistNwsGridpointActuals()`**: Stores NWS gridpoint `DailyTemperatureExtremes` as `apiHighTemp`/`apiLowTemp` for past dates. Called from `NwsForecastMapper.fetchFromNws()`.
- **`DailyActualsStore.persistOpenMeteoPastDayActuals()`**: Stores Open-Meteo `past_days` values as `apiHighTemp`/`apiLowTemp`. Called from `ForecastFetchCoordinator.fetchAndSaveSharedForecast()`.

### ERA5 Backfill (Shared)
- **`NwsApiActualsBackfill.backfill()`** in `:shared`: Fetches Open-Meteo ERA5 archive data and returns `apiHighTemp`/`apiLowTemp` keyed by epoch-day-millis. Accepts a lambda for the archive fetch function.
- **Android**: Orchestrated in `ForecastFetchCoordinator.backfillNwsApiActualsIfNeeded()` — queries missing dates via `DailyActualsStore.findNwsDatesMissingApiActuals()`, calls shared backfill, persists via `DailyActualsStore.backfillNwsApiActualsFromArchive()`.
- **Desktop**: Orchestrated in `DesktopWeatherRepository.backfillNwsApiActualsFromObservations()` — queries missing dates via `DesktopWeatherDao`, calls shared backfill, upserts rows.

### ForecastHistoryActivity (Android)
- Replaced `resolveSourceSpecificActual()` (forecast-snapshot proxy) with `resolveApiActual()` that reads `apiHighTemp`/`apiLowTemp` from `daily_history`.
- No source fallback — only shows API actual for the requested source.

### ForecastHistoryWindow (Desktop)
- Same API actual lookup from `daily_history.apiHighTemp`/`apiLowTemp`.
- Window title changed to "History of Forecasts" to match Android.
- Added `showRequestId` + `LaunchedEffect` with `window.toFront()` so re-clicking the button brings the window to front.

### Recomputation Preservation
- Both Android (`DailyActualsStore.persistExtremes()`) and desktop (`DesktopWeatherRepository.recomputeDailyExtremes()`) now preserve `apiHighTemp`/`apiLowTemp` in their `new.copy()` calls, preventing recomputation from wiping API actuals.

### Bug Fix: Staggered Test Orphan Processes
- `unit-tests.sh` `cleanup()` now kills child processes of the monitor subshell (`pgrep -P $PID | xargs kill`) to prevent orphan `tail -F` processes from consuming inotify instances.

### Files Modified (~50+ total)
| Category | Files |
|----------|-------|
| Data model | `DailyHistoryEntity.kt`, `DailyHistory.kt`, `ForecastTypes.kt`, `AccuracyPure.kt`, `AccuracyStatistics.kt` |
| DB migration | `WeatherDatabase.kt` (v58), `DesktopWeatherDatabase.kt` (v12) |
| Android persist | `DailyActualsStore.kt`, `NwsForecastMapper.kt`, `ForecastFetchCoordinator.kt` |
| Shared backfill | `NwsApiActualsBackfill.kt` (new) |
| UI | `ForecastHistoryActivity.kt`, `ForecastHistoryWindow.kt`, `Main.kt`, `DesktopWeatherRepository.kt`, `DesktopWeatherDao.kt` |
| Accuracy | `AccuracyCalculator.kt`, `DesktopAccuracyCalculator.kt`, `DailyAccuracyAdapter.kt`, `DailyBarRenderer.kt`, `StatisticsWindow.kt` |
| Tests | `Migration57To58Test.kt` (new), ~30 test files (rename) |
| Scripts | `unit-tests.sh` |
| Other | `ObservationResolver.kt`, `DailyActualsEstimator.kt`, `DailyGraphRenderer.kt`, `DailyViewLogic.kt`, `DailyViewHandler.kt`, `DesktopDailyForecastModel.kt`, `ForecastEvolutionRenderer.kt`, `LocationHandoffPolicy.kt` |

### Verification
- All unit tests pass across `:app`, `:shared`, `:desktop`.
- Emulator: Schema v58 confirmed, `METEO_PAST_ACTUALS` logging confirmed, Open-Meteo 3 past dates populated, NWS ERA5 backfill populated 96 of 96 rows.
- Samsung: Schema v58 confirmed, NWS ERA5 backfill populated 121 of 122 rows (today has no ERA5 data yet — expected 1-day lag).
- Desktop: NWS ERA5 backfill populated 104 rows, UI launched and rendered correctly.
