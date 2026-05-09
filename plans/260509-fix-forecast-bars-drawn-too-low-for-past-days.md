# Fix forecast bars drawn too low for past days

## Context

On the daily forecast view, yellow forecast-overlay bars for past Wed/Thu were rendering with bottoms way below the actual lows. Repro confirmed on Samsung Z Fold3, Pixel 7 Pro, and emulator. The widget initially drew correctly, then redrew incorrectly after a background `WeatherWidgetWorker` run.

**Root cause** (verified in DB and logs): Commit `6984b1d` (2026-05-05) narrowed the forecast snapshot fetch range from -30/+14 to -1/+7 to fix a Pixel CursorWindow 2MB crash. As a side effect, the in-memory `forecastSnapshots` map passed to the renderer no longer contains entries for past dates beyond yesterday. In `DailyViewLogic.kt:344-358`, when `forecast?.highTemp/lowTemp` is null for a past day, the code falls back to `climateNormals` — and SF Bay Area May normals are 43-48°F lows (well below actual ~53-56°F). That fallback is what stretched the yellow forecast bar down.

Logs at 11:04:22 confirm: `forecastSnapshotKeys=[2026-05-08, ..., 2026-05-16]` (May 6/7 missing). The DB still has the snapshots — they just weren't loaded into the map.

The IntentRouter (navigation path) already loads -30/+30, which is why scrolling momentarily fixes things — but the next Worker run overwrites the map with the narrow range.

## Approach

Two changes per the user's direction ("don't load climate normals when navigating into history; lazy-load on navigate"):

1. **Remove climate-normal fallback for past-day forecast overlay.** Better to show no forecast bar than a wrong one synthesized from monthly averages.
2. **Unify all 3 fetch paths** (Worker, Provider startup, IntentRouter) to use the existing efficient `getLatestForecastsInRange` DAO method for the past-day window. This dedupes at SQL level (`MAX(batchFetchedAt)`) — ~5 rows/day instead of ~280 — so we can safely load 30 days back without exceeding the CursorWindow.

The today-bar's triple-bar logic in `DailyViewLogic.kt:361-389` needs older snapshots (filter `isBefore(yesterdaySameTime)`), so the recent window (`today-1` to `today+N`) keeps using the full `getAllForecastsInRange` query.

## Changes

### 1. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt:344-358`

In the `if (showComparison)` block (past-day branch), drop the climate-normal fallback. If `forecast?.highTemp/lowTemp` is null, leave `fHigh`/`fLow` null so the renderer skips the overlay.

Add a `Log.d(TAG, "...")` when past day has no forecast snapshot, so we can monitor in field.

Keep `weather?.isClimateNormal` branch only for the **future-day** branch at line 390+ (not past). Verify it's not relied on for past days elsewhere.

### 2. `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt:197-210`

Change `fetchForecastSnapshots` to two queries merged:
- `getLatestForecastsInRange(today-30, today-2, lat, lon)` → past days, small result
- `getAllForecastsInRange(today-1, today+7, lat, lon)` → recent window, preserves snapshot history needed by today's triple-bar logic

Combine and group by `LocalDate`.

### 3. `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt:170-199`

Apply the same two-query merge inside `forecastSnapshotsDeferred`. Currently uses `getAllForecastsInRangeForSources` with `historyStart = today-1`. Change to use `getLatestForecastsInRangeBySource`-equivalent for the past window plus `getAllForecastsInRangeForSources` for the recent window.

(Note: Provider currently filters by `activeSourceList`. We'd need a `getLatestForecastsInRangeForSources` DAO method, OR call the existing per-source one in a loop. The single-source variant `getLatestForecastsInRangeBySource` exists at `ForecastDao.kt:158`. Adding a multi-source `getLatestForecastsInRangeForSources` is straightforward — mirror the `IN (:sources)` pattern from `getAllForecastsInRangeForSources` at line 218.)

### 4. `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt:711-719`

Same two-query merge — use the efficient query for past days, full query for recent window. Removes the latent CursorWindow risk.

## Critical files

- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` (lines 340-360)
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt` (lines 197-210)
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` (lines 170-199)
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt` (lines 711-719, possibly 150-151, 475-476)
- `app/src/main/java/com/weatherwidget/data/local/ForecastDao.kt` (add `getLatestForecastsInRangeForSources` if needed for Provider)

## Reused utilities

- `ForecastDao.getLatestForecastsInRange` (line 183) — already implements DB-level dedup via `MAX(batchFetchedAt)`
- `ForecastDao.getLatestForecastsInRangeBySource` (line 158) — single-source variant
- `ForecastDao.getAllForecastsInRange` (line 200) — keep for recent window
- `WidgetConstants.MS_IN_A_DAY` — date math constant

## Verification

1. **Repro before fix**: On emulator, confirm `forecastSnapshotKeys` log line shows narrow range (`[today-1 ... today+7]`) after a Worker run, and yellow forecast bars on past Wed/Thu visibly extend below the white actual bars to ~45-48°F (climate normal range).

2. **After fix — climate fallback removal**:
   - Navigate to a past day with no forecast snapshot in DB → no yellow bar drawn (was previously drawn at climate-normal heights).
   - Past days WITH snapshots (most days) → yellow bar matches DB forecast values.

3. **After fix — Worker range expansion**:
   - Trigger a Worker run via `adb shell am broadcast -a com.weatherwidget.UPDATE_WIDGETS` or similar.
   - Verify log `prepareGraphDays: forecastSnapshotKeys=[...]` includes `today-30 ... today+7` (full range).
   - Verify `Snapshots=N` count in `SYNC_SUCCESS` log is small (<400 rows for 38 days × ~5 sources).

4. **No CursorWindow regression**: Watch logcat during full app data fetch. No `IllegalStateException: Couldn't read row 0, col 0 from CursorWindow`.

5. **Today's triple bar still works**: Today's column should still show yellow snapshot line (sourced from >24h-old snapshots) — this depends on the recent-window full query continuing to return all today's snapshots.

6. **DB query**:
   ```bash
   python3 scripts/backup_databases.py
   sqlite3 ~/dbs/<latest>.db "SELECT date(targetDate/1000,'unixepoch'), source, COUNT(*) FROM forecasts WHERE source='NWS' AND targetDate >= strftime('%s','now','-30 days')*1000 GROUP BY targetDate ORDER BY targetDate;"
   ```
   Confirms snapshots exist in DB for the dates that previously fell back to normals.
