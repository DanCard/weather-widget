# Hourly Graph: Actual Temperature History (Solid) vs Forecast (Dashed)

## Prompt
Show actual history in hourly temperature graph for all APIs.

## Context
The hourly graph shows only forecast data as a single solid line for both past and future.
We want to show **actual historical temperatures** as a solid line and **forecast** as a dashed line.
The transition is at the **last actual data point** (not the NOW line — observations can lag).
Ghost/delta line is preserved, starting from last actual point forward.

## Design Decisions
- **Line styles**: Actual = solid gradient, Forecast = dashed gradient (same temperature color scheme)
- **Transition point**: Last hour with actual data
- **Ghost line**: Preserved, starts from last actual data point forward
- **Storage**: New `hourly_actuals` table (DB migration 31→32)
- **All sources**: NWS, Open-Meteo, WeatherAPI.com, Silurian all provide actuals

## Step 1: New `HourlyActualEntity` + DAO

**New file**: `app/.../data/local/HourlyActualEntity.kt`
```kotlin
@Entity(tableName = "hourly_actuals",
    primaryKeys = ["dateTime", "source", "locationLat", "locationLon"],
    indices = [Index(value = ["locationLat", "locationLon"])])
data class HourlyActualEntity(
    val dateTime: String,        // "2024-01-15T14:00"
    val locationLat: Double,
    val locationLon: Double,
    val temperature: Float,      // Fahrenheit
    val condition: String,
    val source: String,          // "NWS", "OPEN_METEO", "WEATHER_API", "SILURIAN"
    val fetchedAt: Long,
)
```

**New file**: `app/.../data/local/HourlyActualDao.kt`
- `insertAll(actuals: List<HourlyActualEntity>)`
- `getActualsInRange(startDateTime: String, endDateTime: String, source: String, lat: Double, lon: Double)`
- `deleteOldActuals(cutoffDateTime: String)`

## Step 2: DB Migration 31→32

**File**: `app/.../data/local/WeatherDatabase.kt`
- Add `HourlyActualEntity` to entities list, bump to version 32
- Migration: `CREATE TABLE IF NOT EXISTS hourly_actuals (...)`

## Step 3: Populate actuals from each API source

### NWS
**File**: `app/.../data/repository/CurrentTempRepository.kt`
- In `backfillNwsObservationsIfNeeded()` and observation fetch paths: also insert into `hourly_actuals`
- Convert `ObservationEntity` (epoch timestamp) → hourly `dateTime` key, source = "NWS"

### Open-Meteo
**File**: `app/.../data/remote/OpenMeteoApi.kt`
- Change `parameter("past_days", 0)` → `parameter("past_days", 2)`

**File**: `app/.../data/repository/ForecastRepository.kt`
- When saving Open-Meteo hourly data: hours where `dateTime < now` → insert into `hourly_actuals`
- Hours where `dateTime >= now` → insert into `hourly_forecasts` (existing behavior)

### WeatherAPI.com
**File**: `app/.../data/remote/WeatherApi.kt`
- Add `getHistory(lat, lon, date: String)` method → `GET /v1/history.json?key=...&q=lat,lon&dt=date`
- Response format same as forecast (has `forecastday[].hour[]` array)

**File**: `app/.../data/repository/ForecastRepository.kt`
- Call `weatherApi.getHistory()` for yesterday + today → insert hourly data into `hourly_actuals`
- Piggyback on existing fetch cycle (don't add separate schedule)

### Silurian
- **Test empirically**: Check if their `/forecast/hourly` response already includes past hours
- If yes: split past hours into `hourly_actuals`, future into `hourly_forecasts`
- If no: fall back to NWS observations as actuals for Silurian source

## Step 4: Add `isActual` to `HourData` + populate in `buildHourDataList()`

**File**: `app/.../widget/TemperatureGraphRenderer.kt`
- Add `val isActual: Boolean = false` to `HourData`

**File**: `app/.../widget/handlers/TemperatureViewHandler.kt`
- `buildHourDataList()` accepts additional `actuals: List<HourlyActualEntity>` param
- For each hour: if matching actual exists, use its temperature and set `isActual = true`
- Caller queries `HourlyActualDao.getActualsInRange()` for the graph's time window

## Step 5: Split graph rendering

**File**: `app/.../widget/TemperatureGraphRenderer.kt` — `renderGraph()`

- Find `lastActualIndex` — last `HourData` where `isActual == true`
- **Actual segment** (0..lastActualIndex): Solid gradient paint (existing `originalCurvePaint`)
- **Forecast segment** (lastActualIndex..end): New dashed gradient paint (same shader + `DashPathEffect(floatArrayOf(dp(8), dp(4)), 0f)`)
- Both share the transition point for smooth visual connection
- **Ghost line**: starts from `lastActualIndex` forward only
- If no actuals exist (fresh install): entire line is dashed forecast

## Step 6: Cleanup — add actuals to retention sweep

- Add `hourlyActualDao.deleteOldActuals()` alongside existing `deleteOldObservations()` in scheduled cleanup

## Files to Modify
| File | Change |
|------|--------|
| `data/local/HourlyActualEntity.kt` | **New** |
| `data/local/HourlyActualDao.kt` | **New** |
| `data/local/WeatherDatabase.kt` | Entity + migration 31→32 |
| `data/remote/OpenMeteoApi.kt` | `past_days=2` |
| `data/remote/WeatherApi.kt` | Add `getHistory()` |
| `data/repository/ForecastRepository.kt` | Split actuals for OM + WeatherAPI + Silurian |
| `data/repository/CurrentTempRepository.kt` | Write NWS obs → actuals table |
| `widget/TemperatureGraphRenderer.kt` | `HourData.isActual`, split solid/dashed rendering |
| `widget/handlers/TemperatureViewHandler.kt` | Query actuals, pass to renderer |

## Verification
1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Widget: solid line for past (actuals), dashed for future (forecast)
3. Transition at last actual data point, not NOW line
4. Ghost line starts from transition point forward
5. Test all 4 API sources — each should show actuals
6. Fresh install: all-dashed (no actuals yet)
7. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest`
