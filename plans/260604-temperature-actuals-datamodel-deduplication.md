# Temperature Actuals Deduplication Plan

## Objective
Unify the data models and calculation logic for "Daily Extremes" (temperature actuals) across the Android and Desktop platforms into the `:shared` module. This ensures algorithmic parity in forecast accuracy tracking and simplifies future maintenance.

## Background
Currently, both platforms have identical field structures for daily extremes but use platform-specific classes and aggregation logic:
- **Android:** `DailyExtremeEntity` (Room) + `ObservationResolver` logic (uses complex IDW blending via `ObservationBlender`).
- **Desktop:** `DesktopDailyExtremeEntity` + `DailyExtremesComputer` logic (uses simple per-station max/min).

We will move to a unified model and logic in `:shared`.

## Proposed Solution: Unification in `:shared`

### 1. Unified Data Model
Create `com.weatherwidget.data.model.DailyExtreme` in the `:shared` module. This will be a pure data class (no Room/DB annotations).

```kotlin
data class DailyExtreme(
    val date: Long,
    val source: String,
    val locationLat: Double,
    val locationLon: Double,
    val highTemp: Float,
    val lowTemp: Float,
    val condition: String,
    val updatedAt: Long,
    val precipAmountMm: Float? = null,
    val precipDayMm: Float? = null,
    val precipNightMm: Float? = null,
)
```

### 2. Unified Calculation Logic: `ActualsAggregator`
Create `com.weatherwidget.shared.actuals.ActualsAggregator` in the `:shared` module.
- It will port the superior "IDW Blending" logic from Android's `ObservationResolver`.
- It will operate on shared models (`ObservationReading`, `HourlyForecast`).
- It will produce `DailyExtreme` objects.

### 3. Refactor Android (`app` module)
- **Model:** Update `DailyExtremeEntity` to wrap or map to the shared `DailyExtreme` model.
- **Logic:** Replace the heavy lifting in `ObservationResolver` with calls to the shared `ActualsAggregator`.
- **Mappers:** Add `toDailyExtreme()` and `fromDailyExtreme()` extensions to `DailyExtremeEntity`.

### 4. Refactor Desktop (`desktop` module)
- **Model:** Delete `DesktopDailyExtremeEntity` and use the shared `DailyExtreme` (mapped to its local SQLite schema).
- **Logic:** Delete `DailyExtremesComputer` and use the shared `ActualsAggregator`.
- **Consistency:** This will upgrade the desktop app from "simple max/min" to the more accurate "IDW blended extremes" currently used on Android.

## Implementation Steps
1.  **Shared Model:** Create `DailyExtreme.kt` in `:shared`.
2.  **Shared Logic:** 
    - Move `DailyPrecip` and precipitation splitting logic to `:shared`.
    - Implement `ActualsAggregator.computeDailyExtremes()` using the IDW blending logic.
3.  **Android Refactor:**
    - Update `ObservationResolver` imports and implementation.
    - Update `DailyExtremeEntity` and its DAO usage.
4.  **Desktop Refactor:**
    - Update `DesktopWeatherRepository` to use the new aggregator and shared model.
    - Remove `DailyExtremesComputer.kt`.
5.  **Test Consolidation:**
    - Move relevant unit tests from `ObservationResolverTest` and `DailyExtremesComputerTest` to a unified `ActualsAggregatorTest` in `:shared`.

## Verification & Testing
- **Shared Tests:** Ensure `ActualsAggregatorTest` covers both single-station and multi-station blending scenarios.
- **Android Integration:** Verify that widget "Actuals" labels and graph lines still render correctly.
- **Desktop Integration:** Verify that "Reliability Scores" are correctly calculated and persisted in the desktop database.
- **Regression:** Run `./gradlew test` on both modules.
