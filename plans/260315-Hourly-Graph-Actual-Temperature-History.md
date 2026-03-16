# Hourly Graph: Actual Temperature History with Solid/Dashed Differentiation

## Prompt
Show actual history in hourly temperature graph for all APIs.

## Context
The hourly temperature graph currently shows only forecast data as a single solid line for both past and future.
The "ghost line" (dashed white) only appears when there's an observed delta, approximating actuals.
We want to show **real NWS observation data** as the actual line for past hours, and visually differentiate actual (solid) from forecast (dashed).

## Approach

### 1. Add `observedTemperature` field to `HourData`
**File**: `TemperatureGraphRenderer.kt` (~line 13)
- Add `val observedTemperature: Float? = null` to `HourData`
- Past hours with observation data will have this populated; future hours will be `null`

### 2. Query observations and merge into HourData
**File**: `TemperatureViewHandler.kt` — `buildHourDataList()`
- Accept an additional `observations: List<ObservationEntity>` parameter
- For each past hour, find the closest observation within ±30min and set `observedTemperature`
- Caller already has access to `ObservationDao` via repository; query `getObservationsInRange()` for the graph's time window

### 3. Redesign graph rendering — two-segment approach
**File**: `TemperatureGraphRenderer.kt` — `renderGraph()`

**Remove**: Ghost line logic (`appliedDelta`, `expectedHours`, `ghostPaint`, fetch dot)

**New rendering**:
- **Past segment (before NOW)**:
  - If `observedTemperature` data exists: draw **solid** gradient line using observed temps
  - Forecast line for past hours: draw **dashed** gradient line (secondary, shows what was predicted)
- **Future segment (after NOW)**:
  - Draw **dashed** gradient line (forecast only — no observations exist yet)
- **Transition at NOW**: Both lines visible briefly where they overlap near the NOW indicator

**Line styles**:
- Solid line: existing `originalCurvePaint` (temperature gradient, current stroke width)
- Dashed line: same gradient shader but with `DashPathEffect` — similar to existing ghost paint but with color gradient instead of white

### 4. Build separate point arrays for actual vs forecast curves
- `actualPoints`: populated only for hours where `observedTemperature != null` (past)
- `forecastPoints`: all hours use `temperature` (the forecast value)
- Use existing `GraphRenderUtils.buildSmoothedPath()` for both curves
- Clip/split paths at the NOW x-coordinate so forecast line becomes dashed only in future

### 5. Update callers
**File**: `TemperatureViewHandler.kt` — where `renderGraph()` is called
- Query observations for the graph's time window using `observationDao.getObservationsInRange()`
- Pass observations into `buildHourDataList()`
- Remove `appliedDelta` parameter from `renderGraph()` call (no longer needed)

### 6. Ensure observation backfill covers graph window
**File**: `CurrentTempRepository.kt` — `backfillNwsObservationsIfNeeded()`
- Already fetches 48h of history — this covers the graph's past window (typically ~12-24h back)
- No changes needed here, but verify backfill runs before graph render in the update flow

## Key Files to Modify
1. `app/.../widget/TemperatureGraphRenderer.kt` — rendering logic (main changes)
2. `app/.../widget/handlers/TemperatureViewHandler.kt` — data assembly + caller
3. `app/.../widget/TemperatureGraphRenderer.kt` `HourData` — add observed temp field

## Files for Reference (no changes)
- `app/.../data/local/ObservationDao.kt` — `getObservationsInRange()` already exists
- `app/.../data/local/ObservationEntity.kt` — has timestamp + temperature fields
- `app/.../data/repository/CurrentTempRepository.kt` — backfill logic already in place
- `app/.../widget/GraphRenderUtils.kt` — `buildSmoothedPath()`, `computeNowX()`

## Verification
1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Check widget on device — past hours should show solid actual line, future hours dashed forecast
3. Scroll to historical view — should see solid actual line with dashed forecast overlay
4. Verify with fresh install (no observations yet) — should gracefully show forecast-only dashed line
5. Run unit tests: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest`
