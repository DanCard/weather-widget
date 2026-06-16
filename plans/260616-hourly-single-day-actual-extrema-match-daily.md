# Make the clicked-day hourly graph's actual high/low match the daily bar exactly

## Context

On the widget, clicking a past day's daily-forecast bar opens the hourly temperature graph, but its
labeled actual high/low disagree with the daily bar (Samsung 2026-06-14 NWS: daily 73.7/60.9, hourly
73.0/61.0). Root cause is fully diagnosed (see memory `daily_vs_hourly_actual_extrema_mismatch`):

Both views run the **same** all-station blend `ActualTemperatureSeriesBuilder.blendObservationSeries`,
the actual line is **not** smoothed, and it's **not** single-station. The blender **dedup-thins output to
one point per 5 minutes** (`DEDUP_MS`, greedy from `lastEmittedMs=0`, advancing over the *entire* context
window — `ActualTemperatureSeriesBuilder.kt:236-297`). Which 5-min samples survive (hence series max/min)
depends on the obs set + window, which differ per caller:
- **Daily bar** (`ActualsAggregator.aggregate` → `blendDailyExtremesViaSeries`): that calendar day's obs,
  window `[dayStart,dayEnd]`. Catches a sample at the true peak (73.71).
- **Hourly graph** (`TemperatureHourDataBuilder.buildHourDataResult` → `ActualTemperatureSeriesBuilder.build`,
  `:178`): broad widget obs query, window `centerTime ± 12h` carved from a `±72h/60h` context. Thinning
  lands on a different 5-min sample, skipping the peak (73.05).

**Decisions (user):** apply only to the clicked-day single-day view (rolling "now" view unchanged); make
the graph reproduce the daily bar's value exactly (no smoothing — the actual line is already raw).

**Why same-window-alone is insufficient:** `lastEmittedMs` advances over the full context candidateTimes
(`:297`), so even snapping the visible window to the day leaves the thinning phase mis-aligned. The blend
**input set and window** must equal the daily call so the greedy 5-min thinning emits an identical series.

## Approach

Add a **single-day mode** to the hourly temperature view, entered when a daily bar is clicked:

1. **Day-bounded actual series.** When in single-day mode, build the actual line from the SAME call the
   daily aggregation uses: `blendObservationSeries(observations = thatDaysObs, startMs = dayStart,
   endMs = dayEnd, ...)` and use `result.observations` as the actual points — instead of `build()`'s
   rolling context window. This guarantees identical thinning → identical max/min → labels equal the
   daily bar by construction. Factor the day-bounded series build so both `ActualsAggregator` and the
   graph share one code path (avoid drift; CPD-clean).
2. **Snap the visible window** to the clicked day `[00:00, 24:00)` (center on day-noon at WIDE zoom, which
   already spans 24h) so the graph shows exactly that day.
3. Actual line stays unsmoothed (already true via `TemperatureExtrema.kt:44`).

### Plumbing
- **Entry:** `WeatherWidgetProvider.handleDayClickAction` / `navigateToHourlyView` — carry the clicked
  `targetDate` into a new single-day state (alongside `viewMode`/`zoom`/`hourlyOffset` in the widget
  state manager), and center the view on that day.
- **Render:** `TemperatureStateResolver` (`:352`) → `TemperatureHourDataBuilder.buildHourDataResult`
  (`:144`) gains a `singleDayDate: LocalDate?` param; when set, it routes the actual series through the
  day-bounded blend and bounds the hour grid to that day.
- **Reset:** leaving the day (zoom/scroll/return to now) clears single-day state back to the rolling view.

### Files
- `shared/.../actuals/ActualTemperatureSeriesBuilder.kt` (or `ActualsAggregator.kt`) — extract/expose a
  shared day-bounded actual-series builder used by both daily aggregation and the graph.
- `app/.../widget/handlers/TemperatureHourDataBuilder.kt` (`:144`, `:178`) — `singleDayDate` path.
- `app/.../widget/handlers/TemperatureStateResolver.kt` (`:352`) — thread `singleDayDate`.
- `app/.../widget/WeatherWidgetProvider.kt` (`handleDayClickAction`/`navigateToHourlyView`) — set state,
  center on day.
- Widget state manager — store/clear the single-day date.

## Verification
1. Unit: add a `:shared` test asserting the day-bounded series for a fixture day yields the same
   max/min as `ActualsAggregator` produces for that day (same thinning) — i.e. graph extrema == daily_extremes.
2. Build+install (`./gradlew installDebug`); on Samsung click 06-14's bar → hourly view; pull logs and
   confirm `ACTUAL_EXTREMA` high/low now equal the `daily_extremes` values (73.7/60.9), and the labels match.
3. Confirm the rolling "now" hourly view is unchanged (regression check).

## Status of the prior (separate) fix in this branch
The actual-LOW labeling fix (3 suppression gates: `resolveExtremaRole` order, `checkFetchDotSuppression`
exemption, `checkEndpointSuppression` exemption) is implemented, unit-tested (`:shared` green), and
emulator-verified (`role=ACTUAL_LOW idx=288`). Samsung re-render confirmation for that fix is still pending
and it is uncommitted.
