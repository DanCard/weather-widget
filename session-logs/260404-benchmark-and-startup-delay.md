# 2026-04-04 Session Log: Temperature Graph Benchmark and Startup Delay Reduction

## Session Summary
1. Added sub-step timing instrumentation to `TemperatureGraphRenderer.renderGraph()` to benchmark each phase of the render pipeline.
2. Deployed to emulator, triggered a widget update, and captured benchmark data from logcat.
3. Analyzed the full pipeline breakdown and identified why the actual temperature line appears delayed.
4. Reduced `STARTUP_FULL_GRAPH_REFRESH_DELAY_MS` from 900ms to 200ms.
5. Added `STARTUP_PHASE2` DB logging and a developer toast when Phase 2 is cancelled.

## User Prompts Used In This Session
1. `I want to benchmark the temperature graph. Can I invoke it in the emulator, you examine the logs and give me benchmark info? I'm particularly interested in how long it takes for the actual temperature line to render, since sometimes it seems to take several seconds.`
2. `done` (after triggering a widget tap on the emulator)
3. `Why does it take 900ms for the actual temperature line to appear?`
4. `Lower delay to 200 ms. Is there a way to track / log to db if it causes an issue?`
5. `How will I know if 200ms is causing an issue versus 900ms?`
6. `Does it make sense to add a toast message if issue 1 appears? Widget is currently only being used by me the developer.`
7. `implement`
8. `Write session log to session-logs/ dir`

## Benchmark Investigation

### Instrumentation added to `TemperatureGraphRenderer.kt`
Added `SystemClock.elapsedRealtime()` checkpoints around each phase of `renderGraph()`:
- `ensurePaints` — paint init/caching
- `computeScaling` + `computeLayout` — layout math
- `computePoints` — coordinate computation + 3x Bezier path construction
- `drawFillAndCurves` — gradient fill + canvas curve drawing
- `drawHourLabelsAndIcons` — icon rendering
- `placeTemperatureLabels` + `placeDayLabels` — label collision detection
- `drawFetchDot` + `drawNowIndicator` — decorations

Also added timing inside `computePoints()` to isolate each of the three `buildSmoothCurveAndFillPaths` Bezier path constructions.

Logs emitted as `RENDER_BREAKDOWN` and `BEZIER_BREAKDOWN` via `Log.d(TAG, ...)`.

### Benchmark results (emulator, 584×385px, 95 hours)

**Full pipeline** (`TEMP_PIPELINE_PERF`):
| Phase | Time |
|---|---|
| Resolve current temp | 3ms |
| Observation DB query | 3ms |
| IDW blend (`buildHourData`) | 24ms |
| `renderGraph()` total | 30ms |
| RemoteViews paint | ~44ms (remainder) |
| **Total** | **104ms** |

**Inside `renderGraph()`** (`RENDER_BREAKDOWN`):
| Phase | Time |
|---|---|
| Paint init | 1ms |
| Layout/scaling math | 0ms |
| `computePoints` + Bezier paths | 5ms |
| Curve drawing (fill + paths) | 11ms |
| Hour labels + icons | 5ms |
| Temp/day label placement | 5ms |
| Decorations | 1ms |
| **Total** | **28ms** |

**Bezier path construction** (`BEZIER_BREAKDOWN`): 1ms each for all 3 paths (95 points) — negligible.

### Root cause of the "several seconds" perception
The render itself is fast (104ms total pipeline). The delay is the **two-phase startup design**:
- Phase 1 (fast path): skips observation query and IDW blend entirely, renders forecast-only in ~80ms so the widget appears immediately
- Phase 2 (deferred): fires `ACTION_REFRESH` with `EXTRA_UI_ONLY=true` after `STARTUP_FULL_GRAPH_REFRESH_DELAY_MS` — the actual temperature line appears at this point

The 900ms delay was set conservatively; the pipeline actually completes in ~104ms.

## Changes Implemented

### 1. Benchmark instrumentation (temporary, for measurement)
**File:** `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
- Added `import android.os.SystemClock`
- Added phase timing inside `renderGraph()` → `RENDER_BREAKDOWN` log
- Added per-path timing inside `computePoints()` → `BEZIER_BREAKDOWN` log

### 2. Startup delay reduction
**File:** `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`, line 156
```kotlin
// Before
private const val STARTUP_FULL_GRAPH_REFRESH_DELAY_MS = 900L
// After
private const val STARTUP_FULL_GRAPH_REFRESH_DELAY_MS = 200L
```

### 3. Phase 2 logging + cancellation toast
**File:** `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`

`scheduleStartupFullGraphRefresh` now takes `phase1StartMs: Long` and:
- Logs `STARTUP_PHASE2 status=scheduled` to DB when Phase 2 is queued (with `phase1TotalMs`)
- Logs `STARTUP_PHASE2 status=fired` to DB when Phase 2 successfully fires (with `phase1ToPhase2Ms`)
- Logs `STARTUP_PHASE2 status=cancelled` to DB + shows a developer toast (`⚠️ Phase2 cancelled (Xms)`) when the token guard aborts Phase 2

The call site at line 692 was updated to pass `handlerStartMs`.

## Failure Mode Reference

| Failure | Symptom | Signal |
|---|---|---|
| Phase 2 cancelled | Actuals never appear on that startup | `STARTUP_PHASE2 status=cancelled` in DB + toast |
| Visible flicker | Widget blinks before settling | `phase1ToPhase2Ms` < ~150ms in logcat |

To query for cancellations:
```sql
SELECT message, datetime(timestamp/1000, 'unixepoch', 'localtime')
FROM app_logs WHERE tag='STARTUP_PHASE2' ORDER BY timestamp DESC LIMIT 20;
```

## Files Changed
1. `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — benchmark timing instrumentation
2. `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt` — delay 900→200ms, Phase 2 logging + toast
3. `plans/260404-benchmark-temperature-graph-rendering.md` — plan file
4. `session-logs/260404-benchmark-and-startup-delay.md` — this file
