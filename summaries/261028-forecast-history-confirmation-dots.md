# Forecast History Confirmation Dots for Duplicate Fetches

## User Prompts

1. "desktop: nws API, when was it last updated? forecast history says at 3 pm. It is suppose to update every 4 hours."
2. "Should we update history of forecasts with dots even if the fetches are duplicates?"
3. "B"
4. "Code should be shared betweend desktop and android"
5. "write session summary to summaries/"

## Context & Root Cause

1. **User Observation:**
   - On desktop, the user noticed that NWS was supposedly fetching every 4 hours, but Forecast History displayed its newest snapshot at 3:30 PM (`newest 9h ago` at ~1:00 AM), creating confusion about whether the background scheduler had stalled.

2. **Investigation & Findings:**
   - Querying `~/.local/share/weather-widget/weather.db` and daemon logs showed that NWS was actively fetched on cadence:
     - `15:30:30` (3:30 PM, initial fetch)
     - `19:48:49` (7:48 PM, ~4h 18m routine cadence)
     - `22:44:41` (10:44 PM, triggered on source switch)
     - `00:56:00` (12:56 AM, current observation update)
   - In both the 7:48 PM and 10:44 PM fetches, NWS returned the exact same predictions for upcoming days (e.g. Wednesday High 68° / Low 50°).
   - Under `:shared` `DailyForecastWrite` (established 2026-10-24 to prevent database bloat), unchanged daily predictions are not written as new rows (`SNAPSHOT_SKIP`); instead, the existing row's `lastConfirmedAt` is re-stamped with the fetch timestamp while `fetchedAt` remains at the original sighting (3:30 PM).
   - Because `ForecastHistoryWindow` (desktop) and `ForecastHistoryActivity` (Android) only plotted points at `fetchedAt`, the graph stopped at 3:30 PM and the freshness text reported the age of `fetchedAt`, concealing the fact that NWS had verified the forecast at 7:48 PM and 10:44 PM.
   - For yesterday's settled actuals, `ForecastEvolutionCutoff` correctly filtered out post-16:00 fetches as hindcasts.

3. **Solution Decision (Option B):**
   - Synthesize a trailing confirmation point (`p2`) from the existing `lastConfirmedAt` without altering database schema or write paths to bloat SQLite with intermediate identical rows.
   - Place all logic in `:shared` so both desktop and Android share identical behavior.

## Changes

1. **Shared Forecast Evolution Logic (`shared/src/main/kotlin/com/weatherwidget/shared/graph/ForecastEvolutionCutoff.kt`)**:
   - Extended `ForecastEvolutionCutoff.Row` with `lastConfirmedAt: Long? = null`.
   - Updated `ForecastEvolutionCutoff.points(...)` to emit a trailing `EvolutionPoint` at `row.lastConfirmedAt` whenever `row.lastConfirmedAt != null && row.lastConfirmedAt > row.fetchedAt`.
   - Both original and confirmation points pass through `apply(...)`, guaranteeing extreme cutoffs (`SameDayExtremeCutoff.HIGH_CUTOFF` = 16:00, `LOW_CUTOFF` = 06:00) and settled extreme times continue to drop hindcasts on past or settled days.

2. **Desktop Database DAO (`shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt`)**:
   - Updated `getForecastEvolution` SQL query to include `lastConfirmedAt`:
     `SELECT targetDate, dateOfPrediction, source, highTemp, lowTemp, fetchedAt, lastConfirmedAt FROM forecasts`.
   - Populated `DesktopForecastRow.lastConfirmedAt = rs.getLong("lastConfirmedAt")`.

3. **Desktop Forecast History UI (`desktop/src/main/kotlin/com/weatherwidget/desktop/ForecastHistoryWindow.kt`)**:
   - Passed `it.lastConfirmedAt` to `ForecastEvolutionCutoff.Row(...)`.
   - `points.maxByOrNull { it.fetchedAt }?.fetchedAt` now naturally reflects the latest confirmed fetch time, updating the freshness text (e.g., `newest 2h ago` instead of `newest 9h ago`) and extending the horizontal forecast curve and dots to the latest confirmed time.

4. **Android Forecast History UI (`app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`)**:
   - Passed `it.lastConfirmedAt` from Room's `ForecastEntity` to `ForecastEvolutionCutoff.Row(...)`, ensuring identical dual-platform behavior.

5. **Unit Tests**:
   - `shared/src/test/kotlin/com/weatherwidget/shared/graph/ForecastEvolutionCutoffTest.kt`:
     - Added `points synthesizes a confirmation point when lastConfirmedAt is greater than fetchedAt`.
     - Added `points drops confirmation point when lastConfirmedAt is after extreme cutoff`.
     - Added `points does not duplicate when lastConfirmedAt is null or equal to fetchedAt`.
   - `shared/src/test/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDaoTest.kt`:
     - Added assertion verifying `lastConfirmedAt` is populated and greater than 0 on evolution rows.

## Verification

1. **Unit Tests**:
   - Executed `./scripts/unit-tests.sh`.
   - All **6,234 tests passed** across `:shared`, `:desktop`, and `:app` (2,832 shared, 771 desktop, 908 short, 26 localization, 246 medium, 1,451 long).

2. **Desktop Runtime Build & Restart**:
   - Executed `./scripts/buildStart-desktop.sh`.
   - Verified distributable build completed successfully and launched via autostart.
   - Inspected `autostart-*.log`: headless daemon and UI initialized cleanly.
