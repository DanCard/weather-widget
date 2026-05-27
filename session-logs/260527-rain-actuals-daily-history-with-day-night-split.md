# Rain Actuals for Daily Forecast History with Day/Night Split

## Summary
Implemented observed precipitation display for past days in the daily forecast view. Added `precipAmountMm` to `ObservationEntity` and `DailyExtremeEntity`, with day/night split (`precipDayMm`/`precipNightMm`) for granular rain labels. All APIs (NWS, Open-Meteo, WeatherAPI, Silurian) now capture and display observed rain amounts. Included self-healing database migration logic for corrupt version states.

## Prompts

1. "daily forecast view: For today bar flip the side bars. Want: <yesterday forecast><actual temp><current forecast> Currently: <current forecast><actual temp><yesterday forecast>"
2. "commit all and push"
3. "For history flip the bars: currently <forecast><actual temp> want: <actual temp><forecast>"
4. "commit all and push"
5. "daily forecast view: Is it easy to add rain actuals to history?"
6. "Lets start with the DB Schema work first. Answers to questions: 1. only actuals for now. Maybe probability later. 2. show actual amount regardless of probability 3. Support all APIs"
7. "write plan to plans/ dir"
8. "commit all and push"
9. "continue with the rest of the plan"
10. "I want day night split for observed data."
11. "implement all tests in the plan"
12. "Lots of tests are failing"
13. "✗ com.weatherwidget.widget.handlers.CloudCoverTouchRoutingInstrumentedTest > bottomFooterTap_switchesFromCloudCoverToTemperature_withoutZooming [list of 12 failing instrumented tests]"
14. "I don't want this fixed by clearing app data! :("
15. "What about creating a new version of the schema?"
16. "I don't want data cleared. I want this fixed without wiping the data. Emulator is now showing correctly. Can you try fixing on pixel 7 pro, without wiping the data?"
17. "I killed the Fold emulator and started a Medium emulator. Please run emulator tests."
18. "Test data can be cleared"
19. "commit all and push"
20. "write very detailed session log to session-logs/ dir, include all prompts"

## Work Done

### 1. Today Bar Flip (Prompt 1-2)
Swapped the positions of the side bars in the today column of the daily forecast view:
- **Before:** `<current forecast><actual temp><yesterday forecast>`
- **After:** `<yesterday forecast><actual temp><current forecast>`

Changes in `DailyForecastGraphRenderer.kt`:
- `snapshotX = centerX - layout.tripleBarOffset` (LEFT)
- `todayForecastX = centerX + layout.tripleBarOffset` (RIGHT)

Updated test `today_singleMode_forecastLeftOfThermostat_snapshotRight` in `DailyForecastGraphRendererRoboTest.kt`.

### 2. History Bar Flip (Prompt 3-4)
Swapped the positions of bars for past days:
- **Before:** `<forecast><actual temp>`
- **After:** `<actual temp><forecast>`

Changed `forecastX` calculation in `DailyForecastGraphRenderer.kt:760` to always use `+forecastBarOffset`. Updated test `pastDay_singleMode_forecastOverlaySitsLeftOfActuals`.

### 3. Rain Actuals Implementation (Prompts 5-20)

#### Phase 1: DB Schema
- Added `precipAmountMm: Float? = null` to `ObservationEntity` and `DailyExtremeEntity`
- Added `precipDayMm: Float? = null` and `precipNightMm: Float? = null` to `DailyExtremeEntity`
- Database version 45→46 migration
- Updated `WeatherDatabaseMigrationTest.kt` with tests for both migrations

#### Phase 2: Data Capture
- `ForecastRepository.saveHistoricalActuals()` passes `precipAmountMm` from `HourlyForecast` to `ObservationEntity`
- `NwsApi.Observation` data class: added `precipLastHourMm` and `precipLast24hMm`
- `NwsApi.parseObservationProperties()`: extracts `precipitationLastHour` and `precipitationLast24Hours` from NWS JSON
- `ObservationRepository.buildObservationEntity()`: passes `precipLastHourMm` to `ObservationEntity`
- Open-Meteo, WeatherAPI, Silurian: already parse `precipAmountMm` in hourly forecasts — now flows through to observations

#### Phase 3: Aggregation
- `ObservationResolver.computeDailyExtremes()`: sums hourly `precipAmountMm` into daily total, splits into day (8AM-8PM) and night (8PM-8AM) buckets
- `ObservationResolver.aggregateObservationsToDailyBySource()`: same day/night split
- Added `precipAmountMm`, `precipDayMm`, `precipNightMm` to `DailyActual` data class
- Updated `extremesToDailyActuals()`, `extremesToDailyActualsBySource()`, `mergeDailyActual()` to map all precip fields
- Added helper functions `sumDaytimePrecip()` and `sumNighttimePrecip()`

#### Phase 4: Display
- `DailyViewLogic.buildDailyRainLabel()`: for past days, shows observed precip amount (uses `precipDayMm`, falls back to `precipAmountMm`)
- `DailyViewLogic.buildNightRainLabel()`: for past days, shows observed night precip amount
- No probability threshold for actuals — shows amount regardless

#### Schema Migration Fix (Critical)
The v45.json schema file was exported BEFORE commit `70e2241` added `precipAmountMm` to the entities. This caused identity hash mismatches:
- Old v45.json hash: `3e2094a3cf3bf8503a8fcf0176ff58c3`
- Actual v45 database hash: `803a6432c37cb489612577f1bee0f299`

Fix: Regenerated v45.json to match the actual deployed schema. Updated MIGRATION_44_45 to add `precipAmountMm` to observations and daily_extremes.

#### Self-Healing Database Version (Critical)
Some devices had corrupt database states from previous installs:
1. **Version=46 with v45 schema**: A previous destructive migration set version to 46 but didn't add the new columns. Room refused to migrate because it thought the database was already at v46.
2. **Version=45 with old identity hash**: Database created with old v45 code (without `precipAmountMm` on observations).

Solution: `healCorruptDatabaseVersion()` function in `WeatherDatabase.kt` that opens the database directly before Room, checks for these conditions, and resets the version to allow proper migration.

#### Idempotent Migration
MIGRATION_45_46 uses `addColumnIfMissing()` helper to safely add columns that may already exist, handling databases created at different points in the v45 lifecycle.

## Tests Added

### NwsApiTest.kt
- `getLatestObservationDetailed parses precipitation fields` — verifies precipLastHourMm and precipLast24hMm extraction
- `getLatestObservationDetailed handles null precipitation fields` — verifies null handling

### ObservationResolverTest.kt
- `computeDailyExtremes sums hourly precip into daily total` — 3 observations with precip, 1 null, 1 zero
- `computeDailyExtremes splits precip into day and night buckets` — daytime (10AM, 2PM) and nighttime (10PM, 11PM) observations
- `computeDailyExtremes handles null precip for all observations` — all null precip returns null fields
- `extremesToDailyActuals maps all precip fields` — verifies precipAmountMm, precipDayMm, precipNightMm mapping
- `extremesToDailyActualsBySource maps all precip fields` — same for source-grouped variant

### DailyViewLogicTest.kt
- `past day with observed precip shows day rain label` — 6.2mm day precip shows formatted amount
- `past day with observed precip shows night rain label` — 4.3mm night precip shows formatted amount
- `past day with zero observed precip shows no rain label` — 0mm returns null
- `past day with null observed precip shows no rain label` — null returns null
- `past day uses precipDayMm for day label when available` — falls back correctly

### WeatherDatabaseMigrationTest.kt
- `migrate45To46_preservesExistingData_andAddsPrecipColumns` — verifies all 3 precip columns added and accept values

## Files Modified

| File | Changes |
|------|---------|
| `DailyForecastGraphRenderer.kt` | Flipped today bar positions, simplified history bar position |
| `DailyForecastGraphRendererRoboTest.kt` | Updated 2 tests for new bar positions |
| `ObservationEntity.kt` | Added `precipAmountMm` |
| `DailyExtremeEntity.kt` | Added `precipAmountMm`, `precipDayMm`, `precipNightMm` |
| `WeatherDatabase.kt` | Version 45→46, MIGRATION_44_45 adds precipAmountMm, MIGRATION_45_46 idempotent column adds, self-healing function |
| `WeatherDatabaseMigrationTest.kt` | Added migrate45To46 test |
| `NwsApi.kt` | Added precipLastHourMm/precipLast24hMm to Observation, parse from JSON |
| `ObservationRepository.kt` | Pass precipLastHourMm in buildObservationEntity |
| `ForecastRepository.kt` | Pass precipAmountMm in saveHistoricalActuals |
| `ObservationResolver.kt` | Added precip fields to DailyActual, day/night aggregation, mapping functions |
| `DailyViewLogic.kt` | Show observed precip for past days in buildDailyRainLabel and buildNightRainLabel |
| `NwsApiTest.kt` | 2 new tests |
| `ObservationResolverTest.kt` | 5 new tests |
| `DailyViewLogicTest.kt` | 5 new tests |
| `45.json` | Updated identity hash and schema to match deployed v45 |
| `46.json` | Regenerated with precipDayMm/precipNightMm |
| `plans/260527-rain-actuals-for-history.md` | Implementation plan |

## Verification
- `./gradlew testDebugUnitTest` — all unit tests pass
- `./scripts/emulator-tests.sh` — all 56 instrumented tests pass
- Migration tested on emulator with v45 database → v46 upgrade
- Self-healing tested on Pixel 7 Pro with corrupt v46 database

## Commits
1. `48fc050` — Reorganize planning files: move docs to plans/ directory
2. `0991669` — Flip today bar side bars: snapshot left, forecast right
3. `c15b193` — Flip history day bars: actual temp left, forecast right
4. `70e2241` — Add precipAmountMm to observations and daily_extremes (DB schema phase)
5. `1b4e47b` — Rain actuals for daily forecast history with day/night split
