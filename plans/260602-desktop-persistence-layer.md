# Tier 1 — Desktop Persistence Layer (`:shared` SQLite)

> Foundational parity work. Converts the desktop app from **stateless** to **stateful**.
> Unblocks: genmon Variant A (`260602-genmon-panel-temp-text.md`), forecast accuracy tracking,
> history navigation, yesterday actual-vs-forecast — i.e. most of the Android widget's identity.

## Context / Problem

The desktop app fetches NWS/Open-Meteo/etc. over HTTP into an in-memory `ForecastResult`
(`DesktopWeatherService.kt`) and persists nothing but `~/.config/weather-widget/config.json`.
Consequences:
- Close the app → all weather data is gone; every launch refetches from scratch (empty popup until
  the first network round-trip completes).
- No history, so no accuracy tracking, no "yesterday actual vs forecast", no 30-day navigation.
- The deferred genmon script has no DB to read.

The Android app solves all of this with a Room DB in `app/src/main/java/com/weatherwidget/data/local/`.
That layer is Android/Room-coupled and cannot be reused directly. Desktop needs its own DB in the
plain-JVM `:shared` module.

## Goal

A small, self-contained persistence layer in `:shared` that:
1. Stores daily forecasts, hourly forecasts, observations, daily extremes, and hourly-forecast
   history in a SQLite file on disk.
2. Survives restarts and serves cached data instantly on launch.
3. Is read-through cached behind a repository that wraps the existing network service.
4. Produces a plain SQLite file other tools (the genmon Python script) can read.

### Non-goals (later tiers — but create their tables now)

- Accuracy calculation / display modes (Tier 2). 
- History-navigation UI (Tier 2).
- Current-temp interpolation, multi-station fallback (Tier 3).
- Any change to the Android Room DB.

## Technology decision

**Use `org.xerial:sqlite-jdbc` with a thin hand-written DAO.** Rationale in the insight above:
plain SQLite file (genmon-readable via stdlib `sqlite3`), no codegen/plugin, `:shared` stays a simple
`kotlin-jvm` module. **Alternative considered:** SQLDelight (typesafe generated queries, KMP-ready) —
rejected for Tier 1 to avoid the Gradle plugin + `.sq` codegen overhead; revisit if hand-written SQL
becomes unwieldy.

Catalog + module wiring:
- Add to `gradle/libs.versions.toml`: `sqlite-jdbc = { group = "org.xerial", name = "sqlite-jdbc", version = "<latest 3.49.x>" }`.
- `shared/build.gradle.kts`: `api(libs.sqlite.jdbc)` (api so `:desktop` sees the driver). Keep it
  optional-at-runtime conceptually — Android won't use this code path.

## Schema (mirror Android column names & composite PKs)

Create-if-not-exists DDL for these tables (fields/types/PKs copied from the Android entities so ported
logic matches). All temps °F, all times epoch-millis:

| Table | PK | Mirrors |
|-------|----|---------|
| `forecasts` | (targetDate, forecastDate, locationLat, locationLon, source, fetchedAt) | `ForecastEntity` — daily forecasts **and** 1-day-ahead snapshots for accuracy |
| `hourly_forecasts` | (dateTime, source, locationLat, locationLon) | `HourlyForecastEntity` — live hourly (REPLACE-overwritten) |
| `hourly_forecast_history` | (dateTime, source, locationLat, locationLon, snapshotBucket) | `HourlyForecastHistoryEntity` — preserves original predictions (hindcast fix) |
| `observations` | (stationId, timestamp) | `ObservationEntity` — actual obs |
| `daily_extremes` | (date, source, locationLat, locationLon) | `DailyExtremeEntity` — computed actual highs/lows |

Skip Android-only tables (`app_logs`, `api_usage`, `climate_normals`) for now. Mirror nullable columns
exactly (precip*, cloudCover, period*, min/maxTempLast24h) so later tiers don't need migrations.

## Files / module layout (new, under `:shared`)

`shared/src/main/kotlin/com/weatherwidget/data/local/`:
- `WeatherDatabase.kt` — opens/creates the SQLite file via JDBC, runs DDL, manages
  `PRAGMA user_version` (start v1; provide a `migrate(old → new)` hook for the future), exposes a
  `Connection`/helper. Single shared connection guarded for thread-safety (fetches run on
  `Dispatchers.IO`).
- `WeatherDao.kt` (or split per table) — hand-written SQL:
  - upserts: `INSERT OR REPLACE` for each table (batch per fetch).
  - reads: latest hourly/obs for current temp; daily range by location; daily_extremes by date range.
  - `cleanup(beforeEpochMs)` — delete rows older than 1 month (retention parity).
- `DesktopDbPaths.kt` — DB location resolver: `XDG_DATA_HOME` else `~/.local/share`, →
  `weather-widget/weather.db` (parallels `DesktopConfigStore.defaultConfigPath()` which uses
  `~/.config`).

## Repository integration

Introduce `desktop/.../DesktopWeatherRepository.kt` mirroring Android's `WeatherRepository` pattern,
wrapping network + DB:
- `loadCached(config): ForecastResult` — build a `ForecastResult` from the DB (current temp from
  latest obs/hourly, `daily` from `forecasts`/`daily_extremes`, `hourly` from `hourly_forecasts`).
  Returned immediately on launch so the popup/tray are never empty.
- `refresh(config): ForecastResult` — call `DesktopWeatherService.fetchForecast()`, **persist** the
  result (upsert daily→`forecasts`, hourly→`hourly_forecasts` + append to `hourly_forecast_history`
  per snapshot bucket, current obs→`observations`), run `cleanup`, then return the rebuilt cached view.
- Wire into `Main.kt`: the existing `LaunchedEffect` refresh loop calls `repository.refresh(...)`;
  add a one-shot `loadCached(...)` on startup before the first fetch. `forecast` state now flows from
  the repository.

Keep `DesktopWeatherService` as the pure network layer (unchanged); the repository owns persistence.

## Testing (no mocking framework — use real sqlite)

Aligns with the project's pure-extraction testing strategy. In `shared/src/test`:
- DAO round-trip tests against a **temp-file SQLite DB** (real driver): insert → query → assert;
  `INSERT OR REPLACE` overwrite semantics; `cleanup()` deletes only old rows; `user_version` set.
- `DesktopWeatherRepository` test: stub the network with a canned `ForecastResult`, assert it lands
  in the DB and that `loadCached` reconstructs an equivalent `ForecastResult`.

## Verification

1. `./gradlew :shared:test :desktop:test` green.
2. `./gradlew :desktop:run`; confirm `~/.local/share/weather-widget/weather.db` is created.
3. Inspect with `sqlite3 ~/.local/share/weather-widget/weather.db ".tables"` and
   `"SELECT * FROM hourly_forecasts LIMIT 5;"` — real rows present.
4. Kill the app, relaunch with **network off** → popup/tray show last cached data immediately (no
   empty state). Re-enable network → rows refresh, old rows (>1mo) pruned.
5. Confirm the genmon Variant A path is now feasible: a `sqlite3` query returns the latest NWS temp.

## Follow-ups unblocked

- genmon Variant A (read DB instead of hitting NWS) → `260602-genmon-panel-temp-text.md`.
- Tier 2: port `AccuracyCalculator`/`RainAccuracyCalculator` to `:shared`; save daily snapshots;
  history-navigation UI in the popup.
- Tier 3: `TemperatureInterpolator`, multi-station observation fallback.
