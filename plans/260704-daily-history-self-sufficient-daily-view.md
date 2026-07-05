# Plan: Make daily_history self-sufficient for the daily view

## Overview

Today a *past* day in the daily bar view needs three tables:

1. `daily_history` — actual high/low, condition, measured precip (total/day/night), frozen
   forecast rain chances. Already self-contained.
2. `forecasts` (snapshots) — forecast high/low for the yellow accuracy-overlay bar, plus
   forecast precip amount fallback.
3. `hourly_forecast_history` / observations — measured noon cloud % for the day icon and
   the bar's cloud split.

Goal: denormalize the remaining display inputs into `daily_history` so that shortening the
retention of `forecasts` and hourly data no longer breaks past-day daily rendering. This is
the same "freeze what was displayed while the day was live" pattern the
`forecastDayPrecipChance`/`forecastNightPrecipChance` columns established — extend it, don't
invent a new mechanism.

**New nullable columns** on `daily_history` (both platforms):

| Column | Type | Source when frozen |
|---|---|---|
| `forecastHighTemp` | REAL | Selected overlay snapshot's high (what the yellow bar showed) |
| `forecastLowTemp` | REAL | Selected overlay snapshot's low |
| `forecastPrecipAmountMm` | REAL | Selected snapshot's precip amount |
| `noonCloudPercent` | INTEGER | Resolved measured noon cloud % (`DailyNoonCloudCover`) |

Null = "row predates the feature / value never resolvable" and readers fall back to today's
live derivation — the exact convention the chance columns use.

## Scope boundary (important)

This makes the **daily bar view** archival. It does NOT cover:

- **Day-tap hourly zoom** — still needs `hourly_forecast_history`.
- **Forecast-evolution graph (📈)** — needs the full snapshot *series*, not one frozen value.

So retention stays a per-table policy. Actually shortening the retention windows of
`forecasts`/`hourly_forecast_history` is a **follow-up task**, deferred until ≥30 days of
frozen rows have accumulated; this plan only adds the freezing.

## Schema changes

### Android (`app`)
- `WeatherDatabase.kt`: version 51 → 52; `MIGRATION_51_52` with four
  `ALTER TABLE daily_history ADD COLUMN` statements (mirror `MIGRATION_50_51` at
  `WeatherDatabase.kt:172`). Bump the `@Database` version in the same commit as the entity
  change (schema-JSON export ordering lesson).
- `DailyHistoryEntity.kt`: add the four fields + both mapper functions
  (`toDailyHistory()` / `toEntity()`).

### Shared model
- `shared/.../data/model/DailyHistory.kt`: add the four fields with a comment matching the
  frozen-chance comment style (lines 22–26).

### Desktop (`shared` JDBC layer)
- `DesktopWeatherDatabase.kt`: `SCHEMA_VERSION` 6 → 7; `addColumnIfMissing` × 4 in
  `migrate()` (mirror lines 228–229); add columns to the `CREATE TABLE IF NOT EXISTS
  daily_history` block (line 122).
- `DesktopWeatherDao.kt`: extend the explicit insert column list + `setNullable*` binds
  (lines 226, 242–243) and the row-read mapping (lines 920–921).

## Writers

### 1. Live freeze — Android
Extend the existing chance-snapshot writer in
`ForecastRepository.kt:260-313` (the `dayWindowOpen`/`nightWindowOpen` loop):

- **forecastHighTemp/LowTemp/PrecipAmountMm**: while the day's freeze window is open,
  overwrite with the *currently selected* overlay snapshot values (same selection the
  renderer uses — `DailySnapshotSelector.selectPriorDaySnapshot` semantics, see
  `DailyViewLogic.kt:385`); stop writing once closed. Freeze window: **local midnight at
  end of the target day** (past-day overlay = "most recent snapshot while the day was
  live", per the desktop comment at `DesktopDailyForecastModel.kt:185`).
- **noonCloudPercent**: overwrite with
  `DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(...)` while open. Measured obs
  can arrive late (Open-Meteo `past_days` backfill), so use the **next-day 8am close**
  (reuse the existing `nightWindowOpen` boundary) rather than midnight. Only write non-null
  resolutions — never clobber a frozen value with null.

### 2. Live freeze — Desktop
Mirror in `DesktopWeatherRepository.kt` wherever the chance columns are written today
(locate the desktop twin of the ForecastRepository writer). Same windows, same shared
helpers — any new gating math goes in `:shared` (e.g., next to `DailyRainLabels`), not
duplicated per platform.

### 3. One-time backfill (both platforms)
Mirror `backfillForecastChanceSnapshotsIfNeeded` (`ForecastRepository.kt:329-386`):

- `forecastHighTemp/Low/PrecipAmountMm` from still-retained `forecasts` snapshots via the
  same past-day selection.
- `noonCloudPercent` from `hourly_forecast_history`/observations via the shared resolver.
- Best-effort: unfillable days stay null (no regression). Pref-gated one-shot
  (`PREF_*_BACKFILL_DONE`), desktop equivalent via its config/prefs mechanism.

## REPLACE carry-over audit (highest-risk step)

`insertAll` uses `OnConflictStrategy.REPLACE`; a writer that rebuilds a row from raw data
will silently null the frozen columns — this exact bug already happened once for the chance
columns (see the carry-over comment at `ObservationRepository.kt:697-707`). Every
`daily_history` writer must carry frozen fields forward:

- `ObservationRepository.kt:700-707` — extend the `new.copy(...)` carry-over with the four
  new columns.
- `ForecastRepository.kt:298-309` — uses `existing.copy(...)`, inherently safe; confirm.
- `ObservationResolver.computeDailyHistory` (`ObservationResolver.kt:90-118`) — produces
  fresh rows; verify its consumers merge rather than blind-insert over existing rows.
- Desktop: audit every `DesktopWeatherDao` daily_history insert path the same way.
- Add a regression test per platform: recompute-REPLACE preserves all six frozen columns
  (chances + new four).

## Readers (prefer frozen, fall back to live derivation)

For **past days only** (today keeps live derivation since its window is open):

- Android `DailyViewLogic.prepareGraphDays` (`DailyViewLogic.kt:349-390`): prefer
  `daily_history.forecastHighTemp/LowTemp` for the overlay; fall back to snapshot selection
  when null. Same for `forecastPrecipAmountMm`.
- Android noon cloud (`DailyViewLogic.kt:465` + `DailyForecastIconResolver.kt:80`): prefer
  frozen `noonCloudPercent`; fall back to `resolveMeasuredNoonCloudCoverPercent`.
- Desktop `DesktopDailyForecastModel.kt`: same preference at the snapshot selection
  (lines 185–213) and noon-cloud resolution (lines 278–290).
- Log the chosen source per day at `Log.v` (frozen vs live-derived vs null) — permanent
  decision-chain logging, per project convention.

## Testing regimen

No mocking framework — pure-function extraction where gating/selection logic is new.

| Scenario | Expected |
|---|---|
| Day window open, snapshot updates | frozen forecast high/low overwritten with latest selected snapshot |
| Day window closed (past midnight) | writer leaves forecast high/low untouched |
| Noon cloud resolves after midnight but before next-day 8am | still written (late-obs window) |
| Noon cloud resolves null | existing frozen value NOT clobbered |
| Obs recompute REPLACE on row with frozen values | all six frozen columns carried over |
| Reader: past day with frozen values | overlay/icon use frozen, ignore live tables |
| Reader: past day with nulls (pre-feature row) | falls back to snapshot/hourly derivation |
| Reader: today | live derivation regardless of frozen values |
| Backfill: snapshots retained | fills frozen columns once, pref-gated |
| Backfill: nothing retained | row stays null, no crash, flag still set |
| Android migration 51→52 / desktop v6→v7 | columns added, existing rows null |

Suites: `app` unit tests (Robolectric where needed), `:shared:test`, `:desktop:test`.

## Execution & verification protocol

1. **Baseline**: `./gradlew :shared:test :desktop:test testDebugUnitTest` clean.
2. Schema + entity + mappers (both platforms), migrations.
3. Writers (live freeze + backfill) with tests.
4. REPLACE carry-over audit + regression tests.
5. Readers with preference fallback + tests.
6. Rerun full suites.
7. **Desktop live check**: `scripts/buildStart-desktop.sh`; force a full refresh (age the
   newest REFRESH row >60 min, restart); `sqlite3 ~/.local/share/weather-widget/weather.db`
   — confirm today's row accumulates frozen values and yesterday's row froze; confirm
   past-day overlay renders identically before/after.
8. **Android live check**: `./gradlew installDebug`; `python3 scripts/backup_databases.py`
   and query `daily_history`; screenshot the daily view (convert PNG→JPG before reading)
   and compare past-day overlay/icon to pre-change.

## Follow-up (separate task, not in this plan)

Shorten `forecasts` / `hourly_forecast_history` retention once frozen columns cover the
full 30-day navigation window — requires its own plan weighing the day-tap zoom and
forecast-evolution views, which keep needing those tables.
