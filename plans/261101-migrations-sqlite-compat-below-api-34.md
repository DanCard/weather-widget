# Make Room migrations work on old Android SQLite

Status: implemented, not committed. See Outcome.

## Problem

`./scripts/emulator-tests.sh` fails on `Medium_Phone_API_30`:

```
1) migrate54To55_dropsLocationNameColumn(WeatherDatabaseMigrationTest)
SQLiteException: near "DROP": syntax error (code 1 SQLITE_ERROR):
  while compiling: ALTER TABLE `forecasts` DROP COLUMN `locationName`
  at WeatherDatabase$Companion$MIGRATION_54_55$1.migrate(WeatherDatabase.kt:228)
```

Evidence:

- `logs/emulator-tests/test_results_emulator-5556-20261101-143211.log` and
  `test_results_emulator-5558-20261101-134024.log`: 114 of 115 pass, with this failure in both runs.
- `logs/emulator-tests/test_results_emulator-5554-20261101-140225.log`: `Pixel_Tablet_API36` passes
  all 115.
- `adb -s emulator-5556 shell sqlite3 --version` on the API 30 emulator reports 3.28.0.

## Root cause

Android's SQLite is part of the OS, and `minSdk` is 26, but these migrations use SQL newer than the
oldest supported devices have. Testing only on API 36/37 hid this.

| Feature | Needs SQLite | First Android with it |
|---|---|---|
| `ALTER TABLE ... RENAME COLUMN` | 3.25 | API 30 (3.28, measured) |
| `ALTER TABLE ... DROP COLUMN` | 3.35 | API 34 |

The per-API versions other than API 30 come from memory of Android's documentation. Step 1 measures
API 28.

## Affected migrations

| Migration | Line | Statement | Fails on | Added | Play exposure |
|---|---|---|---|---|---|
| `MIGRATION_81_82` | 900 | `forecasts` rename `batchFetchedAt` | API 26-29 | 2026-10-24 | **Yes, recent releases** |
| `MIGRATION_54_55` | 228 | `forecasts` drop `locationName` | API 26-33 | 2026-07-05 | Probably none |
| `MIGRATION_53_54` | 218 | `forecasts` rename `forecastDate` | API 26-29 | 2026-07-05 | Probably none |
| `MIGRATION_52_53` | 207 | `hourly_forecast_history` rename `snapshotBucket` | API 26-29 | 2026-07-05 | Probably none |

- **Exposure:** the first `Release version` commit (1.0.3, 2026-07-22) was already at database v56, so
  Play installs should not need the July migrations. They are fixed anyway because sideloaded or
  earlier builds could still need them.
- **`MIGRATION_81_82` is not yet reproduced.** It passes on API 30, and no API 26-29 AVD or system
  image is installed. Step 1 confirms it.
- **Failure mode:** `fallbackToDestructiveMigration` only covers a missing migration path, so a throwing
  migration does not wipe data. The device stays on its old version and a fixed release completes the
  upgrade (measured; see Outcome). This originally predicted a crash, which did not happen.
- **Fresh installs are unaffected:** they create the latest schema without running migrations.
- **Desktop is unaffected:** it bundles SQLite through `sqlite-jdbc`.

## Plan

### 1. Reproduce on API 28 before changing code

```bash
~/.Android/Sdk/cmdline-tools/latest/bin/sdkmanager "system-images;android-28;google_apis;x86_64"
~/.Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd -n Medium_Phone_API_28 \
    -k "system-images;android-28;google_apis;x86_64" -d medium_phone
~/.Android/Sdk/emulator/emulator -avd Medium_Phone_API_28 -gpu swiftshader_indirect -no-boot-anim
```

Run the migration class on that emulator only:

```bash
EMULATOR_TESTS_TARGET_SERIAL=<serial> ./scripts/emulator-tests.sh --fresh \
    -c com.weatherwidget.data.local.WeatherDatabaseMigrationTest
```

Use `EMULATOR_TESTS_TARGET_SERIAL`, not `-e`: when an emulator is already running, the script picks the
first one and ignores `-e`. Record the SQLite version and which tests fail. Expected failures are
`migrate53To54`, `migrate54To55`, and `migrate81To82`. If `migrate81To82` passes, stop and re-evaluate.

### 2. Rewrite the four migrations

In `WeatherDatabase.kt`, replace each `ALTER` with the table recreation that `MIGRATION_57_58` already
uses:

1. Create `<table>_new` using the target version's `createSql` from `app/schemas/`.
2. `INSERT INTO <table>_new (...) SELECT ... FROM <table>`, listing columns explicitly and mapping the
   renamed column.
3. `DROP TABLE <table>`, then `ALTER TABLE <table>_new RENAME TO <table>`.
4. Recreate every index in the target schema.

Primary keys and indices to preserve:

| Migration | Primary key after | Indices after |
|---|---|---|
| 52 to 53 | `dateTime, source, locationLat, locationLon, timestampToGroupPredictions` | `..._locationLat_locationLon_source_timestampToGroupPredictions` |
| 53 to 54 | `targetDate, dateOfPrediction, locationLat, locationLon, source, fetchedAt` | `..._locationLat_locationLon`, `..._targetDate_source_locationLat_locationLon_batchFetchedAt` |
| 54 to 55 | same as 53 to 54 | same as 53 to 54 |
| 81 to 82 | same as 53 to 54 | `..._locationLat_locationLon`, `..._targetDate_source_locationLat_locationLon_lastConfirmedAt` |

Do not bump the database version or change the exported schemas: every migration produces the same
schema as before, so devices already past these versions are unaffected.

`forecasts` can be large, so check the 81 to 82 copy time against a real database backup
(`scripts/backup_databases.py`) since it now runs during database open.

### 3. Strengthen the migration tests

In `WeatherDatabaseMigrationTest.kt`:

- Add a `migrate52To53` test, which does not exist today.
- Have the four tests assert that a row survives with its primary key, the renamed column, and nullable
  columns intact. A table recreation can silently misalign columns, which schema validation does not
  catch.
- Keep using `runMigrationsAndValidate`, which compares the resulting tables and indices with the
  exported schema.

### 4. Add a guard test

Add a pure JVM test that reads `WeatherDatabase.kt` and fails if it contains `DROP COLUMN` or
`RENAME COLUMN`, with a message pointing at the `MIGRATION_57_58` pattern. It must follow the category
rules in `arch/testing-strategy.md`, and it must fail against the current code.

This catches the mistake on every unit run, including when no old emulator is connected.

### 5. Update documentation

- `arch/testing-strategy.md`: name API 28 and API 30 as compatibility targets, and state the SQL limit
  that `minSdk = 26` imposes on migrations.
- `AGENTS.md`, persistence conventions: add one line saying migrations must run on `minSdk`'s SQLite.
- `scripts/staggered-tests.sh:295`: update the comment saying `Medium_Phone_API_30` is not a documented
  test target. Leave the `BOOT_AVDS` rotation unchanged, so old emulators run when started rather than
  slowing every gate.

## Verification

1. Run the guard test against the current code first and confirm it fails.
2. Run the full instrumented suite on API 28, API 30, and API 36. The migration tests must fail on API
   28 before the change (step 1) and pass after it.
3. Run `./scripts/staggered-tests.sh`.
4. Do a real upgrade on the API 28 emulator: install a build from before `d23a459c` (database v81), let
   it populate data, install the fixed APK, and confirm the widget renders with its data and that the
   schema is at the current version. Then repeat with the unfixed APK to confirm that the crash happens
   and that the fixed APK recovers from it.

## Out of scope

- Other failures on API 28. The full suite has never run there, so failures unrelated to migrations
  get reported and triaged separately rather than added to this change.
- Raising `minSdk`. Worth considering if Play Console shows few installs on Android 8-10, but it would
  leave affected users without a fix.

## Open items

- **Play Console (user):** check the install share on Android 8-10, which decides how urgently a release
  should follow. A clean crash report does not rule this out, because the paths tested fail without a
  fatal crash.
- **Placed widget:** the unfixed behavior was not tested with a widget on the home screen.
- **Scan coverage:** the scan for newer SQL covered `app/src/main/java` with a keyword list and found
  only the statements above. Step 2 of Verification is the real check for API 26-29.

## Outcome

Deviations from the plan:

- The upgrade test seeded a v81 database from `app/schemas/.../81.json`, with 4,429 rows to match the
  Pixel 7 Pro backup, instead of running a build from before `d23a459c`. The unfixed APK was built from
  `HEAD` with `git archive`.
- `avdmanager` wrote the AVD to `~/.config/.android/avd`. Its `.ini` was copied to `~/.android/avd`, where
  the emulator looks.

Results on `Medium_Phone_API_28` (SQLite 3.22.0):

| Check | Before the fix | After the fix |
|---|---|---|
| `WeatherDatabaseMigrationTest` | `migrate53To54`, `migrate54To55`, `migrate81To82` fail | All pass |
| Open app on a v81 database | Stays at v81; `near "COLUMN": syntax error` on each database access | Upgrades to v85 with no errors |
| Fatal crashes (welcome screen, Settings, UI refresh) | None | None |
| Data | Not reachable by the app | 4,430 rows, every sentinel column intact, both indices |
| Copy time for 4,430 rows on the emulator | n/a | 0.07 s |

Full instrumented suite (118 tests, no failures): API 28 115 passed and 3 skipped; API 30 117 passed and
1 skipped; API 36 116 passed and 2 skipped. Skips are `Assume` checks: opt-in live test, no placed widget,
API-30-only test.

The guard test failed on the four original lines before the fix and passes after it, in 17 ms.

`./scripts/staggered-tests.sh --fresh` on all three emulators: first run failed, second run passed.

The first run failed once on API 28 in `DailyHeaderRainLabelTapIntegrationTest.tappingRainLabel_onWideWidgetWithDelta_opensRainGraph`
(`tempZone=0..0, rainZone=0..111`, label at x=150-227). It passed 4 of 4 alone and in 9 of 9 parallel
emulator runs (3 on API 28), and this change does not touch it. Cause not investigated; left out of scope.
