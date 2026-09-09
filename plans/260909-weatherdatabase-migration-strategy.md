# WeatherDatabase migration strategy (proposal)

**Date:** 2026-09-09 · **Parent plan:**
[plans/260909-desktop-android-duplication-and-complexity-review.md](260909-desktop-android-duplication-and-complexity-review.md) (Phase 4) ·
**Status:** proposal only — no code changed

## Why this needs its own plan

`app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt` is the highest-blast-radius
file in the repo: it owns every user's on-device forecast, observation, daily-history and log row.
It is also the one file where a mistake is silent (the app keeps running with wiped or half-migrated
data). The 260908 audit and the parent plan both deliberately kept it out of the mechanical
cleanups; this document scopes a dedicated change.

## Current state (verified 2026-09-09)

1. `version = 70`, `exportSchema = true`.
2. **26 hand-written `Migration` objects**, `MIGRATION_44_45` … `MIGRATION_69_70`, wired in one
   `addMigrations(...)` call at `WeatherDatabase.kt:764`. They occupy ~600 of the file's ~854 lines.
3. **`fallbackToDestructiveMigration(dropAllTables = true)`** at `:765` — any gap in the chain
   silently wipes every table.
4. **`healCorruptDatabaseVersion`** (`:810-839`) hand-rolls schema repair by downgrading
   `db.version` 46→45→44 while probing `PRAGMA table_info`; it must be kept in sync with the
   migrations by hand.
5. `MIGRATION_65_66` is declared **after** `MIGRATION_66_67`, so the file's order no longer matches
   the chain (cosmetic today, a real hazard when someone adds the next migration).
6. `app/schemas/com.weatherwidget.data.local.WeatherDatabase/` holds `9.json` … `70.json` (62
   files), so Room auto-migration is available and unused.
7. `app/src/androidTest/.../WeatherDatabaseMigrationTest.kt` (644 lines) tests **17 of the 26**
   migrations individually (`44→45` … `69→70` with gaps at `49→50`, `51→52`, `52→53`, `57→58`,
   `61→62`, `62→63`, `63→64`, `65→66`, `66→67`). There is **no full-chain 44→70 test**.

## Risks

1. **Silent data loss.** A future version bump without a migration, or a gap in the chain, hits
   `fallbackToDestructiveMigration` and wipes the DB. The user sees "no data" and a refetch; the
   forecast-accuracy history (the app's differentiator) is gone permanently.
2. **Hand-maintained repair.** `healCorruptDatabaseVersion` duplicates schema knowledge that the
   migrations and exported schemas already encode. Drift here is invisible until a device hits it.
3. **Untested paths.** 9 migrations and the whole chain are unverified; several of the untested ones
   are renames/recreates (higher risk than `ADD COLUMN`).

## Proposed phases

### Phase A — eliminate the destructive fallback (highest value, low risk)

1. Replace `fallbackToDestructiveMigration(dropAllTables = true)` with a logging
   `fallbackToDestructiveMigrationOnDowngrade` **only** if downgrade is genuinely supported, or
   remove it entirely so a missing migration throws (`IllegalStateException`) instead of wiping.
2. Add a startup guard/test that the `addMigrations` chain covers every step from the oldest
   supported version to `version` (a pure check over the declared `startVersion`/`endVersion`).
3. Keep `healCorruptDatabaseVersion` until Phase B lands, then delete it (its job is subsumed by a
   correct chain + a non-destructive fallback).

### Phase B — adopt Room auto-migrations for the mechanical steps

1. Convert the simple `ALTER TABLE … ADD COLUMN` migrations to `@AutoMigration(from, to)` entries in
   the `@Database(autoMigrations = [...])` list, using the exported schemas. Keep hand-written
   migrations only for the steps that rewrite data (renames, `46→47` climate-normals recreate,
   `47→48` jitter collapse, `56→57` primary-key change, `58→59` actuals clear, `69→70` QC flag).
2. Order the remaining hand-written migrations by version in the file.
3. Do this one migration at a time, with the existing per-migration test as the guard, so a failure
   is attributable.

### Phase C — close the test gaps

1. Add the 9 missing per-migration tests, prioritising the rewrites (`57→58`, `61→62`, `62→63`,
   `63→64`, `65→66`, `66→67`).
2. Add one **full-chain** test: open a v44 database with representative rows, run the production
   `addMigrations` list to v70, and assert the rows survived and the schema matches `70.json`.
   This is the test that would have caught every class of chain gap.
3. Wire the full-chain test into `scripts/emulator-tests.sh` (it needs real SQLite, so it stays an
   instrumented test per AGENTS.md).

### Phase D — document the contract

1. Add a short "adding a migration" checklist to `AGENTS.md`: bump `version`, add the migration
   (auto where possible), add a per-migration test, add the schema json, run the full-chain test,
   and never leave a version gap.
2. Note the retention interplay: a destructive wipe also drops `hourly_forecast_history` (18-month
   actuals archive) and `daily_history` (accuracy baselines), which cannot be refetched.

## Verification

1. Per phase: `./scripts/staggered-tests.sh` (the instrumented phase runs the migration tests on a
   real emulator).
2. Phase A/B additionally: install the pre-change APK on an emulator, let it write rows, then
   install the post-change APK and confirm the rows survive (`adb shell run-as com.weatherwidget
   sqlite3 databases/weather_database 'select count(*) from daily_history'`).
3. Never test with `pm clear` — it hides exactly the migration path under test.

## Out of scope

- Changing the schema itself (columns/tables) beyond what a migration already defines.
- The desktop JDBC `DesktopWeatherDatabase` (separate schema-versioning story).
