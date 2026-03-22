# Automated Test Plan: Hourly Actual Temperature History

**Date:** 2026-03-16
**Feature:** Solid-line actual temperature history vs dashed forecast on hourly temperature graph

---

## Overview

Three test layers, each validating a distinct concern:

| Layer | File | Framework |
|-------|------|-----------|
| DAO | `HourlyActualDaoTest.kt` | Robolectric + Room in-memory |
| `buildHourDataList` | `TemperatureViewHandlerActualsTest.kt` | JUnit (pure) |
| `renderGraph` | `TemperatureGraphRendererActualsTest.kt` | MockK Canvas |

---

## Layer 1 — `HourlyActualDao`

**File:** `app/src/test/java/com/weatherwidget/data/local/HourlyActualDaoTest.kt`
**Pattern:** `@RunWith(RobolectricTestRunner::class)` + `TestDatabase.create()` (mirrors `HourlyForecastDaoTest`)

```kotlin
@RunWith(RobolectricTestRunner::class)
class HourlyActualDaoTest {
    private lateinit var db: WeatherDatabase
    private lateinit var dao: HourlyActualDao

    @Before fun setup() { db = TestDatabase.create(); dao = db.hourlyActualDao() }
    @After  fun tearDown() { db.close() }
```

### Test Cases

#### 1.1 `getActualsInRange` — basic range query
Insert actuals at T-3h, T-1h, T+1h. Query `[T-2h, T]`. Expect exactly the T-1h row.

#### 1.2 Range bounds are inclusive
Insert actuals at exactly `startDateTime` and `endDateTime`. Both appear in result.

#### 1.3 Source filter
Insert identical dateTime for source="NWS" and source="OPEN_METEO". Query for "NWS". Only NWS row returned.

#### 1.4 Lat/lon proximity (±0.01 tolerance)
Insert actual at `(37.42, -122.08)`. Query with `(37.425, -122.075)` (within tolerance). Row returned.
Query with `(37.50, -122.08)` (outside tolerance). Empty result.

*(Check the actual tolerance used in the DAO query — mirror the `HourlyForecastDao` proximity constant.)*

#### 1.5 Results ordered by dateTime ASC
Insert three actuals out of order. Verify result list order matches ascending dateTime.

#### 1.6 Composite primary key — upsert replaces on conflict
Insert actual at T with temperature=68°. Re-insert same key with temperature=72°. Query returns single row with temperature=72°.

#### 1.7 `deleteOldActuals` removes rows before cutoff
Insert actuals with `fetchedAt` = yesterday and today. Call `deleteOldActuals(cutoffTime = midnight_today)`. Only today's row remains.

#### 1.8 Empty table — range query returns empty list (no crash)

---

## Layer 2 — `buildHourDataList` with actuals

**File:** `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureViewHandlerActualsTest.kt`
**Access:** `TemperatureViewHandler.buildHourDataList(...)` (marked `@VisibleForTesting internal`)
**Pattern:** Plain JUnit, no mocking needed — function is pure given fixed inputs.

Helper to build a minimal `HourlyActualEntity`:
```kotlin
fun actual(dateTime: String, temp: Float = 65f, source: String = "NWS") = HourlyActualEntity(
    dateTime = dateTime,
    locationLat = 37.42,
    locationLon = -122.08,
    temperature = temp,
    condition = "Clear",
    source = source,
    fetchedAt = System.currentTimeMillis(),
)
```

### Test Cases

#### 2.1 Actual matched by dateTime key sets `isActual = true` and `actualTemperature`
Build forecasts for hours 10..14. Insert actual for hour 12 at 68°.
The HourData for 12:00 must have `isActual=true`, `actualTemperature=68f`.
Adjacent hours must have `isActual=false`, `actualTemperature=null`.

#### 2.2 No actuals → all `isActual = false`
Call `buildHourDataList(..., actuals = emptyList())`.
All HourData have `isActual=false` and `actualTemperature=null`.

#### 2.3 Forecast temperature (`temperature` field) unaffected by actuals
Even when an actual exists for an hour, `HourData.temperature` must still equal the forecast value — not the actual. (The forecast drives the dashed line; actuals drive the solid line.)

#### 2.4 Source mismatch — actual for wrong source not matched
Insert actual with source="OPEN_METEO". Call `buildHourDataList` with `displaySource=WeatherSource.NWS`.
No HourData should have `isActual=true`.
*(Verify whether `buildHourDataList` filters by source or passes all actuals — check the implementation first and test accordingly.)*

#### 2.5 WIDE zoom covers more hours than NARROW
With the same centerTime, WIDE result has more HourData entries than NARROW.
`ZoomLevel.WIDE` back=8/forward=16 ≥ `ZoomLevel.NARROW` back=2/forward=2.

#### 2.6 Future hours never have `isActual = true`
Insert actuals for hours well in the future. Resulting HourData for those hours must still have `isActual=false`. *(Unless implementation intentionally allows it — verify.)*

---

## Layer 3 — `renderGraph` solid/dashed split

**File:** `app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererActualsTest.kt`
**Pattern:** Mirrors `TemperatureGraphRendererFetchDotTest` — `mockkConstructor(Canvas::class)`, count `drawPath` calls.

The renderer draws:
- `drawPath` × 2 for the **fill + curve of the actual/forecast composite** (when no actuals: forecast-only fill + curve)
- Additional `drawPath` calls for the **dashed forecast overlay** when actuals are present (solid past + dashed full-width)
- `drawPath` × 2 more when ghost/delta line is visible

Count `drawPath` calls to distinguish rendering modes.

### Test Cases

#### 3.1 No actuals → no dashed overlay (all-forecast mode)
Build hours with all `isActual=false`. Provide `isCurrentHour=true` on one hour (to make NOW visible, so ghost line renders if delta active).
`renderGraph` with no `appliedDelta`. Expect 2 `drawPath` calls (fill + curve).
This matches the pre-feature baseline behavior.

#### 3.2 With actuals → additional dashed path drawn
Build hours where first 4 are `isActual=true`. Expect `drawPath` count > 2:
- 1 fill (actual/forecast composite)
- 1 solid curve (clipped to past)
- 1 dashed curve (full width forecast overlay)

Verify count equals 3 (or whatever the implementation draws — inspect `renderGraph` to confirm exact count, then pin it).

#### 3.3 `transitionX` is null when no actuals — no clip-based rendering
This is tested implicitly via 3.1: if no actuals, `lastActualIndex = -1`, `transitionX = null`. The solid line should not be clipped. Verify only 2 `drawPath` calls (no extra dashed path).

#### 3.4 Dot drawn on actual curve — y position consistency
*(This partially overlaps with `TemperatureGraphRendererFetchDotTest` which already covers dot presence/absence. Only add a new test if there's a distinct behavioral contract to verify, e.g. dot appears when actuals exist even without `observedAt`.)*

#### 3.5 Ghost line only when NOW indicator visible
Existing test `renderGraph does not draw ghost line when now indicator is not visible` already covers this. **No new test needed.**

---

## Layer 4 — Integration / Robolectric (handler level)

**File:** `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureActualsNavigationRoboTest.kt`
**Pattern:** `@RunWith(RobolectricTestRunner::class)` + `TestDatabase.create()` + mock `AppWidgetManager`

These tests catch the class of bug fixed this session (repository threading, zoom reset) at the integration boundary.

### Test Cases

#### 4.1 Navigation preserves actuals (repository not dropped)
Set up DB with hourly forecasts + actuals for current hour window.
Call `TemperatureViewHandler.updateWidget(...)` — record `FetchDotDebug.lastActualIndex`.
Simulate navigation tap (call `handleGraphNavigation` with `isLeft=true`).
Expect `lastActualIndex >= 0` on the resulting render — i.e., actuals still visible after navigation.

*(This requires either exposing `lastActualIndex` via `FetchDotDebug` or checking an observable proxy such as the actual count in the rendered `HourData`.)*

#### 4.2 `handleSetView` resets zoom to WIDE when switching from DAILY to TEMPERATURE
Use `WidgetStateManager` to set zoom=NARROW on `appWidgetId`.
Call the intent handling path for `ACTION_SET_VIEW` with `targetMode=TEMPERATURE`.
Read back `stateManager.getZoomLevel(appWidgetId)`.
Expect `ZoomLevel.WIDE`.

#### 4.3 `seedActualsFromObservationsIfNeeded` — seeds from existing observations on first call
Insert 3 `ObservationEntity` rows into DB.
Insert 0 `HourlyActualEntity` rows (empty actuals table for this location/source).
Call `seedActualsFromObservationsIfNeeded(lat, lon)` via `CurrentTempRepository`.
Query `hourlyActualDao.getActualsInRange(...)` for that window.
Expect 3 rows, each with source="NWS".

#### 4.4 `seedActualsFromObservationsIfNeeded` — does NOT re-seed when actuals already exist
Pre-populate `hourly_actuals` with 1 NWS row for this location.
Call `seedActualsFromObservationsIfNeeded` again.
Insert 3 more observations.
Expect actuals count still = 1 (seed was skipped, no duplicate conversion).

---

## `TestData` Factory Addition

Add to `TestData.kt`:
```kotlin
fun hourlyActual(
    dateTime: String = "2026-02-20T12:00",
    temperature: Float = 65f,
    source: String = "NWS",
    lat: Double = LAT,
    lon: Double = LON,
    fetchedAt: Long = System.currentTimeMillis(),
) = HourlyActualEntity(
    dateTime = dateTime,
    locationLat = lat,
    locationLon = lon,
    temperature = temperature,
    condition = "Clear",
    source = source,
    fetchedAt = fetchedAt,
)
```

---

## Implementation Order

1. Add `TestData.hourlyActual()` factory
2. `HourlyActualDaoTest` (Layer 1) — isolated, no renderer/handler deps
3. `TemperatureViewHandlerActualsTest` (Layer 2) — pure logic
4. `TemperatureGraphRendererActualsTest` (Layer 3) — pin current drawPath counts
5. `TemperatureActualsNavigationRoboTest` (Layer 4) — integration, most complex

Run after each layer: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest`
