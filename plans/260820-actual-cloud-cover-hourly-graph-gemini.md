# Plan: Add Actual Cloud Cover % to Meteo Hourly Cloud Cover Graph (Option B - Observation Pipeline Parity)

**Target Platforms:** Android (`:app`) and Linux Desktop (`:desktop`)  
**Shared Code:** JVM Shared Weather & Graph Library (`:shared`)  
**Date:** 2026-08-20  
**Status:** Planned  

---

## 1. Overview & Objectives

Currently, the **Hourly Temperature Graph** visually distinguishes between observed actuals (past hours) and predicted weather (future hours):
- **Past Actuals:** Rendered as a solid curve driven by timestamped readings in the `observations` table.
- **Future Forecast:** Rendered as a dashed curve.
- **Current Observation ("Fetch Dot"):** An anchored point with concentric rings showing current observed reading, current value label (`71°`), and a staleness age badge (`15m`) in narrow zoom views.
- **Extrema & Labels:** Distinct placement for past actual extrema vs. future forecast extrema.

In contrast, the **Hourly Cloud Cover Graph** currently renders an uninterrupted solid curve with uniform gradient fill across the entire visible window (past and future), and does not display a current observed cloud cover fetch dot.

### Objective (Option B: Full Observation Pipeline Parity)
Upgrade the Hourly Cloud Cover Graph on **both Android and Linux Desktop** to mirror the exact architecture and visual conventions of the Hourly Temperature Graph:
1. **Database Parity:** Add `cloudCover INTEGER DEFAULT NULL` to the `observations` table (Room migration v61 → v62 on Android; SQLite schema update on Desktop) so point-in-time observed cloud cover is stored persistently alongside temperature and precipitation.
2. **Fetch & Parse Current Cloud Cover:** Request and parse real-time `cloud_cover` from Open-Meteo's `current` API block and record it in `observations`.
3. **Historical Backfill:** Update `HistoricalActualsBackfill` to carry `cloudCover` from past hourly reanalysis into synthetic `OPEN_METEO_MAIN` observation rows.
4. **Solid Actual vs. Dashed Forecast Curves:** Draw past cloud cover (from observations / past hourly reanalysis) as a solid curve and future predicted cloud cover as a dashed curve.
5. **Current Cloud Cover "Fetch Dot":** Render a dedicated observation dot at `(fetchDotX, fetchDotY)` displaying the current cloud cover percentage (e.g. `100%`) and staleness badge (e.g. `10m`) on narrow zoom spans (≤12h).
6. **Collision Avoidance & Parity:** Integrate the fetch dot into the label placement and obstacle registry to prevent overlapping text and maintain 100% dual-platform parity.

---

## 2. Evidence & Current State Analysis

### 2.1 Open-Meteo API Capabilities
* Open-Meteo's `/forecast` endpoint supports `current=temperature_2m,weather_code,cloud_cover`.
* Open-Meteo's hourly endpoint with `past_days` returns historical hourly `cloud_cover` (0–100%) for past hours.
* In `shared/src/main/kotlin/com/weatherwidget/data/remote/OpenMeteoApi.kt`:
  - Currently requests: `parameter("current", "temperature_2m,weather_code")`.
  - Does **not** yet request `cloud_cover` in the `current` parameter, and `CurrentReading` lacks a `cloudCover` property.

### 2.2 Database Schema (`observations` table)
* `observations` table on Android (`ObservationEntity.kt`) and Desktop (`weather.db`) stores `temperature` and `precipAmountMm`, but currently lacks `cloudCover`.
* `hourly_forecasts` already contains `cloudCover INTEGER`, so hourly forecast rows already store cloud cover.

### 2.3 Android Cloud Cover Graph (`app/.../widget/CloudCoverGraphRenderer.kt`)
* Draws a single `curvePath` (`canvas.drawPath(curvePath, paints.curvePaint)`) with no actual vs. forecast split.
* Renders `nowLine`, percent labels from `ValueLabelEngine`, and day/hour labels.
* No current observation dot or solid/dashed transition.

### 2.4 Desktop Cloud Cover Graph (`desktop/.../com/weatherwidget/desktop/CloudCoverGraph.kt`)
* Draws a single `buildCurve(coords)` path with `COLOR_CLOUD_CURVE` and `COLOR_CLOUD_FILL_START/END`.
* Renders `drawNowLine` and `ValueLabelEngine` placements.
* No distinction between historical actual points and future forecast points.

---

## 3. Detailed Architecture & Design

### Phase 1: Database Schema & Entity Updates (`:app` & `:shared`)

1. **Android Room Migration (v61 → v62):**
   - In `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`:
     - Bump `version = 62`.
     - Define `MIGRATION_61_62`:
       ```kotlin
       val MIGRATION_61_62 = object : Migration(61, 62) {
           override fun migrate(db: SupportSQLiteDatabase) {
               addColumnIfMissing(db, "observations", "cloudCover", "INTEGER")
           }
       }
       ```
     - Register `MIGRATION_61_62` in `.addMigrations(...)`.
   - Update `app/src/main/java/com/weatherwidget/data/local/ObservationEntity.kt`:
     ```kotlin
     data class ObservationEntity(
         ...
         val precipAmountMm: Float? = null,
         val cloudCover: Int? = null,
         val isWebFallback: Boolean = false,
         val qcFailed: Boolean = false,
     )
     ```
   - Update mapping functions `ObservationEntity.toReading()` and `ObservationReading.toEntity()`.

2. **Desktop SQLite Schema Update:**
   - In `desktop/.../DesktopWeatherDatabase.kt` (or migration runner):
     - Add `cloudCover INTEGER` to the `CREATE TABLE observations` statement.
     - Add `ALTER TABLE observations ADD COLUMN cloudCover INTEGER` migration check on startup.

3. **Shared Observation Models:**
   - In `shared/src/main/kotlin/com/weatherwidget/data/model/ForecastTypes.kt`:
     - Add `val cloudCover: Int? = null` to `ObservationReading`.

4. **Historical Backfill (`HistoricalActualsBackfill.kt`):**
   - In `shared/src/main/kotlin/com/weatherwidget/shared/actuals/HistoricalActualsBackfill.kt`:
     - Map `hour.cloudCover` to `ObservationReading.cloudCover` when generating backfill observation rows for past hours (`OPEN_METEO_MAIN`).

---

### Phase 2: Open-Meteo API Ingestion (`:shared`)

1. **Update `OpenMeteoApi.kt`:**
   - Add `cloud_cover` to the `current` parameter in `getForecast()` and `getCurrent()`:
     ```kotlin
     parameter("current", "temperature_2m,weather_code,cloud_cover")
     ```
   - Update `OpenMeteoApi.CurrentReading`:
     ```kotlin
     data class CurrentReading(
         val temperature: Float,
         val weatherCode: Int?,
         val observedAt: Long? = null,
         val cloudCover: Int? = null,
     )
     ```
   - Update `RawFetch` in `com.weatherwidget.data.model.ForecastTypes.kt`:
     - Add `providerCurrentCloudCover: Int? = null`.
   - Update `OpenMeteoApi.getForecast()` to parse `current["cloud_cover"]?.jsonPrimitive?.content?.toIntOrNull()` into `RawFetch.providerCurrentCloudCover`.

2. **Repository & Fetch Handlers:**
   - When saving latest observation from Open-Meteo `current` reading, populate `cloudCover` in the new `ObservationEntity` row.

---

### Phase 3: Android Widget Implementation (`:app`)

1. **`CloudCoverViewHandler.kt`:**
   - Extract `currentCloudCover` and `observedAt` from latest observation / fetch outcome.
   - Extract past actual cloud cover points from `observations` (and `hourly_forecasts`).
   - Pass `currentCloudCover`, `observedAt`, and updated `hours` list to `CloudCoverGraphRenderer.renderGraph()`.

2. **`CloudCoverGraphRenderer.kt` & `CloudCoverGraphStyle.kt`:**
   - **Paints:**
     - Add `actualCurvePaint` (solid curve for past hours, color `#CCCCCC` / `#DDC8CFD8`, stroke width matching style).
     - Add `forecastDashedPaint` (dashed stroke for future hours, `DashPathEffect(floatArrayOf(8.dp, 4.dp), 0f)`).
     - Add `fetchDotPaint`, `fetchDotRingPaint`, and `fetchDotTextPaint` (for current cloud cover % and staleness badge).
   - **Curve Splitting:**
     - Build `actualVisiblePoints` (points where `x <= transitionX`, anchored to `(fetchDotX, fetchDotY)`).
     - Build `forecastSegmentPaths` (points where `x >= transitionX`).
     - Render solid actual path + dashed forecast path.
   - **Fetch Dot & Current Value Placement:**
     - Calculate `(fetchDotX, fetchDotY)` mapping `currentCloudCover` to Y coordinate.
     - Draw concentric circle rings (outer dark ring, white ring, center fill).
     - Render current cloud percentage text (e.g. `100%`) with dark shadow layer.
     - Render staleness age label (e.g. `15m`) using `FetchDotLabel.formatAgeLabel(ageMinutes, spanHours)` for narrow spans (≤12h).
     - Register fetch dot bounding boxes into `drawnLabelBounds` so `ValueLabelEngine` and day labels avoid overlapping it.

---

### Phase 4: Desktop Linux Implementation (`:desktop`)

1. **`DesktopWeatherService.kt`:**
   - Store current observation with `cloudCover` in `observations` and `current_status`.
   - Forward `currentCloudCover` and `currentObservedAt` to desktop UI composables.

2. **`CloudCoverGraph.kt`:**
   - Receive `currentCloudCover: Int?` and `currentObservedAt: Long?` in `CloudCoverGraph(...)`.
   - Calculate `transitionMs = currentObservedAt ?: now`.
   - Separate coordinate sequence into:
     - **Past actual coordinates:** `coords.filter { it.timeMs <= transitionMs }` + anchor point at `transitionX`.
     - **Future forecast coordinates:** `coords.filter { it.timeMs >= transitionMs }`.
   - Draw past curve with solid stroke and future curve with `PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx() * scale, 4.dp.toPx() * scale))`.
   - Draw the current cloud cover **Fetch Dot** at `(fetchDotX, fetchDotY)` with concentric circles and measured text (`100%` + staleness label).
   - Pass fetch dot bounds to `drawnLabels` before invoking `ValueLabelEngine` placements.

---

## 4. Verification & Testing Plan

### 4.1 Room Migration & Database Tests
1. **`WeatherDatabaseMigrationTest.kt`:**
   - Add test verifying migration `61 → 62` correctly adds `cloudCover INTEGER` column to `observations` table.
   - Verify existing observation records remain intact after migration.
2. **Category:** `@Category(MediumDuration::class)` or `@Category(LongDuration::class)`.

### 4.2 Unit & Robolectric Tests
1. **`:shared` Unit Tests:**
   - `OpenMeteoApiTest`: Verify JSON response parsing extracts `providerCurrentCloudCover` from `current.cloud_cover`.
   - `HistoricalActualsBackfillTest`: Verify backfill rows carry `cloudCover`.
   - Duration Category: `@Category(ShortDuration::class)`.
2. **`:app` Robolectric & Unit Tests:**
   - `CloudCoverGraphRendererTest`: Verify curve splitting (solid actual vs dashed forecast) and fetch dot placement geometry.
   - `CloudCoverGraphLabelPlacementRobolectricTest`: Verify peak/dip labels avoid colliding with the fetch dot and staleness badge.
   - Duration Category: `@Category(ShortDuration::class)` / `@Category(MediumDuration::class)`.
3. **`:desktop` Unit Tests:**
   - Desktop graph rendering tests asserting geometry calculation, path splitting, and bounds intersection.

### 4.3 Automated & Device Verification
1. Run `./scripts/unit-tests.sh` to ensure all duration categories pass across all 3 modules (`:shared`, `:app`, `:desktop`).
2. Run `./scripts/emulator-tests.sh` on connected Android emulator.
3. Capture visual confirmation via `adb exec-out screencap` on emulator running Open-Meteo source in Cloud Cover view.
4. Launch desktop app (`./gradlew :desktop:run`) to visually confirm identical rendering of solid actual curve, dashed forecast curve, and fetch dot.

---

## 5. Summary of Files to Modify

| Module | File | Responsibility |
| :--- | :--- | :--- |
| `:app` | `app/.../data/local/WeatherDatabase.kt` | Room DB version 62 + `MIGRATION_61_62` |
| `:app` | `app/.../data/local/ObservationEntity.kt` | Add `cloudCover: Int?` to entity |
| `:app` | `app/src/androidTest/.../WeatherDatabaseMigrationTest.kt` | Test Room migration 61 → 62 |
| `:shared` | `shared/.../data/model/ForecastTypes.kt` | Add `cloudCover: Int?` to `ObservationReading` & `providerCurrentCloudCover` to `RawFetch` |
| `:shared` | `shared/.../data/remote/OpenMeteoApi.kt` | Query and parse `current.cloud_cover` |
| `:shared` | `shared/.../actuals/HistoricalActualsBackfill.kt` | Backfill `cloudCover` into synthetic observation rows |
| `:app` | `app/.../widget/CloudCoverGraphStyle.kt` | Add dashed/solid stroke and fetch dot paints |
| `:app` | `app/.../widget/CloudCoverGraphRenderer.kt` | Implement solid/dashed path splitting and fetch dot rendering |
| `:app` | `app/.../widget/handlers/CloudCoverViewHandler.kt` | Pass current cloud cover and timestamps to renderer |
| `:desktop` | `desktop/.../com/weatherwidget/desktop/CloudCoverGraph.kt` | Compose solid actual curve, dashed forecast curve, and fetch dot |
| `:desktop` | `desktop/.../com/weatherwidget/desktop/DesktopWeatherService.kt` | Provide current cloud observation state & DB migration |
