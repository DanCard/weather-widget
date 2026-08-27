# Store Open-Meteo cloud layers

## Goal

Persist Open-Meteo's total, low, mid, and high cloud-cover percentages without changing either
cloud graph yet. Once live data is available in all four columns, inspect representative layer
combinations and choose a graph design from evidence.

## Evidence

1. The shared `HourlyForecast` model currently carries total and low cloud cover only.
2. Open-Meteo's hourly and 15-minute requests currently ask for `cloud_cover` and
   `cloud_cover_low`; the parser therefore discards the available mid/high bands.
3. Android Room stores live and snapshot forecasts in `hourly_forecasts` and
   `hourly_forecast_history` at schema version 67. Both tables currently contain total and low.
4. Desktop SQLite mirrors those two tables at schema version 21 and its explicit JDBC statements
   enumerate every persisted and loaded column.
5. Open-Meteo's separate current-reading path writes observation-like rows. Those rows are outside
   this phase: they feed the actual/current resolver, while the proposed layer visualization is a
   forecast graph concern.

## Proposed implementation

1. Add nullable `cloudCoverMid` and `cloudCoverHigh` fields to the shared `HourlyForecast` model.
   Carry them through stitching and all Android entity/model conversions so fields survive partial
   row merges.
2. Request and parse `cloud_cover_mid` and `cloud_cover_high` in Open-Meteo hourly and 15-minute
   responses, clamped to 0-100 like total and low. Other providers leave the fields null.
3. Add the two nullable columns to Android `hourly_forecasts` and `hourly_forecast_history` with a
   Room 67-to-68 migration, register the migration, and export schema 68.
4. Carry both fields through Android live storage, meaningful-change detection, nullable-field
   preservation, and forecast-history snapshots.
5. Add both columns to the desktop fresh-create DDL and a version-22 migration. Update every JDBC
   insert, read, merge, and history path that currently enumerates total/low cloud cover.
6. Keep `observations`, Open-Meteo current readings, Previous Runs' low-only synthetic series, and
   all renderers unchanged in this phase. This avoids assigning forecast layer semantics to actual
   observations or choosing a visualization before inspecting the data.

## Verification

1. Shared parser tests: total/low/mid/high survive hourly and 15-minute parsing; missing fields stay
   null; values are range-clamped.
2. Android migration test: a version-67 database upgrades to 68, preserves existing rows, and reads
   null mid/high for legacy data.
3. Android repository integration test: an Open-Meteo four-layer row round-trips through live and
   history storage, including nullable-field merge/change detection.
4. Desktop database integration test: schema upgrade and JDBC round-trip preserve all four fields.
5. Run focused tests, all shared/desktop tests, Android build, install on the emulator, trigger an
   Open-Meteo fetch, and query its live forecast rows to confirm realistic layer combinations are
   stored. Use those rows as the evidence for the subsequent graph-design discussion.


## Verification results (2026-08-26)

All five implementation steps landed in 68ef68af. Verification run:

- Shared suite: 1221 tests / 157 classes, 0 failures — includes the desktop v21-to-v22 upgrade and
  four-field JDBC round-trip (`DesktopCloudLayerSchemaIntegrationTest`).
- App unit suite: 2086 tests / 292 classes, 0 failures — includes `OpenMeteoApiTest` four-layer
  parse/clamp and `OpenMeteoCloudLayerStorageIntegrationTest` live/history/nullable-merge round-trip.
- Instrumented `WeatherDatabaseMigrationTest`: 15 tests pass on Generic_Foldable_API36, including
  67-to-68.
- Android build + emulator install clean; the live `weather_database` upgraded in place to
  `user_version=68` with all four columns present on `hourly_forecasts` and
  `hourly_forecast_history`.

Live Open-Meteo rows at Mountain View (364 four-layer rows):

- Only `OPEN_METEO` populates low/mid/high; NWS, Silurian, Tomorrow.io, WeatherAPI and OWM leave all
  three null, as intended. 507 low vs 364 mid/high is the expected pre-migration legacy-null gap.
- **Total is a union, not a sum.** On the 152 rows with any cloud, total is at-or-above
  `max(low,mid,high)` in 149 (98%) and at-or-below the clamped sum in 127 (84%); it strictly exceeds
  the max in 38. A stacked-area rendering would therefore be wrong at both ends.
- **The low-only curve has a real blind spot.** 58 of 364 hours (16%) pair `low < 20` with
  `max(mid,high) >= 70`. The stored forecast contains a continuous ~24h stretch from 2026-08-27
  midday where low reads 0-4 while mid and high sit at 100 — the current
  `CloudSeriesBuilder.visibleCloudCover()` would draw that entire day as clear sky.
- **Density suits a sparse encoding.** 267 of 364 hours (73%) have both mid and high below 20, so a
  layer ribbon drawing nothing at low coverage stays mostly empty; mid is notable in 58 hours and
  high in 87.
