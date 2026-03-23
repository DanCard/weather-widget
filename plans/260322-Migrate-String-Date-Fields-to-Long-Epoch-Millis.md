# Plan: Migrate String Date Fields to Long (Epoch Millis)

## Context

Several Room database entities store calendar dates as ISO-8601 strings (`"YYYY-MM-DD"`) rather than Long epoch milliseconds. This is inconsistent with newer entities (`ObservationEntity`, `HourlyForecastEntity`, `AppLogEntity`) which all use Long correctly. String dates work by accident (ISO-8601 sorts lexicographically) but are larger on disk, slower to compare, and fragile if format ever drifts. Migration 39→40 already set the precedent by converting `hourly_forecasts.dateTime` TEXT→INTEGER using SQLite's `strftime` — we follow the same pattern here.

## Convention

All date-only fields (`YYYY-MM-DD`) → **UTC midnight epoch millis**:
```kotlin
LocalDate.now().toEpochDay() * 86400_000L  // UTC midnight
```

`periodStartTime`/`periodEndTime` (ZonedDateTime ISO-8601) → epoch millis via `ZonedDateTime.parse(...).toInstant().toEpochMilli()`. Since these are nullable and re-fetched from NWS, **null them out in the SQL migration** to avoid SQLite timezone-offset parsing issues; the next NWS fetch repopulates them correctly.

## Fields to Migrate

| Entity | Field | Old Type | New Type |
|--------|-------|----------|----------|
| `ApiUsageEntity` | `date` | `String` | `Long` |
| `ForecastEntity` | `targetDate` | `String` | `Long` |
| `ForecastEntity` | `forecastDate` | `String` | `Long` |
| `ForecastEntity` | `periodStartTime` | `String?` | `Long?` |
| `ForecastEntity` | `periodEndTime` | `String?` | `Long?` |
| `DailyExtremeEntity` | `date` | `String` | `Long` |

## DB Migration (40 → 41)

Follow exact same pattern as MIGRATION_39_40 in `WeatherDatabase.kt`:

### `api_usage_stats` (PK: `date, apiSource`)
```sql
CREATE TABLE api_usage_stats_new (date INTEGER NOT NULL, apiSource TEXT NOT NULL, callCount INTEGER NOT NULL, PRIMARY KEY(date, apiSource));
INSERT INTO api_usage_stats_new SELECT strftime('%s', date) * 1000, apiSource, callCount FROM api_usage_stats;
DROP TABLE api_usage_stats;
ALTER TABLE api_usage_stats_new RENAME TO api_usage_stats;
```

### `forecasts` (PK includes `targetDate`, `forecastDate`)
```sql
CREATE TABLE forecasts_new (...same schema but targetDate INTEGER, forecastDate INTEGER, periodStartTime INTEGER, periodEndTime INTEGER...);
INSERT INTO forecasts_new SELECT strftime('%s', targetDate)*1000, strftime('%s', forecastDate)*1000, NULL, NULL, <all other cols> FROM forecasts;
DROP TABLE forecasts;
ALTER TABLE forecasts_new RENAME TO forecasts;
```

### `daily_extremes` (PK includes `date`)
```sql
CREATE TABLE daily_extremes_new (...same schema but date INTEGER...);
INSERT INTO daily_extremes_new SELECT strftime('%s', date)*1000, <all other cols> FROM daily_extremes;
DROP TABLE daily_extremes;
ALTER TABLE daily_extremes_new RENAME TO daily_extremes;
```

Register `MIGRATION_40_41` in `.addMigrations(...)`.

## Entity Changes

### `ApiUsageEntity.kt`
- `val date: String` → `val date: Long`
- DAO method signatures: `date: String` → `date: Long`

### `ForecastEntity.kt`
- `val targetDate: String` → `val targetDate: Long`
- `val forecastDate: String` → `val forecastDate: Long`
- `val periodStartTime: String?` → `val periodStartTime: Long?`
- `val periodEndTime: String?` → `val periodEndTime: Long?`

### `DailyExtremeEntity.kt`
- `val date: String` → `val date: Long`

## DAO Changes

### `ForecastDao.kt`
- All query method parameters: `startDate: String`, `endDate: String`, `targetDate: String`, `forecastDate: String` → `Long`
- SQL WHERE clauses already use `>=`/`<=`/`=` — work correctly with Long

### `DailyExtremeDao.kt`
- `getExtremesInRange(startDate: String, endDate: String, ...)` → `Long`

## Call Site Changes (~12 files)

**Date creation** (replace `LocalDate.format(ISO_LOCAL_DATE)` with epoch millis):

- `AppModule.kt` line 72: `LocalDate.now().format(...)` → `LocalDate.now().toEpochDay() * 86400_000L`
- `ForecastRepository.kt` multiple sites: same conversion for `day.date`, `LocalDate.now().toString()`, `todayDateString`, `cursorDate.toString()`
  - NWS `periodStartTime`/`periodEndTime`: `ZonedDateTime.parse(...).toInstant().toEpochMilli()`
- `ObservationResolver.kt` lines 167-219: date string construction → `LocalDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond() * 1000`
- `ObservationRepository.kt` lines 159, 205, 331, 347, 362: date string params → Long

**Date reading** (replace `LocalDate.parse(...)` with epoch conversion):

- `ForecastRepository.kt` line 554: `LocalDate.parse(forecast.targetDate)` → `LocalDate.ofEpochDay(forecast.targetDate / 86400_000L)`
- `ForecastRepository.kt` line 560: `ZonedDateTime.parse(forecast.periodEndTime)` → `Instant.ofEpochMilli(forecast.periodEndTime!!).atZone(ZoneId.systemDefault())`
- `ForecastRepository.kt` lines 571-577: string comparison `forecast.targetDate == todayDateString` → `forecast.targetDate == LocalDate.now().toEpochDay() * 86400_000L`
- `AccuracyCalculator.kt` line 115: `LocalDate.parse(actual.date)` → `LocalDate.ofEpochDay(actual.date / 86400_000L)`
- `AccuracyCalculator.kt` line 121-122: string comparisons → Long comparisons
- `ForecastHistoryActivity.kt` line 344: `LocalDate.parse(targetDate)` → `LocalDate.ofEpochDay(targetDate / 86400_000L)`
- `ForecastHistoryActivity.kt` range queries: build Long params from `LocalDate.toEpochDay() * 86400_000L`
- `WidgetIntentRouter.kt` line 517-519: string range params → Long
- `WeatherWidgetProvider.kt` line 170: string range params → Long
- `WeatherWidgetWorker.kt` lines 226-230: string params → Long
- `ObservationResolver.kt` lines 240-265: `extremesToDailyActuals()` — date field is now Long, convert back to LocalDate with `LocalDate.ofEpochDay(it.date / 86400_000L)` where needed for display

## Critical Files

- `app/src/main/java/com/weatherwidget/data/local/ApiUsageEntity.kt`
- `app/src/main/java/com/weatherwidget/data/local/ForecastEntity.kt`
- `app/src/main/java/com/weatherwidget/data/local/DailyExtremeEntity.kt`
- `app/src/main/java/com/weatherwidget/data/local/ForecastDao.kt`
- `app/src/main/java/com/weatherwidget/data/local/DailyExtremeDao.kt`
- `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt` (migration + version bump to 41)
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`
- `app/src/main/java/com/weatherwidget/stats/AccuracyCalculator.kt`
- `app/src/main/java/com/weatherwidget/di/AppModule.kt`
- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`

## Verification

1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest` — unit tests must pass
2. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug` — must build and install
3. Install over existing app (do NOT clear data) — Room migration runs automatically; verify widget still shows data
4. `./scripts/run-emulator-tests.sh` — instrumented tests on emulator
5. Pull DB after install and verify date columns are INTEGER type with sensible epoch values:
   ```bash
   python3 scripts/backup_databases.py
   sqlite3 <db_file> "SELECT date, datetime(date/1000, 'unixepoch') FROM daily_extremes LIMIT 5;"
   ```
