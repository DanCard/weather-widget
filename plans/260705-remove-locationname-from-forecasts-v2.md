# Remove `forecasts.locationName` column (Android Room v54→55, Desktop v9→10)

## Context

`forecasts.locationName` is a write-only vestigial column. Tracing every read in main code shows the value is only ever copied onto synthetic GENERIC_GAP gap rows in the same table — nothing displays it, logs it, or joins on it. The desktop DAO already hardcodes `''` on write and no desktop SELECT reads it. Removing it deletes the column on both platforms plus the parameter plumbing that exists solely to populate it (notably the awkward "derive name from firstOrNull non-GENERIC_GAP row" dance in ClimateGapFiller and its callers).

**Scope guard:** `locationName` as a *stored column* exists only in the `forecasts` table. The `locationName` *parameter* on `CurrentTempRepository.refreshCurrentTemperature` / `WeatherRepository.refreshCurrentTemperature` **stays** — it feeds `recordHistoricalPoi()` (POI recording, unrelated to this table). `WeatherWidgetWorker.getLocationName()` also stays (still used by those calls at WeatherWidgetWorker.kt:527/620).

One atomic commit is fine (column is unread); order below keeps modules independently buildable.

## Phase 1 — Desktop/shared (SCHEMA_VERSION 9 → 10)

**`shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDatabase.kt`**
- Delete `locationName TEXT NOT NULL DEFAULT '',` from the forecasts CREATE TABLE (line 36).
- Bump `SCHEMA_VERSION` 9 → 10 (line ~316).
- Add migration block after `if (from < 9)`, mirroring its PRAGMA-guard idiom. sqlite-jdbc is 3.53.1.0 (bundled SQLite ≥ 3.35) and the column is not in the PK or either index, so `DROP COLUMN` is legal:
  ```kotlin
  if (from < 10) {
      // PRAGMA table_info(forecasts) loop; if a "locationName" column exists:
      stmt.execute("ALTER TABLE forecasts DROP COLUMN locationName")
  }
  ```

**`shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt` — `upsertForecasts` (lines 96–143)**
- Remove `locationName` from INSERT column list (line 102); 17 → 16 `?` placeholders.
- Delete `stmt.setString(5, "") // locationName` (line 120) and **shift all subsequent bind indices down by one** (highTemp 6→5 … fetchedAt 17→16). This is runtime-only breakage — rely on shared/desktop tests, not the compiler.

## Phase 2 — Android entity + Room migration (v54 → 55)

Per project rule (memory: room-schema-export-rename-order): entity edit and `@Database` version bump in the **same change**.

**`app/src/main/java/com/weatherwidget/data/local/ForecastEntity.kt`** — delete line 19 (`val locationName: String = ""`). Not in `primaryKeys`/`indices`; no annotation changes.

**`app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`**
- `version = 55` (line 14).
- Add `MIGRATION_54_55` after `MIGRATION_53_54` (~line 217) and register in `.addMigrations(...)` (line 281).
- minSdk 26 → framework SQLite predates `DROP COLUMN` (needs 3.35/API 34+), so **recreate the table**:
  1. `CREATE TABLE forecasts_new (...)` — 54.json's forecasts `createSql` minus `` `locationName` TEXT NOT NULL, `` (verified: same 18 remaining columns, PK `(targetDate, dateOfPrediction, locationLat, locationLon, source, fetchedAt)`).
  2. `INSERT INTO forecasts_new (18 cols) SELECT (same 18 cols) FROM forecasts` — PK unchanged, no collisions possible, plain INSERT.
  3. `DROP TABLE forecasts`; `ALTER TABLE forecasts_new RENAME TO forecasts`.
  4. Recreate both indices with Room's exact names (verified in 54.json):
     `index_forecasts_locationLat_locationLon` and
     `index_forecasts_targetDate_source_locationLat_locationLon_batchFetchedAt`.
- After generating 55.json (Phase 5), diff its `createSql` against the migration and copy Room's strings verbatim if they differ at all.

## Phase 3 — Plumbing unwind (app main code)

**`ClimateGapFiller.kt`**
- `gapRows(...)`: drop `locationName: String` param (line 34) and the entity write (line 47).
- `appendGaps(...)`: signature unchanged; delete the derivation at lines 72–74 and the doc sentence at line 62.
- `appendGapsToSnapshots(...)`: drop `locationName: String` param (line 84).

**Gap-filler callers** — drop the locationName argument at:
- `ForecastRepository.kt:1206` (also delete the `val locationName = liveSourceData.firstOrNull()...` line)
- `WeatherWidgetProvider.kt:307`
- `WidgetIntentRouter.kt:755` (delete the firstOrNull-non-GENERIC_GAP arg line)
- `WeatherWidgetWorker.kt:356` (drops the `getLocationName(lat, lon)` arg)

**Fetch-path params that become dead** (their only purpose was the entity write) — remove `locationName` param from:
- `NwsForecastMapper.fetchFromNws` (~line 47) + entity write (~line 149)
- `ForecastRepository.mapDailyForecast` (line 775) + entity write (line 805) — single mapper shared by all non-NWS providers
- `ForecastRepository.fetchFromNws` (line 697), `fetchFromAllApis` (~line 570, plus its ~7 internal call sites), `getWeatherData` (line 147)
- `ForecastRepository.saveForecastSnapshot`: no signature change; just delete `locationName = forecast.locationName,` at line 866
- `WeatherRepository.getWeatherData` (line 37) and `WeatherRepository.fetchFromNws` (line 117) pass-throughs
- `WeatherWidgetWorker.kt:171` and `:714` — delete the `locationName = getLocationName(...)` named args

**Keep:** `CurrentTempRepository.refreshCurrentTemperature` (line 113, feeds `recordHistoricalPoi` line 138), `WeatherRepository.refreshCurrentTemperature` (line 51), `WeatherWidgetWorker.getLocationName()` (line 779, used at 527/620).

## Phase 4 — Tests

**Mechanical `locationName = ...` arg deletions** (named args, field had a default — nothing shifts):
- `app/src/test/java/com/weatherwidget/testutil/TestData.kt:38` (delete unused `LOCATION_NAME` const if orphaned)
- ~20 app test files with 1 occurrence each + 3 androidTest files (DailyFutureDayNoHourlyClickIntegrationTest:71, DailyHistoryClickIntegrationTest:66, DailyMainColumnVsBottomIconClickTargetIntegrationTest:62). Sweep: `grep -rn "locationName" app/src shared/src desktop/src` after the entity edit; let the compiler catch stragglers.

**Signature-change call sites** (positional 3rd arg — verify each compiles, don't sed blindly):
- `getWeatherData(lat, lon, "…", ...)`: WeatherRepositoryTest:139/157/224, NwsPrecipAmountIntegrationTest:151/241/305, OpenMeteoIntegrationTest:146/186/245, OpenMeteoDayNightPrecipIntegrationTest:138, WeatherRepositoryRateLimitIntegrationTest:96
- `fetchFromNws(...)`: NwsMiddayOverrideTest:121; `mapDailyForecast(...)`: ForecastRepositoryDayNightPrecipTest:71/113

**Old-schema migration seeds are traps — do NOT touch:** `WeatherDatabaseMigrationTest.kt` INSERTs at lines 34 and 244 seed pre-migration v44/v53 schemas and must keep `locationName`. No existing test validates to "latest version", so none needs the new migration appended.

**Raw SQL:** `shared/src/test/kotlin/com/weatherwidget/data/local/desktop/DesktopAccuracyTest.kt:184` — remove `locationName` column + its `''` literal (it's a literal, not a `?`; bind indices unchanged).

**New Android migration test** in `WeatherDatabaseMigrationTest.kt` (template: `migrate53To54_...` lines 237–258): seed v54 via `helper.createDatabase(testDb, 54)` with a full-column INSERT including `locationName='HQ'`; `runMigrationsAndValidate(testDb, 55, true, MIGRATION_54_55)` (validates against 55.json incl. index names); assert row survived (targetDate=100, dateOfPrediction=99, condition='Sunny') and `PRAGMA table_info(forecasts)` no longer lists `locationName`.

**New desktop migration test** in `desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopStartupTest.kt` (template: `testDatabaseMigrationFromV5ToV6` lines 186–231): hand-create a v9 DB with the old forecasts CREATE (incl. locationName), insert one row with a name, `PRAGMA user_version = 9`, run `initialize()`; assert column gone, row count preserved, `user_version == 10`; call `initialize()` again for idempotency.

## Phase 5 — Regenerate 55.json

Room exports via KSP (`ksp { arg("room.schemaLocation", "$projectDir/schemas") }`, app/build.gradle.kts:383):
1. After Phase 2 edits: `./gradlew :app:kspDebugKotlin` → generates `app/schemas/com.weatherwidget.data.local.WeatherDatabase/55.json`.
2. Diff 55.json `createSql` vs MIGRATION_54_55; reconcile verbatim.
3. Commit 55.json (schemas dir doubles as androidTest assets — the migration test needs it).

## Phase 6 — Docs

`CLAUDE.md` "Database Schema" section is stale (says Version 9, old entity/column names). Update: Android Room v55, desktop SCHEMA_VERSION 10, forecasts PK `(targetDate, dateOfPrediction, locationLat, locationLon, source, fetchedAt)`, note locationName removed. ARCHITECTURE.md has no locationName/version mentions (verified).

## Verification

```bash
./gradlew :app:kspDebugKotlin && git status app/schemas/   # 55.json appears
./gradlew testDebugUnitTest                                # app unit tests
./gradlew :shared:test :desktop:test                       # incl. new v9→10 test
./scripts/emulator-tests.sh -c com.weatherwidget.data.local.WeatherDatabaseMigrationTest
./scripts/emulator-tests.sh                                # full instrumented suite
# NEVER ./gradlew connectedDebugAndroidTest (removes widgets from physical devices)
```

**Manual upgrade check** (guards against fallbackToDestructiveMigration masking a broken migration):
1. On emulator with current v54 build installed + populated widget: `./gradlew installDebug` (upgrade, no uninstall).
2. Widget still renders history/forecast bars (data preserved).
3. `adb logcat`/app_logs: confirm **no** `DB_DESTRUCTIVE_MIGRATION` entry.
4. Desktop: `scripts/buildStart-desktop.sh` against existing `weather.db`; then `sqlite3 ~/.local/share/weather-widget/weather.db 'PRAGMA user_version; PRAGMA table_info(forecasts);'` → 10, no locationName. (Back up DBs first: `python3 scripts/backup_databases.py`.)

## Risks / gotchas

1. **Room validation strictness**: schema JSON is compared column-by-column and index-by-index (incl. index *names*). Mitigation: copy generated 55.json SQL verbatim into the migration.
2. **`fallbackToDestructiveMigration(dropAllTables = true)`** (WeatherDatabase.kt:282) makes a broken migration look like success while wiping a month of forecast history. The migration test + DB_DESTRUCTIVE_MIGRATION log check are the real gates.
3. **Desktop bind-index off-by-one** in `upsertForecasts` fails at runtime only — covered by shared/desktop tests.
4. **Old-schema test seeds** must keep `locationName` (they describe historical schemas).
5. Cheap fallback if desktop DROP COLUMN worries anyone: recreate-table mirroring the `from < 6` block — but DROP COLUMN is safe here (column not in PK/index/view) and matches the `from < 9` guard style.
