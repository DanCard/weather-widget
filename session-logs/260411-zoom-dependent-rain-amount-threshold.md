# Session: Zoom-Dependent Rain Amount Threshold for Precipitation Graph

**Date:** 2026-04-11
**Goal:** Lower the threshold for showing rain amount annotations in the precipitation graph from 99% to 97% when in NARROW (zoomed-in) view
**Scope:** PrecipitationGraphRenderer.kt, PrecipViewHandler.kt

---

## Prompt 1: Compare NWS API Rain Forecast with App Display

> Can you pull the hourly daily for nws rain forecast percent directly from api? I'm wondering if it matches what the app says.

### Investigation

1. **Identified location** from shared preferences: 37.422, -122.0841 (Mountain View, CA), NWS gridpoint MTR/93,87.

2. **Queried NWS API directly:**
   - Daily forecast endpoint: `https://api.weather.gov/gridpoints/MTR/93,87/forecast`
   - Hourly forecast endpoint: `https://api.weather.gov/gridpoints/MTR/93,87/forecast/hourly`

3. **Pulled app database** from device via `adb shell run-as com.weatherwidget cat /data/data/com.weatherwidget/databases/weather_database` and queried the `hourly_forecasts` and `forecasts` tables.

### Findings

**Hourly PoP values match exactly.** Example comparison for April 11:

| Hour (local) | API PoP | App DB PoP | Match? |
|------------|--------|------------|--------|
| 09:00 | 35% | 35% | Yes |
| 10:00 | 37% | 37% | Yes |
| 15:00 | 56% | 56% | Yes |
| 16:00 | 80% | 80% | Yes |
| 19:00 | 97% | 97% | Yes |

**Daily PoP values mostly match** but there's a notable discrepancy for Sunday Apr 12:
- API says 81% (daytime period)
- App DB shows 14% (appears to be picking up the nighttime 22% value)
- This is a pre-existing merge logic issue, not part of this session's work.

**Key observation:** The 97% value at 19:00 is right on the edge of the 99% threshold for showing rain amount annotations. This prompted the next discussion.

---

## Prompt 2: Change Threshold for Rain Amount Display

> For hourly rain chance graph, zoomed in view, can we change the threshold for showing rain amount? Lower it to 97%

### Discussion

Identified two locations using `>= 99`:
1. `PrecipitationGraphRenderer.kt:804,807` — `findHighProbRainPeriods()` in the precipitation graph (hourly view)
2. `DailyViewLogic.kt:411` — daily view rain label formatting

The user clarified they only want the change in the **hourly precipitation graph zoomed-in (NARROW) view**, not the daily view, and not globally.

### Design Decision

- **NARROW zoom**: threshold = 97% (show rain amounts for periods with probability >= 97%)
- **WIDE zoom**: threshold = 99% (unchanged behavior, reducing visual noise in wider view)
- Zoom level is already available in `PrecipViewHandler` and passed as `smoothIterations` to the renderer, but not as a typed enum.

### Implementation Approach

Add a `highProbThreshold: Int = 99` parameter to `PrecipitationGraphRenderer.renderGraph()` with a default value so all 23 existing test callers work unchanged. Only the production caller in `PrecipViewHandler` computes and passes the zoom-dependent value.

---

## Prompt 3: Implement the Change

> proceed with the zoom-dependent approach (97 for NARROW, 99 for WIDE)

### Changes Made

#### 1. `PrecipitationGraphRenderer.kt`

**`renderGraph()` signature** (line 71) — Added parameter:
```kotlin
highProbThreshold: Int = 99,
```

**Comment** (line 543) — Updated from:
```
// --- Rain amount annotations for 99%+ probability periods ---
```
to:
```
// --- Rain amount annotations for high probability periods (97%+ in NARROW, 99%+ in WIDE) ---
```

**`findHighProbRainPeriods()` call** (line 552) — Changed from:
```kotlin
val rainPeriods = findHighProbRainPeriods(hours)
```
to:
```kotlin
val rainPeriods = findHighProbRainPeriods(hours, highProbThreshold)
```

**`findHighProbRainPeriods()` method** (line 800) — Changed signature from:
```kotlin
private fun findHighProbRainPeriods(hours: List<PrecipHourData>): List<RainPeriod> {
```
to:
```kotlin
private fun findHighProbRainPeriods(hours: List<PrecipHourData>, highProbThreshold: Int = 99): List<RainPeriod> {
```

Both `>= 99` comparisons (lines 804, 807) changed to `>= highProbThreshold`.

#### 2. `PrecipViewHandler.kt`

Added threshold computation before `renderGraph()` call (after line 251):
```kotlin
val highProbThreshold = if (zoom == com.weatherwidget.widget.ZoomLevel.NARROW) 97 else 99
```

Added `highProbThreshold` argument to the `renderGraph()` call:
```kotlin
observedAt = observedAt,
highProbThreshold = highProbThreshold
```

#### 3. No Test Changes

All 23 existing test callers of `PrecipitationGraphRenderer.renderGraph()` use the default parameter value of 99, preserving current behavior. No test modifications required.

### Verification

- `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL
- `./gradlew testDebugUnitTest` — All tests pass, no regressions

### Plan Written

`plans/260411-zoom-dependent-rain-amount-threshold.md`

---

## Summary of All Changes

### Production Code (2 files)

| File | Change |
|------|--------|
| `PrecipitationGraphRenderer.kt` | Added `highProbThreshold: Int = 99` param to `renderGraph()`, passed it through to `findHighProbRainPeriods()`, replaced two `>= 99` checks with `>= highProbThreshold`, updated comment |
| `PrecipViewHandler.kt` | Added `highProbThreshold` computation from zoom level, passed it to `renderGraph()` |

### Test Code

No changes required — default parameter value preserves existing behavior.

### Key Design Decisions

1. **Zoom-dependent threshold** — 97% for NARROW (±2 hours), 99% for WIDE (±12 hours). NARROW has fewer hours so more annotations won't clutter; WIDE keeps the conservative threshold to reduce noise.
2. **Default parameter** — `highProbThreshold: Int = 99` ensures all existing callers (23 test sites + future callers) default to the original behavior.
3. **No daily view change** — The `DailyViewLogic.kt:411` `>= 99` check remains unchanged per user's explicit request to only change the hourly graph.
4. **Method-level default on `findHighProbRainPeriods`** — Also given `highProbThreshold: Int = 99` default for safety, even though all call sites go through `renderGraph()`.