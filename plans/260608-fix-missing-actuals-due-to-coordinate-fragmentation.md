# Plan: Fix Missing Actual Temperatures due to Coordinate Precision Fragmentation

This plan addresses the issue where past days' actual temperature bars and labels are missing on the emulator's daily forecast widget.

## 1. Root Cause Analysis
- **Database Queries**: Database entries for daily extremes (`daily_extremes` table) and forecast entries (`forecasts` table) are queried using `LocationMatch.ROOM_WHERE`, which safely queries coordinates within a tolerance range of `0.1` degrees to handle geocoding jitter.
- **In-Memory Filtering**: After the SQLite queries return the records:
  - `ObservationResolver.extremesToDailyActualsBySource()` filters the records using strict coordinate equality: `it.locationLat == lat && it.locationLon == lon`.
  - `WidgetRenderer.kt` filters now-centered hourly forecasts using strict equality: `row.locationLat == locationLat && row.locationLon == locationLon`.
- **Siloing/Discarding**: If the database entries were saved under a slightly different precision (e.g. `37.422` vs `37.4168`), the strict equality checks silently filter them out.
- **Visual Impact**: Since past days' actuals are discarded, the widget daily graph renderer receives null high/low temperatures for past days, resulting in missing actual temperature bars and missing text labels.

---

## 2. Proposed Changes

### 2.1 Update [ObservationResolver.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt)
- Use the proximity box check (`LocationMatch.TOLERANCE_DEG`) instead of strict double equality.
- Update `sourceExtremesToDailyActualMap` to accept target `lat` and `lon`, group entities by date, and select the record closest to the target coordinate (calculated using `TempUtils.distanceSq`).

### 2.2 Update [WidgetRenderer.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt)
- Update the filtering of `nowCenteredHourlyForecasts` to use the proximity box check (`LocationMatch.TOLERANCE_DEG`) rather than strict equality.

---

## 3. Implementation Details

### File: [ObservationResolver.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt)
- Re-write the coordinate filtering in `extremesToDailyActualsBySource` and `sourceExtremesToDailyActualMap`:
```kotlin
    fun extremesToDailyActualsBySource(
        extremes: List<DailyExtremeEntity>,
        lat: Double,
        lon: Double,
    ): DailyActualsBySource {
        val local = ZoneId.systemDefault()
        return extremes
            .filter {
                Math.abs(it.locationLat - lat) <= com.weatherwidget.data.local.LocationMatch.TOLERANCE_DEG &&
                    Math.abs(it.locationLon - lon) <= com.weatherwidget.data.local.LocationMatch.TOLERANCE_DEG
            }
            .groupBy { it.source }
            .mapValues { (_, sourceEntities) ->
                sourceExtremesToDailyActualMap(sourceEntities, lat, lon, local)
            }
    }

    private fun sourceExtremesToDailyActualMap(
        entities: List<DailyExtremeEntity>,
        lat: Double,
        lon: Double,
        local: ZoneId,
    ): DailyActualMap {
        return entities
            .groupBy { it.toDailyExtreme().toLocalDate() }
            .mapValues { (_, dateEntities) ->
                dateEntities.minBy {
                    com.weatherwidget.shared.util.TempUtils.distanceSq(it.locationLat, it.locationLon, lat, lon)
                }.toDailyExtreme()
            }
    }
```

### File: [WidgetRenderer.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt)
- Re-write the filtering of `nowCenteredHourlyForecasts`:
```kotlin
        val nowCenteredHourlyForecasts = hourlyForecasts.filter { row ->
            Math.abs(row.locationLat - locationLat) <= com.weatherwidget.data.local.LocationMatch.TOLERANCE_DEG &&
                Math.abs(row.locationLon - locationLon) <= com.weatherwidget.data.local.LocationMatch.TOLERANCE_DEG &&
                row.dateTime in nowMinEpoch..nowMaxEpoch
        }
```

---

## 4. Test Plan & Verification

### 4.1 Unit Tests
- Execute all unit tests in the `:shared` and `:app` modules to ensure no regressions:
  ```bash
  ./gradlew test
  ```
- Specifically check `ObservationResolverTest.kt` to verify that `extremesToDailyActualsBySource` behaves correctly.

### 4.2 Instrumented / Emulator Tests
- Run the emulator tests suite:
  ```bash
  ./scripts/emulator-tests.sh
  ```

### 4.3 Visual Verification on Emulator
- Force-refresh the widget on the emulator or wait for a update cycle.
- Take a screenshot of the widget on the emulator.
- Verify that Sat and Sun (past days) now show:
  1. Red actual temperature bar segments (or appropriate history bars).
  2. High/low temperature labels (e.g. `72°` and `54°`) above/below the history columns.
