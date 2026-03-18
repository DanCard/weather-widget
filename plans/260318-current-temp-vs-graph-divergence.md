# Current Temp vs Hourly Graph Divergence

## Problem
The header current temperature and the hourly graph actuals curve show different values at the NOW position.

## The Two Pipelines

### Current temperature (top-left header)
1. `CurrentTempRepository.fetchNwsCurrent()` calls the **live NWS API** for up to 5 stations
2. IDW-blends the resulting observations via `SpatialInterpolator.interpolateIDW()` with `nowMs = System.currentTimeMillis()`
3. Stores the blended value in `CurrentTempEntity`
4. `CurrentTemperatureResolver.resolve()` **interpolates between hourly forecast values** as the base, then applies a **delta** (observed − estimated) that **decays to zero over 4 hours**
5. Displayed temp = `hourlyInterpolation + decayingDelta`

### Hourly graph actuals (the curve)
1. `WeatherRepository.getObservationsInRange()` reads **cached `ObservationEntity` rows** from SQLite
2. `blendObservationSeries()` in `TemperatureViewHandler` IDW-blends observations within ±15 min windows, using `primary.timestamp` as `nowMs` for the staleness check
3. Blended obs are injected as sub-hourly data points on the graph

## Sources of Divergence

### 1. Current temp is a blended forecast estimate, not a pure observation
The header doesn't display the raw IDW-blended observation directly. It shows:
```
estimatedTemp (hourly forecast interpolation) + decayingDelta
```
The delta anchors to the observation at fetch time and drifts back toward the forecast over 4 hours. Mid-cycle, the displayed temp is a **linear mix** of forecast and observation. The graph shows pure observations — these diverge from forecast-based interpolation by however wrong the forecast is.

### 2. Different staleness windows
- Current temp: `MAX_STALENESS_MS = 2h` in `SpatialInterpolator`, evaluated against **wall-clock now**
- Graph blending: also 2h staleness, but evaluated against `primary.timestamp` — an observation from 1.9h ago passes staleness on the graph even if it would fail in the live IDW blend
- `MAX_SPREAD_MS = 1h` keeps each cohort tight, but the two paths can end up blending different station subsets

### 3. Different station sets
- Current temp fetches the **latest** observation per station from the NWS API (fresh HTTP, up to 5 stations)
- The graph reads all observations in the time range from the DB, which may include older observations from a different cycle or from stations that weren't in the latest current-temp fetch

---

## Options

### Option A — Write the blended observation back to the DB *(recommended, minimal change)*
After computing `blendedTemp` in `fetchNwsCurrent()`, insert a synthetic `ObservationEntity` with `stationId = "NWS_IDW_BLEND"` into the DB at the current timestamp. The graph finds this blended point at the NOW position and displays it, matching the header.

**Change:** One extra `observationDao.insertAll()` call in `CurrentTempRepository.fetchNwsCurrent()`

**Pro:** Single-point fix; guarantees exact match at "now"; no renderer changes needed.
**Con:** Synthetic entity doesn't represent a real station, could confuse the stations display UI.

---

### Option B — Substitute current temp at the NOW line in the renderer
When building `HourData` at the current hour in `buildHourDataList()`, substitute `observedCurrentTemp` (already threaded through as `observedCurrentTemp` param in `updateWidget`) instead of the raw observation for the NOW slot.

**Change:** Pass `observedCurrentTemp` into `buildHourDataList()`; use it as `actualTemperature` for the current-hour slot.

**Pro:** Graph precisely tracks the header at NOW.
**Con:** Creates a visible discontinuity — the curve jumps to match the header at "now" then falls back to observations going backward.

---

### Option C — Anchor delta decay to observation timestamp, not fetch time
Currently the delta decays from `observedCurrentTempFetchedAt`. Instead anchor decay to the `observedAt` field (the NWS observation's own timestamp). This makes the header behave more like a point on the observation curve — both share the same temporal anchor.

**Change:** In `CurrentTemperatureResolver`, use `observedAt` instead of `observedCurrentTempFetchedAt` as the decay start. Requires threading `observedAt` through to the resolver (currently only `fetchedAt` is stored in `CurrentTempEntity`).

**Pro:** Makes header and graph share the same time reference.
**Con:** If the station reports hourly, decay effectively starts earlier, making the adjustment fade faster than intended.

---

### Option D — Have the graph read from `CurrentTempEntity` for the "now" slot
In `buildHourDataList()`, query `CurrentTempEntity` for the current hour and use its blended value as `actualTemperature` for the now slot only. Historical graph slots remain unchanged.

**Change:** Add a `currentTempEntity: CurrentTempEntity?` param to `buildHourDataList()`; use it when `time == currentHour`.

**Pro:** Header and graph NOW position always show the same value; historical actuals unaffected.
**Con:** Adds a DB dependency to `buildHourDataList`; requires passing the entity through the call chain.

---

### Option E — Remove the delta/decay mechanism; display observation directly *(largest change)*
Replace the current formula with: display `observedCurrentTemp` directly when fresh, fall back to `estimatedTemp` when stale. The graph already shows the observation; if the header shows the same value (no forecast mixing), they converge naturally.

**Change:** Significant refactor of `CurrentTemperatureResolver`; delta/decay logic removed.

**Pro:** Eliminates the fundamental source of divergence — one system, one value.
**Con:** Header temp would jump when a new observation arrives instead of smoothly interpolating between hours. The delta-decay was designed specifically to smooth those jumps.
