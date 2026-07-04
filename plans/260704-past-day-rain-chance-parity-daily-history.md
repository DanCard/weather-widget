# Past-day rain chance parity: store displayed day/night chances in daily_history (renamed from daily_extremes)

## Context

The previous change (plans/260704-night-rain-chance-nws-period-vs-hourly-window.md) made
future/today rain-chance labels use the hourly 8am–8pm / 8pm–8am window max. The past-day
path was left on the raw NWS period fields (6am/6pm boundaries), so a night label can
*change when the day rolls into history*: tonight shows 14% (window max includes 7am), but
tomorrow — if no rain fell — the same night's fallback chance in history shows the stored
NWS period value of 9%.

Recomputing history from `hourly_forecasts` is wrong by design (those rows are hindcast-
replaced). The fix is archival: **snapshot the resolved displayed values while the day is
current, and replay them in history.**

User decisions:
- Store the values in the `daily_extremes` table, **renamed to `daily_history`** (with class
  renames) in this same change. The table already holds per-(date, source, location) history
  — actual extremes plus observed day/night precip *amounts* using the exact same 8am/8pm
  windows — so displayed forecast *chances* fit naturally and the old name no longer covers
  the contents.
- **Backfill** the last ~30 days from `hourly_forecast_history` (it stores hourly PoP
  snapshots on both platforms), so history is correct immediately, not just going forward.

No non-Kotlin code references the `daily_extremes` table name (checked scripts/, *.py, *.sh).

## Design

**What gets stored:** two nullable columns on the renamed table:
- `forecastDayPrecipChance` (INTEGER, 0–100) — resolved day chance (8am–8pm window max,
  falling back to period fields / whole-day PoP exactly as displayed)
- `forecastNightPrecipChance` (INTEGER) — resolved night chance (8pm–8am-next-day window max,
  falling back to the night period field)

Stored per (date, source, location) — the same key the table already uses — computed per
source from that source's own hourly + daily rows via the existing shared resolution logic.

**When written:** at the end of every successful data fetch, for dates D-1 and D (yesterday's
night window runs to 8am today, so it keeps updating until then; recomputes after that are
idempotent because past hourly rows are frozen). Last write before the window closes ==
"what the widget displayed" — exactly the archival semantics we want.

**Clobber hazard (the critical constraint):** both platforms write this table with full-row
`INSERT OR REPLACE` from the *actuals* path (Android `DailyExtremeDao.insertAll` REPLACE via
`ObservationRepository`; desktop `upsertDailyExtremes` INSERT OR REPLACE). Two writers + full-row
REPLACE = the observation writer nulls out the chance columns on its next pass unless merging is
explicit (same bug class as the "desktop history snapshot drops cloud" incident). Both writers
must coalesce fields they don't own from the existing row.

**Read path:** the `isPast` branch of `resolveDailyLabelPrecip` prefers the stored chances,
falling back to the period fields for rows that predate the feature (belt-and-suspenders
under the backfill).

## Changes

### 1. Rename (mechanical, both platforms)

- SQL: `daily_extremes` → `daily_history` (Android migration + desktop migration + the
  CREATE TABLE in `DesktopWeatherDatabase.kt`). Room's auto-named index
  (`index_daily_extremes_…`) must be dropped and recreated under the new auto-generated name
  — verify against the generated schema JSON / migration test rather than guessing.
- Kotlin: `DailyExtremeEntity`→`DailyHistoryEntity`, shared `DailyExtreme`→`DailyHistory`
  (`shared/.../data/model/DailyExtreme.kt`), `DailyExtremeDao`→`DailyHistoryDao`, mappers
  (`toDailyExtreme()`→`toDailyHistory()`), desktop DAO methods (`upsertDailyExtremes` etc.),
  and variable/param names where they'd become misleading. ~50 files, IDE-mechanical.
- Keep existing column names (`precipDayMm` etc.) — only the table and types rename.
- Log tags like `DAILY_EXTREME_OVERWRITE` in `ObservationRepository.kt:692`: rename to
  `DAILY_HISTORY_OVERWRITE` (grep for consumers first; app_logs queries by tag).

### 2. Schema + migrations

- Android: `WeatherDatabase.kt` version 50 → 51. One migration: `ALTER TABLE daily_extremes
  RENAME TO daily_history`, recreate index, `ALTER TABLE daily_history ADD COLUMN
  forecastDayPrecipChance INTEGER` / `forecastNightPrecipChance INTEGER`.
  Add a v50→51 case to `app/src/androidTest/.../WeatherDatabaseMigrationTest.kt`.
- Desktop: `DesktopWeatherDatabase.kt` `SCHEMA_VERSION` 5 → 6, same statements in `migrate()`;
  update the fresh-install CREATE TABLE too.

### 3. Shared chance resolution for storage

New small shared helper in `DailyRainLabels` (reusing `calculateDayNightPrecipProbabilities`
and the same fallback chain as the non-past branch of `resolveDailyLabelPrecip`), e.g.
`resolveStorableDayNightChance(sourceId, dailyRow, hourly, targetDate, zoneId)` → the
per-source displayed day/night chance. Single source of truth so the stored value can never
drift from what the live label shows.

### 4. Write hooks (merge, never plain REPLACE)

- Android `WeatherRepository` (post-fetch, *after* actuals aggregation so rows exist): for
  D-1 and D, per source with data, resolve chances and update all existing `daily_history`
  fragments in the `LocationMatch` box (mirroring the fragment-heal loop at
  `ObservationRepository.kt:683-699`) via read → `copy(forecastDayPrecipChance=…, …)` →
  `insertAll`. If no row exists yet for a (date, source), skip — next cycle catches it.
- Android `ObservationRepository` actuals writer: when building `new` rows, carry over
  existing chance values (`new.copy(forecastDayPrecipChance = existing.forecastDayPrecipChance, …)`)
  so its REPLACE preserves them. This is the clobber-regression fix and MUST have a test.
- Desktop `DesktopWeatherRepository` + `DesktopWeatherDao`: same two-sided merge. For
  `upsertDailyHistory`, either read-merge-in-Kotlin like Android or switch the SQL to
  `INSERT … ON CONFLICT(date,source,locationLat,locationLon) DO UPDATE SET` with
  `COALESCE(excluded.col, col)` for not-owned nullable columns — pick one pattern and use it
  for both writers on desktop.

### 5. Read path

- `resolveDailyLabelPrecip` (`shared/.../DailyRainLabels.kt`): add optional
  `storedDayPrecipChance: Int? = null`, `storedNightPrecipChance: Int? = null`; the `isPast`
  branch returns `ResolvedDailyPrecip(stored ?: daytimePeriod, storedNight ?: nightPeriod)`.
- Android: `DailyForecastIconResolver.resolveDailyLabelPrecip` wrapper gains the two params;
  `DailyViewLogic` passes them from the day's `DailyHistory` row (`actual` is already in
  scope at the call sites, e.g. the night-label block near line 550).
- Desktop: `DesktopDailyForecastModel` passes them from its `DailyHistory` row.

### 6. Backfill (one-time, both platforms)

- Guarded one-shot (Android: `SharedPreferencesUtil` flag — use the util, not raw prefs;
  desktop: flag file or config field), run in the background after first launch post-upgrade.
- For each (date, source, location) row in the last 30 days where both chance columns are
  null: compute day/night window max from `hourly_forecast_history` PoP (freshest snapshot
  per hour, same convention as the hindcast graph line), fall back to the daily row's period
  fields; write via the merge path from §4.
- Backfill logic lives in `:shared` (pure function over hourly-history rows + a thin
  per-platform driver), per the share-logic rule.

### 7. Tests

- Shared: `isPast` prefers stored chances / falls back when null; storable-resolution helper
  matches the live non-past resolution for identical inputs (anti-drift test); backfill
  window-max computation.
- Android: **clobber regression** — observation aggregation REPLACE preserves existing chance
  columns; migration test v50→51 (rename + columns + index); `DailyViewLogicTest` past-day
  label uses stored chance over period field (the 14%-stays-14% scenario).
- Desktop: DAO merge test in the `DesktopWeatherDaoTest` pattern; migration 5→6.

## Verification

1. `./gradlew :shared:test :desktop:test :app:testDebugUnitTest`; migration test via
   `./scripts/emulator-tests.sh -c com.weatherwidget.data.local.WeatherDatabaseMigrationTest`.
2. Emulator end-to-end: `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug`, trigger
   refresh, then `python3 scripts/backup_databases.py` and query `daily_history` — expect
   backfilled + fresh `forecastNightPrecipChance` values; for 2026-07-04/NWS expect 14
   (window max incl. 7am), not 9 (period field).
3. Widget check: navigate to yesterday — dry past night should show the stored window-max
   chance; screenshot via the PNG→JPG convert rule.
4. Desktop: `scripts/buildStart-desktop.sh`; confirm past-day labels match Android and
   genmon panel still reads the DB (it doesn't reference the renamed table, but sanity-check).
