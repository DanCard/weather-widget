# Daily view fetches far more than it renders (and my 30-day horizon regressed Samsung)

## Measured problem

Samsung Fold, widget 345 (10-col graph). `SET_VIEW_TIMING mode=DAILY` from `app_logs`, split at the
20:53 install of commit 2643ae18:

| | before (n=27) | after (n=12) |
|---|---|---|
| min | 234ms | 347ms |
| median | ~310ms | ~418ms |
| ≥976ms | 1 of 27 | **5 of 12** |

Raw after-values: `347 375 386 391 391 417 419 976 1010 1092 1353 2580`.
The floor rose ~110ms and the tail got much worse — this is a real regression, not an outlier. The
tail matches the reported ~3s on the observations-activity round trip. Emulator/Pixel don't show it
because the Samsung DB carries the most historical batches (40MB vs 20MB).

## Cause 1 — my `DAILY_FORECAST_DAYS = 30L` was lazy

I set every path to 30 days because that's what the interactive path already used, without asking
what the render needs. It needs `dateOffset + numColumns - 2` (nav step is 1 day;
`NavigationUtils.getDayOffsets` spans `-1 .. numColumns-2` around the center), plus 1 day for the
nav-enable lookahead. For the Fold's 10-col widget at offset 0 that is **today+9, not today+30**.

Row counts on the Samsung confirm the recent-range over-fetch is modest but real:
`today-1..today+30` = 857 rows vs `today-1..today+9` = 759 needed.

## Cause 2 — pre-existing: 30-day history load feeding a per-group `Log.d`

Bigger fish, and it predates my change. `DailyInteractionRenderer` loads
`DAILY_LOOKBACK_DAYS = 30` of history on **every** render, though at offset 0 the only past column
rendered is *yesterday*. Those rows then flow through
`DailyForecastSelector.selectFreshestPerDaySource`, which emits **one `Log.d` per
(targetDate, source) group** that has more than one row — and builds the message eagerly:

```kotlin
Log.d(TAG, "dedup: date=… rows=${group.size} sameSite=${sameSite.size} " +
    "pickedBatch=… droppedBatches=${group.filter { it !== picked }.map(batchFetchedAt)}")
```

`Log.d` always evaluates its arguments, so the `filter{}.map{}` list is allocated and rendered even
when the tag isn't loggable. Measured **3635 `DailyForecastSelector` lines** in one captured window
(3 widgets × several selector calls per render). At ~50µs per line that is on the order of 150–200ms
per render — same magnitude as the entire pre-regression budget. This also violates the project rule
that high-frequency logs use `Log.v` (see memory `verbose_level_for_high_frequency_logs`).

## Plan

### Phase A — right-size the load windows (the "fetch too much" fix, first per user preference)

1. Add to `NavigationUtils` a pure helper returning the days-past-today and days-before-today the
   daily view actually renders at a given `(dateOffset, numColumns, skipYesterday)`, derived from the
   existing `getVisibleDateRange` so it cannot drift from the render.
2. `DailyInteractionRenderer`: build `rangeFor` from that helper using the widget's real
   `dimensions.cols` and its current `dateOffset` (both already available — just move the
   `WidgetSizeCalculator.getWidgetSize` call above `loadData`), plus 1 day of nav headroom on each
   side.
3. `WidgetStartupCoordinator` / `WeatherWidgetWorker`: these serve all widgets at once, so use the
   **max** across installed widgets of `savedOffset + cols - 1`, instead of a flat constant.
4. **Decouple navigation reach from what was loaded** (see risk below): `canNavigate` currently tests
   the loaded `availableDates`, so shrinking the load would silently shorten how far forward/back the
   user can scroll. Replace that test with an explicit policy horizon
   (`NAV_HISTORY_DAYS = 30`, `NAV_FORECAST_DAYS = 30`) so navigation reach is unchanged by design
   rather than as a side effect of the query width.

### Phase B — stop the logging hot spot (after A, per user preference)

5. Guard the `DailyForecastSelector` dedup log behind `Log.isLoggable(TAG, Log.VERBOSE)` and demote
   to `Log.v`, so neither the string nor the `droppedBatches` list is built on the hot path. Keep the
   diagnostic content intact (memory: keep debug logging, don't delete it).

## Main risk

Phase A step 4 is the load-bearing one. The horizon currently does double duty — it sizes the query
*and* defines how far the user can navigate, because `availableDates` comes from the loaded rows
(gap rows included). Cutting the query without step 4 would look like "navigation stops after a day
or two". Step 4 must land in the same change as steps 2–3.

## Verification

- Unit tests for the new `NavigationUtils` helper: for `(cols=10, offset=0, skipYesterday=false)` the
  forecast need is today+8 and history need is yesterday; assert it matches
  `getVisibleDateRange` for a spread of cols/offsets so the two cannot drift.
- Unit test that navigation reach is independent of the load window (the step-4 contract).
- Re-measure `SET_VIEW_TIMING mode=DAILY` on the Samsung across the same interaction sequence and
  compare against the before/after table above; expect to land at or below the 234–310ms floor.
- Confirm `DailyForecastSelector` line count in a captured window drops to ~0 at default log level.

## Status

**Phases A and B implemented.** Full unit suite green (1770 tests, 246 classes, 0 failures).

Design note on how A avoided the navigation risk: rather than replacing `canNavigate` with a policy
constant, the **query window** shrank while the **gap-fill horizon stayed at 30**. `ClimateGapFiller`
synthesizes `GENERIC_GAP` rows in memory from 12 cached monthly means — ~30 allocations, no query —
and `availableDates` is built from those rows, so forward navigation reach is completely unchanged
and no `canNavigate` logic was touched. Backward reach comes from observation dates, which
`DailyActualsLoader` loads on its own range. This turned out simpler and lower-risk than the plan.

`WidgetInteractionCache.Key` gained the window bounds so a nav tap that widens the window can never
be served a narrower cached load, while widgets resolving the same (widest) window still coalesce.

Measured after:

- `DailyForecastSelector` log lines in a comparable capture window: **3635 -> 0**.
- Samsung worker breakdown: `SYNC_PERF weather=85ms hourly=1888ms actuals=734ms widgets=113ms` —
  the daily forecast query is no longer meaningful; **hourly is now the dominant cost**.
- The 8th-day fix still holds: emulator renders all 10 columns to 2026-08-11 with
  `ic_weather_clear` and bar `#34C759`.

## Phase C — hourly pulled every source, not the displayed one (done)

User: "Hourly should not pull 10 days of hourly rows across every source. Should only pull for
current source." Correct — the daily query had used `activeSourceList` for a while; hourly never got
the same treatment, and every consumer filtered to the display source in memory afterwards.

Row counts over the 72h-back/168h-forward window on the Samsung:

| table | all sources | display source (NWS) |
|-------|-------------|----------------------|
| `hourly_forecasts` | 4,928 | 841 |
| `hourly_forecast_history` | **33,473** | 7,597 |

`hourly_forecast_history` was the elephant — 115,716 rows total, and `fetchHourlyForecasts` called
`getHistoryInRangeForBucketWindowAllSources` with unbounded buckets.

Added multi-source (`IN (:sources)`) variants — `HourlyForecastDao.getHourlyForecastsForSources` and
`HourlyForecastHistoryDao.getHistoryInRangeForBucketWindowForSources` — and used them from the three
render paths (`WeatherWidgetWorker.fetchHourlyForecasts`, `WidgetStartupCoordinator`,
`DailyInteractionRenderer`). "Sources" is each installed widget's display source **plus
`GENERIC_GAP`**, not literally one source, because widgets can display different sources at once —
same semantics the daily query already used.

`DailyActualsLoader` deliberately left alone: it builds actuals *per source* and its hourly feeds
per-source interpolation, so filtering there could degrade a source's actuals. It is the separate
`actuals=` stage anyway.

### Measured (Samsung Fold, `SYNC_PERF uiOnly=true`)

| | hourly stage | total |
|---|---|---|
| before | 1146–2036ms (median ~1590) | 1756–2994ms (median ~2284) |
| after | **503–541ms (median 533)** | **1152–1285ms** |

A ~3x cut on the stage, and the variance collapsed. Worth noting the guard that caught a real
mistake: `HourlyProximityQueryAllowlistTest` failed because the new `...ForSources` method fell
outside its regex — it has the same cross-**site** over-fetch hazard (proximity box spans every
cached coordinate site), so the pattern was extended to `(BySource|ForSources)?` rather than the
allowlist being loosened. Restricting sources changes which sources return, never which sites.

## Next (the "why is it slow" phase, deliberately separate)

With the query window right-sized (A), the log hot spot gone (B) and hourly source-filtered (C), the
Samsung's remaining stages are:

| stage | before all three | after |
|-------|------------------|-------|
| hourly | 1888ms | 533ms |
| **actuals** | 734ms (uiOnly) / **3079ms** (full sync) | unchanged — now the largest |
| weather (daily) | 85ms | 85ms |
| widgets (paint) | 113ms | ~77ms |

**`actuals` is now the biggest remaining stage**, and unlike hourly it is untouched: `DailyActualsLoader`
still reads its own unbounded observation range and its own unfiltered hourly. That is the next
candidate, but it needs care — the per-source actuals map is what the display source reads from.

The other thing still outstanding is a like-for-like check of the ORIGINAL report: re-measure
`SET_VIEW_TIMING mode=DAILY` across the reported 5-step interaction sequence (day column -> zoom ->
observations activity -> back -> home) and compare against the 234-310ms pre-regression floor. That
timing only fires on real interaction, so it cannot be reproduced from adb — it needs a manual run on
the Fold.

## Phase D — measure the interval the user actually sees

The like-for-like Fold run is now captured. The retained outlier was real:

- `21:07:28 SET_VIEW_TIMING widget=345 total=2580ms mode=DAILY`.
- That timer starts inside `WidgetIntentActionHandler.setView`, after broadcast delivery, coroutine
  dispatch, and the per-widget mutex. It therefore undercounts the user's tap-to-paint interval.
- The outlier overlapped an activity-exit cache repaint, a fresh process after
  `PROC_EXIT reason=CRASH_NATIVE`, and an NWS fetch. The daily interaction materialized 201 forecast
  and 1324 hourly rows; its `DailyViewHandler` portion was 1047ms, leaving about 1533ms in unmeasured
  interaction queries/preparation.
- A post-Phase-C repeat at 21:48 used 76 forecast and 227 hourly rows. Broadcast receipt was
  `21:48:33.776`, One UI received the full `RemoteViews` at `21:48:34.688`, and One UI laid out widget
  345 at `21:48:34.710`: 934ms from the earliest app-observable boundary to host layout. This repeat
  is faster; it does not negate the earlier 2.58s event.

Added two correlated timing breadcrumbs:

1. `SET_VIEW_E2E_TIMING` starts at `WidgetActionReceiver.onReceive` and carries a per-event token.
   The existing `SET_VIEW_TIMING` carries the same token, so their difference exposes coroutine and
   widget-lock wait time. This is broadcast-receipt-to-completion, not inaccessible physical
   touch-down time.
2. Slow-only `DAILY_INTERACTION_PERF` splits the daily interaction into cached daily data,
   climate-gap fill, snapshots, hourly rows, observations, current-temperature hourly rows,
   current-temperature resolution, and final render/push, with row counts and the same token in its
   metadata. It persists only at 500ms or slower so normal taps do not flood `app_logs`.

The logging build was installed on the Fold and the new markers were validated against two manual
repeats of the reported sequence:

| repeat | app receiver to completion | handler | One UI result delivery |
|--------|----------------------------|---------|------------------------|
| uncontended | 327ms | 316ms | immediate |
| refresh-contended | 832ms | 822ms | immediate |

The second repeat overlapped the serial activity-exit refresh. Its interaction breakdown was 27ms
daily data, 20ms gap fill, 64ms snapshots, 37ms hourly, 158ms observations, 12ms current-hourly,
48ms current-temperature resolution, and 432ms render/push. The adjacent refresh of widget 349 took
1317ms, showing that activity-exit work can still create contention, but the home event itself no
longer exhibits the original 2580ms app-side delay. These measurements begin at broadcast receipt;
physical touch-down remains outside the app's observable boundary.

System and One UI logs expose the portion the app marker cannot see. On two refresh-contended runs,
One UI handled touch-up and ActivityManager accepted the `ACTION_SET_VIEW` broadcast in 11ms and
8ms respectively, but `WidgetActionReceiver.onReceive` did not run until the preceding asynchronous
`ACTION_REFRESH` completed its serial repaint of widgets 345, 349, and 352:

| repeat | touch-up | system accepted broadcast | app receiver | host received daily view |
|--------|----------|---------------------------|--------------|--------------------------|
| 1 | 22:02:50.895 | 22:02:50.906 | 22:02:53.075 | 22:02:53.895 |
| 2 | 22:03:08.161 | 22:03:08.169 | 22:03:10.121 | 22:03:10.886 |

That is 3.000s and 2.725s from touch-up to the launcher receiving the daily result. The dominant
missing interval is therefore neither touch handling nor One UI applying the result: the home
broadcast is queued for roughly two seconds behind the still-open `goAsync()` refresh broadcast.
An uncontended 327-404ms app render demonstrates that a normal touch-to-result target of about
500ms is reasonable, but not while activity exit refreshes every installed widget ahead of the tap.

## Phase E — remove the unrelated global refresh from this interaction

The global repaint was an overloaded action, not a requirement of the observations screen:

- Global `ACTION_REFRESH` callers such as screen unlock and settings/locale changes need to repaint
  all widgets and retain that behavior.
- Widget-launched activities already attach `EXTRA_APPWIDGET_ID`, but `WidgetActionReceiver` and
  `WidgetRefreshCoordinator` discarded it before routing to `renderAllWidgetsFromCache`.
- `WeatherObservationsActivity.onDestroy` sent the action on every exit even though its comment said
  to refresh only after changing source.

The corrected routing preserves global refresh when no ID is supplied and calls
`renderWidgetFromCache` when an ID is present. The observations activity now marks widget content
dirty only after a source change or successful manual observation fetch; simply inspecting and
closing the activity sends no refresh. If dirty, only the originating widget is repainted.

Verification:

- `WidgetRefreshCoordinatorTest`: targeted refresh calls only `renderWidgetFromCache`; global
  refresh still calls only `renderAllWidgetsFromCache`.
- `WeatherObservationsSupportTest`: a valid widget plus changed content is required for exit repaint.
- Focused Short/Medium tests, Kotlin compilation, `ktlintCheck`, and `assembleDebug` passed.
- The corrected APK is installed on the Samsung Fold. A final manual repeat of the exact activity
  sequence remains required because the device left the launcher while the automated probe was in
  progress.
