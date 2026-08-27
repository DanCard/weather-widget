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

