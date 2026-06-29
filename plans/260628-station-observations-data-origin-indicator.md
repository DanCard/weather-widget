# Detailed Implementation Plan: Option B (isWebFallback Boolean)

This plan outlines the specific code changes required to implement type-safe, database-persisted indicators of the observation data source (NWS API vs. Synoptic Web API) on both Android and Desktop.

---

## Code Modifications

### 1. Unified Models (`:shared`)

#### [ForecastTypes.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/data/model/ForecastTypes.kt)
Add `val isWebFallback: Boolean = false` to `ObservationReading`:
```kotlin
data class ObservationReading(
    val stationId: String,
    val stationName: String,
    val timestamp: Long,
    val temperature: Float,
    val condition: String,
    val locationLat: Double,
    val locationLon: Double,
    val distanceKm: Float = 0f,
    val stationType: String = "UNKNOWN",
    val maxTempLast24h: Float? = null,
    val minTempLast24h: Float? = null,
    val api: String,
    val fetchedAt: Long = System.currentTimeMillis(),
    val precipAmountMm: Float? = null,
    val isWebFallback: Boolean = false, // <-- Added
)
```

#### [DesktopEntities.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopEntities.kt)
Add `val isWebFallback: Boolean = false` to `DesktopObservationEntity` and update mappings `toReading()` / `toEntity()`:
```kotlin
data class DesktopObservationEntity(
    val stationId: String,
    val stationName: String,
    val timestamp: Long,
    val temperature: Float,
    val condition: String,
    val locationLat: Double,
    val locationLon: Double,
    val distanceKm: Float = 0f,
    val stationType: String = "UNKNOWN",
    val fetchedAt: Long = System.currentTimeMillis(),
    val maxTempLast24h: Float? = null,
    val minTempLast24h: Float? = null,
    val api: String,
    val precipAmountMm: Float? = null,
    val isWebFallback: Boolean = false, // <-- Added
)
```

#### [DesktopWeatherDatabase.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDatabase.kt)
Update the table schema definition to include the column, and add dynamic table-alter logic on startup:
```kotlin
// Table creation
CREATE TABLE IF NOT EXISTS observations (
    ...
    isWebFallback INTEGER NOT NULL DEFAULT 0
)

// Dynamic Alter (Self-Healing)
addColumnIfMissing(db, "observations", "isWebFallback", "INTEGER NOT NULL DEFAULT 0")
```

#### [DesktopWeatherDao.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt)
Include `isWebFallback` in inserts, updates, and select mappings (read from ResultSet as `rs.getInt("isWebFallback") == 1`).

---

### 2. Android App Changes (`:app`)

#### [ObservationEntity.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/local/ObservationEntity.kt)
Add `val isWebFallback: Boolean` to the Room entity class, and update `toReading()` mapping.

#### [WeatherDatabase.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt)
Increment version from `48` to `49` and add `MIGRATION_48_49` to execute:
```kotlin
db.execSQL("ALTER TABLE `observations` ADD COLUMN `isWebFallback` INTEGER NOT NULL DEFAULT 0")
```

#### [ObservationRepository.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt)
- Update `buildObservationEntity` signature to accept `isWebFallback: Boolean = false` and map it.
- When calling `synopticApi.fetchSynopticObservations`, pass `isWebFallback = true` to `buildObservationEntity`.

---

### 3. Desktop Service Changes (`:desktop`)

#### [DesktopWeatherService.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt)
Update NWS API mapping to set `isWebFallback = false` and Synoptic fallback mapping to set `isWebFallback = true`.

---

### 4. UI Changes (Both Platforms)

#### [ObservationsWindow.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/ObservationsWindow.kt)
Modify the card display to append `(API)` or `(Web)` next to the station type (e.g. `OFFICIAL (Web)` or `OFFICIAL (API)`):
```kotlin
val originStr = if (obs.isWebFallback) "Web" else "API"
Text("${obs.stationId} • $distanceStr • $originStr", fontSize = 14.sp, color = ObsStyle.textSecondary)
```

#### [WeatherObservationsActivity.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/WeatherObservationsActivity.kt)
Modify Android's `ObservationAdapter` to render `(API)` or `(Web)` in the card subtitle.

---

## Verification Plan

1. Run `./gradlew test` to ensure all tests (both shared and platform-specific) pass.
2. Build Android debug app and run instrumented tests using `./scripts/emulator-tests.sh` to verify Room migration `48 -> 49` works successfully.
3. Rebuild and run Desktop App, verify taskbar temperature and observations screen list.
