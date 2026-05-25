# Plan: Hourly forecast-history storage (with snapshot-cadence cap + real migration)

## Context

Today the `hourly_forecasts` table keeps only the **latest** value per hour: its PK is
`(dateTime, source, locationLat, locationLon)` with `OnConflictStrategy.REPLACE`
(`HourlyForecastEntity.kt:8`, `HourlyForecastDao.kt:43`). So when a forecast for a given hour is
re-fetched, the prior prediction is overwritten — the *as-predicted* hourly cloud/rain/temp is
unrecoverable. (This is exactly why the today snapshot bar had to reuse today's cloud ratio.)

The daily forecast history **is** preserved: `ForecastEntity` keys on
`(targetDate, forecastDate, …, source, fetchedAt)`, so every changed fetch appends a row. But it
accumulates a row per *changed* fetch, unbounded by cadence.

**Goal (this change):** persist an *hourly* forecast history so a future feature can reconstruct
what was forecast for an hour as of an earlier time — mirroring the daily-history idea. Plus cap
how often history snapshots are recorded so neither table grows without bound.

### Confirmed decisions
- **Storage only.** Add the table/DAO/write path/retention/migration. Nothing reads it yet.
- **Snapshot-cadence cap:** record a new forecast-history snapshot at most once every **4h for the
  primary API**, **8h for non-primary**. Each snapshot keeps **full hourly detail**.
- **Applies to both** the new hourly history **and** the existing daily `ForecastEntity` history.
  The latest row must still update every fetch so current display stays fresh.
- **Real Room migration — do NOT drop the database.** (The builder currently relies solely on
  `fallbackToDestructiveMigration(dropAllTables = true)`, `WeatherDatabase.kt:85`, which has been
  wiping cached data on every version bump — see the `DB_DESTRUCTIVE_MIGRATION` log. CLAUDE.md says
  cached data is valuable, so we add an explicit migration and keep the fallback only as a backstop.)
- **Primary API = first source** in the global visible-sources order (`KEY_VISIBLE_SOURCES_ORDER`,
  default `NWS,TOMORROW_IO,OPEN_METEO,SILURIAN`), `WidgetStateManager.kt:70/191`.

## Design

### 1. Snapshot-cadence policy helper
New small object (e.g. `data/repository/ForecastHistoryPolicy.kt`):
- `primarySourceId(): String` — first entry of the **global** visible-sources order. Add a
  widget-id-independent accessor to `WidgetStateManager` (read `KEY_VISIBLE_SOURCES_ORDER` directly,
  reuse the existing parse at `WidgetStateManager.kt:191`) if one doesn't exist.
- `bucketMs(sourceId): Long` → `4h` if `sourceId == primarySourceId()` else `8h`.
- `snapshotBucket(nowMs, sourceId): Long = (nowMs / bucketMs(sourceId)) * bucketMs(sourceId)`
  (floor to the bucket boundary).

The cadence cap is enforced **structurally** by putting the bucket value in the primary key:
fetches in the same bucket collapse via `REPLACE`; a new bucket creates a new history row.

### 2. New table `hourly_forecast_history` + DAO
- `HourlyForecastHistoryEntity` — same payload fields as `HourlyForecastEntity`
  (`dateTime, locationLat, locationLon, temperature, condition, source, precipProbability,
  cloudCover, precipAmountMm, fetchedAt`) **plus** `snapshotBucket: Long`.
  - PK: `(dateTime, source, locationLat, locationLon, snapshotBucket)`.
  - Index on `(locationLat, locationLon, source, snapshotBucket)`.
- `HourlyForecastHistoryDao`: `@Insert(REPLACE) insertAll(...)`; a range query
  `(lat, lon, source, dateTime BETWEEN …, snapshotBucket = …)`; `deleteOld(cutoff)` on `fetchedAt`.

### 3. Hourly write path
In `ForecastRepository.saveHourlyEntities` (`ForecastRepository.kt:681`), after the existing save to
the live `hourly_forecasts` table, also write history: map the **full** future hourly set to
`HourlyForecastHistoryEntity` with `snapshotBucket = ForecastHistoryPolicy.snapshotBucket(now, source)`
and `insertAll`. Same-bucket fetches REPLACE (cadence cap); each snapshot keeps every future hour.
The live table is **unchanged** (hot path / temperature interpolation keep full freshness).

### 4. Daily `ForecastEntity` history throttle
The daily table conflates "current" (latest row) and "history" (older `fetchedAt` rows). To cap
history cadence **without** staling current display, align the history identity to the bucket:
- When building the changed daily forecast rows for save (`ForecastRepository.kt` ~`513`/`546`/`585`),
  set `fetchedAt = ForecastHistoryPolicy.snapshotBucket(now, source)` while keeping
  `batchFetchedAt = actual now`.
- Effect: within a bucket the PK `(targetDate, forecastDate, …, source, fetchedAt=bucketStart)` is
  identical, so `REPLACE` overwrites the row with the freshest values (current display, which orders
  by `batchFetchedAt DESC`, stays fresh); a new bucket appends a new history row. History rows are
  therefore spaced ≥ 4h/8h.
- **Verify (flag):** consumers of `ForecastEntity.fetchedAt` still behave — the today snapshot
  selection (`DailyViewLogic.kt:408-411`, "fetched before now−24h"), `AccuracyCalculator`, and any
  staleness check. forecastDate (the 1-day-ahead marker) is unchanged; only `fetchedAt` is
  bucket-aligned (≤8h earlier than actual), which these should tolerate. Confirm during impl.

### 5. DB registration + real migration (44 → 45)
- Add `HourlyForecastHistoryEntity` to `@Database(entities=…)`, add
  `abstract fun hourlyForecastHistoryDao()`, bump `version = 44` → `45` (`WeatherDatabase.kt:11-12`).
- Add `val MIGRATION_44_45 = object : Migration(44, 45) { override fun migrate(db) { db.execSQL(
  "CREATE TABLE IF NOT EXISTS hourly_forecast_history (…)") ; db.execSQL("CREATE INDEX …") } }`.
  Copy the exact `CREATE TABLE`/`CREATE INDEX` SQL from Room's generated schema JSON
  (`app/schemas/…/45.json`, produced because `exportSchema = true`) so it matches byte-for-byte.
- Register `.addMigrations(MIGRATION_44_45)` on the builder **before**
  `.fallbackToDestructiveMigration(...)` (`WeatherDatabase.kt:84-85`); keep the fallback as backstop.
  Existing user data (forecasts, observations, live hourly) is **preserved** across this upgrade.

### 6. Retention
Alongside the existing 1-month cleanup (`ForecastRepository.kt:833-834`), add
`hourlyForecastHistoryDao.deleteOld(oneMonthAgoTimestamp)`.

## Files to modify / add
- `app/src/main/java/com/weatherwidget/data/local/HourlyForecastHistoryEntity.kt` (new)
- `app/src/main/java/com/weatherwidget/data/local/HourlyForecastHistoryDao.kt` (new)
- `app/src/main/java/com/weatherwidget/data/repository/ForecastHistoryPolicy.kt` (new helper)
- `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt` (entity+dao+version+migration)
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt` (hourly history write,
  daily `fetchedAt` bucket-align, retention)
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt` (global `primarySourceId()` accessor if missing)
- `app/schemas/com.weatherwidget.data.local.WeatherDatabase/45.json` (generated on build)

## Verification
1. **Unit tests** (`./gradlew testDebugUnitTest`): `ForecastHistoryPolicy` — primary→4h, non-primary→8h,
   `snapshotBucket` floors correctly; two saves within one bucket coalesce, across buckets create two.
2. **Migration test** (instrumented, Room `MigrationTestHelper` using exported schemas; run via
   `./scripts/emulator-tests.sh`, never `connectedDebugAndroidTest`): create v44 DB with sample
   forecast/observation rows → run `MIGRATION_44_45` → assert it opens, the old rows **survive**, and
   `hourly_forecast_history` exists with the expected columns.
3. **Repository test**: save the same source's hourly twice within a bucket → 1 history snapshot;
   advance the clock past the bucket → 2. Save daily forecasts twice in a bucket → latest values win,
   one history row; new bucket → new row. Primary source uses 4h, non-primary 8h.
4. **On-device** (`./gradlew installDebug`): trigger fetches; pull DB
   (`python3 scripts/backup_databases.py`) and query `hourly_forecast_history` to confirm rows
   accumulate at the expected cadence and that pre-upgrade data was retained (no `DB_DESTRUCTIVE_MIGRATION`
   log entry on upgrade).

## Risks / flags
- **Storage volume:** full hourly detail per snapshot × 6 snapshots/day (primary) over 1 month is
  sizable but bounded by retention; revisit retention window if the table grows large.
- **`ForecastEntity.fetchedAt` semantics** change to bucket-aligned — verify the consumers listed in
  §4 before finalizing; if any depend on exact fetch time, fall back to a per-source "last history
  write" guard instead of bucket-aligning `fetchedAt`.
