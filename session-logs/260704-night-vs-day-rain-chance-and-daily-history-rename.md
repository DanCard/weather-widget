# Session Log: Night Rain Chance Bug + daily_history Rename/Parity

**Date:** Saturday, July 4, 2026
**Topic:** NWS night rain chance 9% vs 14% discrepancy; past-day rain-chance parity via daily_extremes → daily_history rename + forecast chance snapshot
**Status:** Completed

## Prompts (verbatim, in order)

### 1.
> emulator: is there a bug with the night vs day rain chance?
> Tonight rain chance is 9%.  Should it be 14%.  What does the api hourly rain chance for that time frame say?
> On the website it says rain chance is 14% at 7 am.  So if the cutoff between night and day is 8 am, than rain chance should be 14% for night rain chance not 9%.  Do not make major changes to the code with discussing with me first.

### 2.
> Option 1

(Selecting "drop the NWS-direct period branch, always use the hourly 8pm–8am window max" from the two options presented after prompt 1's investigation.)

### 3.
> Create a plan for the change and write the plan to plans/ dir

### 4.
> Go ahead and implement the plan

### 5.
> Tell me more about : ... past-day path was intentionally left untouched

### 6.
> Make a plan to have past days work the same as future days.  Maybe store values in temperature extremes table?  I'm open to renaming table to daily history or something like that.

### 7. (AskUserQuestion answers)
- "Should the daily_extremes table (and its DailyExtreme* classes) be renamed to daily_history as part of this change?" → **Rename now, same change**
- "Should existing past days (last ~30 days) get a one-time backfill of the day/night chance values?" → **Yes, backfill (Recommended)**

### 8.
> implement plans/260704-past-day-rain-chance-parity-daily-history.md

(Preceded by a local `/model` command switching the session's default model to Sonnet 5 — not a prompt to act on.)

### 9.
> continue

(Sent after a `[Request interrupted by user]` mid-way through initial TaskCreate calls; resumed the same instruction.)

### 10.
> write session log to session-logs/ dir .  Include all prompts.

## Objective

Two related fixes, done as two sequential plan/implement cycles:

1. **Immediate bug**: the widget showed tonight's rain chance as 9% (NWS's raw "Tonight" 6pm–6am period field) when the true 8pm–8am night-window max was 14% (driven by a 7am spike NWS's own period boundary excludes). Fix: stop trusting NWS's period field directly for *live* (non-past) days; always use the shared hourly 8am–8pm/8pm–8am window-max logic for every source.
2. **Follow-up parity bug**: after fix #1, a *past* day's rain label could still change when it rolled from "today" into history, because the past-day code path was left on the old raw period fields (since recomputing from the live `hourly_forecasts` table for closed days would read hindcast-drifted data, not what was actually shown). Fix: snapshot the resolved, as-displayed chance into a renamed `daily_history` table (was `daily_extremes`) while each day is still live, replay that snapshot once the day is history, and backfill 30 days of pre-existing rows from the `hourly_forecast_history` archive.

## Changes

### Fix 1 — night rain chance uses hourly window max, not NWS period field
- `shared/.../DailyRainLabels.kt`: deleted the `useDirectNwsPeriodPrecip` branch in `resolveDailyLabelPrecip`; folded its `?: precipProbability` fallback into the general day chain.
- `app/.../DailyForecastIconResolver.kt`, `desktop/.../DesktopDailyForecastModel.kt`: dropped the now-unused `rowSourceId` argument; updated comments.
- `shared/.../NwsDailyMapper.kt`: updated stale comment.
- Tests: rewrote `directNwsUsesPeriodChanceOverSparseHourlyMax` → `nwsUsesHourlyWindowMaxLikeOtherSources`; added `nightWindowIncludesEarlyMorningHoursNwsPeriodExcludes` (pins the exact 9%→14% scenario); updated one `DailyViewLogicTest` case.
- Verified on the emulator: pulled `hourly_forecasts`, confirmed NWS 7am = 14% vs stored period field 9%; after the fix, screenshot showed the widget displaying 14%.

### Fix 2 — daily_history rename + forecast chance snapshot/backfill for past-day parity
- **Rename**: `daily_extremes` table → `daily_history`; `DailyExtremeEntity`→`DailyHistoryEntity`, shared `DailyExtreme`→`DailyHistory`, `DailyExtremeDao`→`DailyHistoryDao`, desktop DAO methods, log tags (`DAILY_EXTREME_OVERWRITE`→`DAILY_HISTORY_OVERWRITE`, etc.) — ~50 files, mechanical sed-based rename.
  - **Near-miss caught**: running a compile mid-rename (before bumping `@Database(version=...)`) silently overwrote the *already-shipped* v50 Room schema JSON with the new table name. Caught via `git status` on `app/schemas/`; restored with `git checkout` and reordered (bump version first).
  - **Second catch**: the blind sed also renamed the literal SQL strings inside already-shipped historical migrations (`MIGRATION_44_45`, `MIGRATION_45_46`, `healCorruptDatabaseVersion`) and the instrumented migration test's v45 seed data — all reverted back to `daily_extremes` since those migrations operate on real databases that still have the old name at that point in the chain.
- **Schema**: Android `WeatherDatabase` v50→51 (`MIGRATION_50_51`: rename table, drop/recreate the auto-named index, add two new columns); desktop `DesktopWeatherDatabase` schema v5→6. New migration test `migrate50To51_...` added and passed on-device.
- **New columns**: `forecastDayPrecipChance` / `forecastNightPrecipChance` (nullable Int) on `daily_history`.
- **Shared resolution**: extracted the non-past branch of `resolveDailyLabelPrecip` into `DailyRainLabels.resolveLiveDayNightChance` — reused both by the live label path and the new snapshot writer, guaranteeing the stored value can never drift from what was actually displayed.
- **Write hooks** (`ForecastRepository.snapshotDisplayedRainChance` / `DesktopWeatherRepository.snapshotDisplayedRainChance`): resolve and store the chance for yesterday/today on every non-UI-only fetch. Each of a date's day (8am–8pm) and night (8pm–8am) windows is gated independently on whether it's still open — closed windows are never re-derived, because the live `hourly_forecasts` table is REPLACE'd on every fetch and would otherwise silently drift the archive with later, different re-forecast data ("hindcast drift", same root cause as the existing hourly-graph hindcast-vs-live split).
  - **Bug caught by a real test failure**: the hourly query range for "today" originally stopped at tomorrow midnight instead of extending through the 8am cutoff — a genuine off-by-range bug found by a failing assertion (expected 14, got 9), not just inspection.
  - **Clobber bug caught by design review, then pinned with tests**: the pre-existing actuals writers (`ObservationRepository.recomputeDailyExtremesForDay`, `DesktopWeatherRepository.recomputeDailyExtremes`) rebuild `daily_history` rows from scratch and do a full-row `REPLACE` — without carrying over the new chance columns from the existing row first, every actuals recompute would silently wipe the snapshot. Fixed on both platforms; regression tests added.
- **Read path**: `resolveDailyLabelPrecip`'s `isPast` branch now prefers `storedDayPrecipChance`/`storedNightPrecipChance`, falling back to the raw period fields for history predating this feature. Wired through `DailyForecastIconResolver`, `DailyViewLogic` (both call sites), and `DesktopDailyForecastModel`.
- **Backfill**: one-time 30-day backfill (`ForecastRepository.backfillForecastChanceSnapshotsIfNeeded` / `DesktopWeatherRepository.backfillForecastChanceSnapshotsIfNeeded`), reading `hourly_forecast_history` (the as-predicted archive, never the live REPLACE'd table) via `HourlyForecastStitcher.stitch` (Android) / `getHourlyHistory` (desktop, already freshest-per-hour). Gated by a SharedPreferences flag on Android and an `app_logs` tag marker on desktop (reusing the existing pipeline-health log rather than adding a new config field, to avoid the documented desktop config.json write-race risk). Best-effort: rows with no matching history are left null, same as pre-feature behavior.

## Verification

- Full unit test sweep across `:shared`, `:app`, `:desktop` — all green, run repeatedly after each of the 8 implementation sub-tasks.
- Instrumented `WeatherDatabaseMigrationTest` on the emulator — all 6 cases pass, including the new v50→51 case.
- Real end-to-end emulator verification: installed the updated APK over the emulator's existing v50 database (real migration, not a fresh install); forced staleness via a targeted, reversible edit to `weather_fetch_metadata.xml` (not `pm clear`) to trigger a genuine non-UI-only background sync; confirmed via `app_logs` (`REFRESH_DECISION`, `SYNC_STAGE`) that the real fetch → actuals recompute → snapshot write → backfill sequence ran; pulled the DB and confirmed `daily_history.forecastNightPrecipChance = 14` for today (NWS), with older backfilled days correctly populated (0, not null, matching their actual no-rain history); screenshot confirmed the widget displays **14%** for tonight.

## Files Modified (representative, not exhaustive — ~65 files touched by the mechanical rename)

- `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt`
- `shared/src/main/kotlin/com/weatherwidget/data/model/DailyHistory.kt` (renamed from `DailyExtreme.kt`)
- `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt`, `DesktopWeatherDatabase.kt`
- `app/src/main/java/com/weatherwidget/data/local/DailyHistoryEntity.kt`, `DailyHistoryDao.kt` (renamed), `WeatherDatabase.kt`
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`, `ObservationRepository.kt`, `WeatherRepository.kt`
- `app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`, `WeatherWidgetWorker.kt`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt`, `DesktopDailyForecastModel.kt`
- New tests: `ForecastRepositorySnapshotDisplayedRainChanceTest.kt`, `ForecastRepositoryBackfillChanceSnapshotTest.kt`, `DesktopSnapshotDisplayedRainChanceTest.kt`, `DesktopBackfillChanceSnapshotTest.kt`
- `app/schemas/com.weatherwidget.data.local.WeatherDatabase/51.json` (new, generated)

## Memory Recorded

- `feedback_room_schema_export_rename_order.md` — bump `@Database` version before/with an entity rename, or an interim compile silently corrupts the already-shipped schema JSON for the current version.
- `night_rain_chance_window_gating.md` — the daily_history chance-snapshot design and the general "hindcast drift" hazard for any writer that archives a live value by re-deriving from a REPLACE-overwritten table.
