# Fix cross-device disagreement on the hourly forecast line (coordinate fragmentation)

## Context

The emulator, Samsung Galaxy Z Fold, and desktop draw **different NWS temperatures for the same
hour** (e.g. 4pm today: Samsung 77°, others 76°). Live-DB evidence proved this is **not** a render
bug — it is a *data-state* divergence:

- `hourly_forecasts` (and `hourly_forecast_history`) have `PRIMARY KEY(dateTime, source,
  locationLat, locationLon)` with lat/lon stored as **REAL (float)**. GPS jitter moves the saved
  coordinate by a few ten-millionths of a degree (~10 cm) between fetches, so
  `@Insert(onConflict = REPLACE)` **never overwrites** — each fetch *appends* a new row. Stale
  forecasts for a given hour linger for up to a month (cleanup ages rows out by `fetchedAt`, never
  as "superseded fragment"). Confirmed: Samsung had a 4pm NWS row fetched **Jun 20** sitting beside
  today's fetch at a 10 cm-offset coordinate; the emulator had **Jun 16** fragments. DB bloat
  corroborates: Samsung 52 MB vs desktop 27 MB.
- `resolveForecastsByTime` (two copies: `app/.../widget/handlers/ForecastSourcePriority.kt:9` and
  `shared/.../actuals/ActualTemperatureSeriesBuilder.kt:474`) picks, per hour, `minByOrNull
  { fetchedAt }` for **past** hours and `maxByOrNull { fetchedAt }` for **future**. The
  earliest-for-past rule was meant to show the "original prediction," but it actually surfaces
  *whatever stale fragment each device happens to still hold* — and that differs per device, which
  is the visible disagreement. 4pm is the trap: it is ~1 min past `now`, so it counts as past and
  the oldest fragment wins.

### Decisions (confirmed with user)
1. **Past-hour line semantics:** keep "previous predictions," but source them **deterministically
   from `hourly_forecast_history`** instead of leftover live-table fragments.
2. **Scope:** read-path fix **+ write-side lat/lon quantization + one-time cleanup** of existing
   duplicates (shrinks the bloated DBs).
3. **Architecture:** **add `locationLat`/`locationLon` to the shared `HourlyForecast` model** so the
   shared selection logic can collapse same-site fragments directly (user chose this over a
   generic accessor helper).

### Intended outcome
All surfaces converge on the same hourly forecast value: identical for current/future hours
(freshest fetch, which every device shares) and consistent original-prediction values for past
hours (from the snapshot table). New fragments stop forming; existing ones are cleaned up.

---

## Design

### 1. Widen the shared model — `shared/.../data/model/ForecastTypes.kt`
Add two **nullable, default-null** fields to `HourlyForecast` (source-compatible with the ~10
existing consumers — `RainAnalyzer`, `HeaderPrecipCalculator`, `CurrentTempResolver`, etc., which
simply leave them null):
```kotlin
val locationLat: Double? = null,
val locationLon: Double? = null,
```
Populate them where rows are mapped from storage:
- Android: `HourlyForecastEntity.toHourlyForecast()` (`app/.../data/local/HourlyForecastEntity.kt:24`).
- Desktop: the `HourlyForecast(...)` constructions in `DesktopWeatherDao.getLatestHourly` (~line
  478) and `getHourlyHistory` (~line 529), reading `locationLat`/`locationLon` from the `ResultSet`.

### 2. One shared selection helper — new `shared/.../shared/actuals/HourlyForecastSelector.kt`
Single source of truth for "collapse candidate rows to one forecast per hour," reused by both
platforms (replaces the two duplicated `resolveForecastsByTime` bodies). Operates on
`List<HourlyForecast>` (now carrying coords):
```
selectForecastsByTime(rows, displaySourceId, centerLat, centerLon, nowMs): Map<Long, HourlyForecast>
  group by dateTime; for each hour:
    1. source-priority filter (displaySource -> GENERIC_GAP -> all)   // existing rule
    2. SAME-SITE filter: keep rows where LocationMatch.sameSite(center, row) — drops both
       jitter-stale fragments at the wrong fetch AND neighbouring markers (e.g. default
       37.422 vs GPS 37.4168, which are ~0.005° apart > 0.002° sameSite, so the off-site
       marker is excluded). If none match center, fall back to all (defensive).
    3. pick freshest: maxByOrNull { fetchedAt }   // current/future
```
Reuse `LocationMatch.sameSite` (`shared/.../data/local/LocationMatch.kt:48`) — it exists for exactly
this 0.002°/~200 m "same physical site" test. Keep the existing COLD/dedup debug logging behind the
shared `Log` shim.

- `ActualTemperatureSeriesBuilder.resolveForecastsByTime` (line 474) and the arbitrary
  `hourlyForecastSeries` `firstOrNull` reducer (line 464) both delegate to the helper.
- App's `ForecastSourcePriority.resolveForecastsByTime` becomes a thin wrapper: map
  `List<HourlyForecastEntity>` → `HourlyForecast` (now with coords), call the helper. Change
  `TemperatureHourDataBuilder.forecastsByTime` (`app/.../handlers/TemperatureHourDataBuilder.kt:158,
  262`) from `Map<Long, HourlyForecastEntity?>` to `Map<Long, HourlyForecast?>` — the only field
  reads (`condition`, `cloudCover`, `precipProbability`, `temperature` at lines 262–279) all exist
  on `HourlyForecast`, so ripple is minimal.

### 3. Past hours from history (deterministic original prediction)
The freshest-wins rule above governs current/future. For **past** hours, draw the forecast value
from `hourly_forecast_history` instead of the live table:
- App: extend the stitching in `GraphDataLoader.kt:114–129` (and/or
  `HourlyForecastStitcher.stitch`) so that for `dateTime < now` the forecast **temperature/condition**
  comes from the **earliest snapshot bucket** covering that hour (the original as-predicted value),
  same-site filtered; current rows still win for `dateTime >= now`. Today the history rows only
  backfill nullable fields — extend them to also own the past-hour value.
- Desktop: same logic in `DesktopWeatherDao.getHourlyWithHistory` (~line 576), which already merges
  `getLatestHourly` + `getHourlyHistory`.
- Per-hour history selection (earliest bucket, same-site) also routes through the shared helper /
  `HourlyForecastStitcher` so Android and desktop behave identically. Note: snapshots are captured
  independently per device, so past values are "best available original prediction," deterministic
  per device — not byte-identical across devices, but no longer the random-surviving-fragment lottery.

### 4. Stop new fragments — quantize lat/lon on write
Round coordinates to a fixed grid (**4 decimals ≈ 11 m**, well below `sameSite`'s 0.002°) right
before persisting, so REPLACE actually overwrites. Add a tiny shared helper (e.g.
`LocationMatch.quantize(lat, lon)` or a `LocationKey` object) and apply it at the write choke points:
- Android: the entities passed to `hourlyForecastDao.insertAll` (`ForecastRepository.kt:793`) and
  `hourlyForecastHistoryDao.insertAll` (`:817`) — quantize in the entity construction at
  `ForecastRepository.kt:834/887/802` (and the delta-comparison that computes `changedEntities` must
  compare on the quantized key so it doesn't churn).
- Desktop: `DesktopWeatherDao.upsertHourlyForecasts` (line 20) and `upsertHourlyForecastHistory`
  (line 54) — quantize the `locationLat/locationLon` params before binding.

### 5. One-time cleanup of existing duplicates
- Android: `MIGRATION_47_48` in `WeatherDatabase.kt` (bump `version = 48`, register in
  `.addMigrations(...)`, generate `app/schemas/.../48.json` via `exportSchema`). The migration
  rounds lat/lon and keeps the freshest row per `(dateTime, source, round(lat,4), round(lon,4))` for
  `hourly_forecasts`, and per `(…, snapshotBucket, …)` for `hourly_forecast_history`. SQL must match
  the generated `48.json` byte-for-byte (Room post-migration validation). Follow the existing
  `MIGRATION_46_47` pattern, including the downgrade guard block at lines 224–237.
- Desktop: idempotent dedup `DELETE` in `DesktopWeatherDatabase` init (collapse to freshest per the
  same key). Safe to run on every startup since it's a no-op once clean.

---

## Critical files
- `shared/.../data/model/ForecastTypes.kt` — add coords to `HourlyForecast`.
- `shared/.../shared/actuals/HourlyForecastSelector.kt` — **new** shared selection helper.
- `shared/.../shared/actuals/ActualTemperatureSeriesBuilder.kt` — delegate (lines 464, 474).
- `shared/.../data/model/HourlyForecastStitcher.kt` — past-hour value from history, same-site.
- `shared/.../data/local/LocationMatch.kt` — add `quantize(...)`; reuse `sameSite`.
- `app/.../data/local/HourlyForecastEntity.kt` — populate coords in `toHourlyForecast()`.
- `app/.../widget/handlers/ForecastSourcePriority.kt` — thin wrapper over shared helper.
- `app/.../widget/handlers/TemperatureHourDataBuilder.kt` — `forecastsByTime` type change.
- `app/.../widget/handlers/GraphDataLoader.kt` — past-hour history stitching.
- `app/.../data/repository/ForecastRepository.kt` — quantize on write (lines 793/817/834/887/802).
- `app/.../data/local/WeatherDatabase.kt` — `MIGRATION_47_48`, `version = 48`.
- `app/schemas/.../48.json` — generated schema.
- `shared/.../data/local/desktop/DesktopWeatherDao.kt` — populate coords, quantize writes,
  past-hour history merge (lines 20, 54, 478, 529, 576).
- `shared/.../data/local/desktop/DesktopWeatherDatabase.kt` — idempotent cleanup DELETE.

## Tests
- **New** `HourlyForecastSelectorTest` (shared, plain JUnit): two same-site fragments at one hour
  (fresh + day-stale) → fresh wins; a neighbouring off-site marker (~0.005° away) is excluded; a
  past hour with a history snapshot → original value; GENERIC_GAP source-priority preserved. This is
  the direct regression for the reported bug.
- `LocationMatch.quantize` rounding test (and that quantized values fall within `sameSite`).
- Android `MigrationTest` (`androidTest`, run via `./scripts/emulator-tests.sh -c <Class>`): seed
  v47 with duplicate jittered rows, migrate to 48, assert one freshest row per quantized key.
- Run shared unit suite: `./gradlew :shared:test` and `./gradlew testDebugUnitTest`.

## Verification (end-to-end, the original symptom)
1. `./gradlew installDebug` to emulator + Pixel + Samsung; rebuild/restart desktop via
   `scripts/buildStart.sh`.
2. Trigger a fetch on each (widget tap / `ACTION_REFRESH` broadcast; desktop relaunch).
3. Pull each DB (`adb exec-out run-as com.weatherwidget cat databases/weather_database`, plus
   `-wal`) and the desktop `~/.local/share/weather-widget/weather.db`; for the **same** hour and
   source confirm: (a) only one row per quantized coordinate remains, (b) the displayed value
   matches across all four surfaces for current/future hours.
4. Screenshot the hourly graph on each (convert PNG→JPG per CLAUDE.md) and compare the 4pm point.
5. Confirm DB sizes drop after the cleanup migration (Samsung's 52 MB should fall substantially).
</content>
</invoke>
