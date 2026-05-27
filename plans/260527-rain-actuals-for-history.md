# Rain Actuals for Daily Forecast History

## Context

The daily forecast view shows rain labels (probability and amount) for future days, but **completely skips past days** — both `buildDailyRainLabel()` and `buildNightRainLabel()` return null when `isPastDate` is true.

The APIs (Open-Meteo, WeatherAPI, NWS) already return observed precipitation for the last 3 days, but the data is **discarded** during `saveHistoricalActuals()` — only temperature and condition are stored.

**Goal:** Show observed rain amounts for past days in the daily forecast view, regardless of probability threshold.

## Phase 1: DB Schema — COMPLETE

Added `precipAmountMm: Float?` to `ObservationEntity` and `DailyExtremeEntity` with migration 45→46.

### Files changed:
- `app/src/main/java/com/weatherwidget/data/local/ObservationEntity.kt`
- `app/src/main/java/com/weatherwidget/data/local/DailyExtremeEntity.kt`
- `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`
- `app/src/androidTest/java/com/weatherwidget/data/local/WeatherDatabaseMigrationTest.kt`
- `app/schemas/.../46.json`

## Phase 2: Data Capture

Pass already-parsed precipitation through the storage layer.

### 2a. ObservationRepository / ForecastRepository

Update `saveHistoricalActuals()` to pass `precipAmountMm` when building `ObservationEntity` from hourly forecasts.

**File:** `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt` (~line 801-831)

The hourly forecasts already have `precipAmountMm` parsed — just need to sum hourly values into a daily total when creating the observation entity, or store per-observation and aggregate later.

### 2b. NWS Observation Parsing

Extract precipitation fields from NWS observation JSON.

**File:** `app/src/main/java/com/weatherwidget/data/remote/NwsApi.kt` (lines 453-476, `parseObservationProperties`)

NWS observations include `precipitationLastHour`, `precipitationLast3Hours`, `precipitationLast24Hours`. Need to verify exact field names/units against real API response.

### 2c. Open-Meteo / WeatherAPI / Silurian

These APIs already parse `precipAmountMm` for hourly data. The data flows through `HourlyForecast.precipAmountMm` but is dropped at the observation boundary. Update `saveHistoricalActuals()` to preserve it.

### Tests needed:
- Unit test: `saveHistoricalActuals()` stores precipAmountMm from hourly forecasts
- Integration test: NWS observation parsing extracts precipitation

## Phase 3: Aggregation

Sum hourly observed precipitation into daily totals.

### 3a. ObservationResolver

Update `computeDailyExtremes()` to sum `precipAmountMm` from hourly observations into `DailyExtremeEntity.precipAmountMm`.

**File:** `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`

### 3b. DailyActual

Add `precipAmountMm: Float?` to the `DailyActual` data class.

**File:** `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`

Update `extremesToDailyActuals()` and `extremesToDailyActualsBySource()` to map the new field.

### Tests needed:
- Unit test: `computeDailyExtremes` sums hourly precip into daily total
- Unit test: `extremesToDailyActuals` maps precipAmountMm

## Phase 4: Display

Show observed rain amounts for past days.

### 4a. DailyViewLogic

Update `buildDailyRainLabel()` to handle past dates — show observed amount instead of returning null.

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` (lines 616-661)

- Remove `if (isPastDate) { return null }` guard
- For past days, format `dailyActual.precipAmountMm` using existing `formatPrecipAmount()` from `WidgetFormatUtils.kt`
- No probability threshold for actuals — show amount regardless

Update `buildNightRainLabel()` similarly (lines 663-692).

### 4b. DailyForecastRainLabelRenderer

Should work without changes — it renders whatever text is in `rainData.dailyRainLabelText`.

### Tests needed:
- Unit test: `buildDailyRainLabel` returns formatted amount for past day with observed precip
- Unit test: `buildDailyRainLabel` returns null for past day with zero/null precip
- Unit test: `buildNightRainLabel` returns formatted amount for past night

## API Precipitation Support Summary

| API | Observed Precip Source | Status |
|-----|----------------------|--------|
| Open-Meteo | `precipitation_sum` in daily history (already fetched) | Needs pass-through |
| WeatherAPI | `precip_mm` in hourly history (already fetched) | Needs pass-through |
| NWS | `precipitationLastHour` in observations | Needs parsing |
| Silurian | Hourly history `precipAmountMm` (already fetched) | Needs pass-through |

## Key Utility

- `WidgetFormatUtils.formatPrecipAmount(mm: Float): String` — already handles US/imperial vs metric formatting

## Verification

1. `./gradlew testDebugUnitTest` — all unit tests pass
2. `./scripts/emulator-tests.sh -c com.weatherwidget.data.local.WeatherDatabaseMigrationTest` — migration test passes
3. `./gradlew installDebug` — verify on emulator/device with past days showing rain amounts
