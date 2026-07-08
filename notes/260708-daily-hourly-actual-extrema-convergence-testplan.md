# Test plan: daily-bar vs hourly-graph actual-extrema convergence

**Date:** 2026-07-08
**Bug class:** recurring. The daily column and the hourly temperature graph show *different* actual
highs/lows for the same day and source, even though both are "the actual high/low for that day."

## Why it keeps coming back

Both views blend station observations through the **same** shared engine
(`ActualTemperatureSeriesBuilder.blendObservationSeries`, via `ActualsAggregator.aggregate`). The
divergence is never in the math — it is in the **observation window** each caller passes in. The blend
is window-sensitive because **station membership is window-sensitive**: a station whose feed lapses
before midnight, or whose readings only bracket a day-edge candidate *across* midnight, is present in a
wide window and absent from a day-isolated one. With fewer diluting stations, a lone distant/personal
cold (or hot) outlier dominates that day's extreme.

Each prior fix converged one call site; the next feature added a new caller that re-fetched a
day-isolated window and the bug reappeared. **The durable invariant to test is caller-independent:**

> For a fixed observation set, a day's daily-aggregate extreme == that day's extreme from a
> wide-window blend == the hourly graph's per-day extreme — regardless of how narrow the caller's
> query window is.

## Root cause of the 2026-07-08 instance

- Displayed NWS low: **52.6 → 52.5** (daily column) vs **~54.4** (hourly graph), same day/source.
- `LOAC1` (personal, 8.3 km) read 48°F pre-dawn while three nearer stations held ~55°F. `KPAO`
  (official, 6 km) went stale at 20:47 the prior evening.
- Day-isolated windows dropped the prior-evening coverage, so LOAC1 dominated the low.

## The fix (shipped)

Single authoritative context constant: `ActualsAggregator.DAILY_BLEND_CONTEXT_MS` (24h), public so
every caller reaches back the same distance. Extrema are still taken from the target day only.

| Layer | File / function | Change |
|-------|-----------------|--------|
| Shared engine | `ActualsAggregator.aggregate` / `blendDailyExtremesViaSeries` | Blend each day over `[dayStart-CONTEXT, dayEnd+CONTEXT]` from the full obs list; extract extrema from the target day. |
| Android — stored history | `ObservationRepository.recomputeDailyExtremesForDay` | Fetch `contextObs` over ±CONTEXT; filter result rows to the target day. |
| Android — **live today display** | `ObservationRepository.getDailyActualsWithLiveToday` | Fetch obs from `todayStart-CONTEXT` (this is the path the widget actually renders). |
| Android — view-toggle display | `WidgetIntentRouter` SET_VIEW handler | Same widen. |
| Desktop | `DesktopWeatherRepository.recomputeDailyExtremes` | No change — already passes a whole-history window. |

Unrelated same-session change: `MAX_WEB_FALLBACK_STATIONS` 2 → 3 (Synoptic web fallback breadth).

## Automated tests

### 1. Shared, pure-JUnit (both platforms) — `ActualsWindowIndependenceTest`
`shared/src/test/.../actuals/ActualsWindowIndependenceTest.kt`
- `daily aggregate low equals the wide-window graph low, not the day-isolated low` — builds a
  cross-midnight scenario (near station's only bracketing reading is the prior evening + a cold edge
  reading), asserts `aggregate` matches the wide blend and differs from the day-isolated blend.
- `daily aggregate and hourly graph build agree on both extremes` — asserts `aggregate` high/low ==
  `ActualTemperatureSeriesBuilder.build` per-day actual max/min for the same data.

### 2. Android integration, Room + repository — `DailyLiveTodayWindowConsistencyTest`
`app/src/test/.../widget/DailyLiveTodayWindowConsistencyTest.kt`
- Inserts prior-evening + today observations into a real Room DB, calls the **display path**
  `getDailyActualsWithLiveToday`, and asserts the displayed today low equals the wide blend and does
  NOT regress to a today-only blend. This is the test that would have caught the field bug; it guards
  the caller-side fetch widen that the shared test cannot see.

### Existing guards kept
`ActualsLoneStationGuardTest` (lone-station suppression), `YesterdayActualHighConsistencyTest`
(single-station daily==graph), `ObservationRepositoryDailyMergeTest` (recompute contract).

## How to run

```bash
./gradlew :shared:test --tests "com.weatherwidget.shared.actuals.ActualsWindowIndependenceTest"
./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.widget.DailyLiveTodayWindowConsistencyTest"
# regression sweep of the family:
./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.widget.*Consistency*" \
  --tests "com.weatherwidget.data.repository.ObservationRepositoryDailyMergeTest"
./gradlew :shared:test --tests "com.weatherwidget.shared.actuals.*"
```

## Manual device verification (when a live outlier is present)

1. `./gradlew installDebug`; `adb -s <serial> shell am broadcast -a com.weatherwidget.ACTION_REFRESH -n com.weatherwidget/.widget.WeatherWidgetProvider`
2. `adb logcat -d | grep getDailyActualsWithLiveToday` → `blendedLow` must match the hourly graph's
   labelled low for today.
3. Cross-check the stored row: pull `weather_database`, `SELECT lowTemp FROM daily_history WHERE
   source='NWS' AND date(date/1000,'unixepoch')=<today>` — must agree with the live blend.

## Extension for the next recurrence

Any NEW code path that calls `aggregate`/`aggregateObservationsToDailyBySource` from a DB fetch MUST
fetch `queryStart - ActualsAggregator.DAILY_BLEND_CONTEXT_MS`. Grep guard idea: fail CI if
`getObservationsInRange(` is followed by a same-day start feeding `aggregate` without the constant.
