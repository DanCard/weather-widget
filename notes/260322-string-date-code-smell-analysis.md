# String Date Code Smell Analysis
*2026-03-22*

## Background

After migrating DB entity fields from `String` to `Long` (epoch millis) in migration 40→41, a follow-up audit
identified remaining places in the codebase where dates are stored or passed as `String` inside the app.

---

## Legitimate String Dates (API Boundaries — Leave As-Is)

These are JSON deserialization DTOs. The JSON payload contains strings like `"2026-03-22"`, so the Kotlin
model must match. They get converted to epoch millis immediately when creating `ForecastEntity`.

| Location | Field | Reason |
|----------|-------|--------|
| `OpenMeteoApi.DailyForecast` | `date: String` | JSON deserialization |
| `WeatherApi.DailyForecast` | `date: String` | JSON deserialization |
| `SilurianApi.DailyForecast` | `date: String` | JSON deserialization |
| `WeatherApi.getHistory(date: String)` | parameter | Passed verbatim in HTTP URL |

---

## Genuine Code Smells — String Used as a Date Key Inside the App

### 1. Root Cause: `DailyActual.date: String` and `DailyActualMap = Map<String, DailyActual>`

**File**: `ObservationResolver.kt`

```kotlin
data class DailyActual(val date: String, ...)
typealias DailyActualMap = Map<String, DailyActual>
typealias DailyActualsBySource = Map<String, DailyActualMap>
```

This String key (`"2026-03-22"`) propagates into ~10 files. Every lookup like `dailyActuals[todayStr]`
depends on ISO format matching exactly. This is the root of the smell — fixing it here fixes all downstream.

**Fix**: `DailyActual.date: LocalDate`, `DailyActualMap = Map<LocalDate, DailyActual>`

---

### 2. `forecastSnapshots: Map<String, List<ForecastEntity>>`

**Files**: `DailyViewHandler`, `DailyViewLogic`, `WeatherWidgetWorker`, `WidgetIntentRouter`

The String key is reconstructed from epoch millis on every call:
```kotlin
.groupBy { LocalDate.ofEpochDay(it.targetDate / 86400_000L).toString() }
```
This conversion exists only because the downstream map uses String. Removing it would also eliminate the
`.toString()` round-trips scattered through the widget handlers.

**Fix**: `Map<LocalDate, List<ForecastEntity>>`

---

### 3. `weatherByDate: Map<String, ForecastEntity>`

**Files**: `DailyViewHandler`, `DailyViewLogic`

Same pattern. Key built with `today.format(ISO_LOCAL_DATE)`, lookups use `weatherByDate[todayStr]`.

**Fix**: `Map<LocalDate, ForecastEntity>`

---

### 4. Internal NWS Processing Maps in `ForecastRepository`

Short-lived `MutableMap<String, ...>` used during NWS period parsing:
```kotlin
val temperatureMap = mutableMapOf<String, Pair<Float?, Float?>>()
val conditionMap = mutableMapOf<String, String>()
val precipProbabilityMap = mutableMapOf<String, Int>()
// etc.
```
Keyed by date strings extracted from NWS period `startTime`, then converted back to epoch millis when
building `ForecastEntity`. These are internal and short-lived, but still fragile.

**Fix**: `MutableMap<LocalDate, ...>` (convert from `startTime` string once at entry point)

---

### 5. Stats/Display DTOs

| Field | Current | Suggested Fix |
|-------|---------|---------------|
| `DailyAccuracy.date` | `String` | `LocalDate` (display layer calls `.toString()`) |
| `ComparisonStatistics.periodStart` | `String` | `LocalDate` |
| `ComparisonStatistics.periodEnd` | `String` | `LocalDate` |
| `DailyForecastGraphRenderer.DayData.date` | `String` | `LocalDate` |
| `DailyForecastGraphRenderer.BarDrawnDebug.date` | `String` | `LocalDate` (debug only) |

---

## Summary Table

| Location | Current Type | Recommended Type |
|----------|-------------|-----------------|
| `DailyActual.date` | `String` | `LocalDate` |
| `DailyActualMap` | `Map<String, DailyActual>` | `Map<LocalDate, DailyActual>` |
| `DailyActualsBySource` | `Map<String, DailyActualMap>` | `Map<String, Map<LocalDate, DailyActual>>` (source key stays String) |
| `forecastSnapshots` | `Map<String, List<ForecastEntity>>` | `Map<LocalDate, List<ForecastEntity>>` |
| `weatherByDate` | `Map<String, ForecastEntity>` | `Map<LocalDate, ForecastEntity>` |
| NWS processing maps | `MutableMap<String, ...>` | `MutableMap<LocalDate, ...>` |
| `DailyAccuracy.date` | `String` | `LocalDate` |
| `ComparisonStatistics.periodStart/End` | `String` | `LocalDate` |
| `DailyForecastGraphRenderer.DayData.date` | `String` | `LocalDate` |
| API response DTOs | `String` | Leave as-is (JSON boundary) |

---

## Recommended Approach

The highest-value fix is changing `DailyActualMap` to `Map<LocalDate, DailyActual>`. That single change
eliminates the ISO format dependency for all daily actuals lookups throughout the widget handlers. The
`forecastSnapshots` and `weatherByDate` maps follow naturally — once the lookup key type is `LocalDate`,
the round-trip `.toString()` / `LocalDate.parse()` conversions disappear.

The NWS processing maps and stats DTOs are lower priority — they are either short-lived or display-only.
