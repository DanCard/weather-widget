# NWS Today High Shows 83.4 After Temperature Graph

## User Prompts

1. Start in plan-first mode: inspect first, propose a short plan, and wait for approval before making changes. Do not edit files or run mutating commands until I confirm.

2. On pixel 7 pro: daily forecast view for today, the high temp is 83.4 why? Review logs, and logging if that helps.

3. On pixel 7 pro: daily forecast view for today, the high temp is 83.4 why? Review logs, add logging if that helps.

4. Implement the plan.

5. On samsung and pixel 7 pro: daily forecast view: For today's high it says 83 initially. When I click on temperature graph and return to daily it says 83.4, then after a time it returns to 83.

6. This is only for the NWS api, so you can ignore other sources. A simple question to ask is why does it show 83.4 after temperature graph, which seems wrong.

7. write detailed session log to session-logs/ dir, then commit and push

## Summary

Investigated an NWS-only daily-view discrepancy where today's high initially displayed as `83`, changed to `83.4` immediately after opening the temperature graph and returning to daily view, then later returned to `83`.

The root cause was not the NWS forecast row. The NWS forecast high remained `83.0`. The temporary `83.4` came from the `SET_VIEW -> DAILY` path using a stale/different persisted today row from `daily_extremes` (`83.40072`) and merging it into today's actuals. Normal worker refreshes used the live time-aligned NWS actual for today (`82.56303`) and therefore displayed the forecast high `83.0`.

The fix keeps today's actuals live-only in `WidgetIntentRouter.getDailyActuals`, matching the repository/worker path and preventing persisted `daily_extremes` from overriding today's live NWS blend on view-return renders.

## Runtime Evidence

1. Connected-device check found Samsung connected during the final evidence pass:
   - Serial: `adb-RFCT71FR9NT-j2OIso._adb-tls-connect._tcp`
   - Manufacturer: `samsung`
   - Model: `SM-F936U1`
   - Device time observed around `2026-05-18 18:38 PDT`

2. Pixel 7 Pro was not connected during the final evidence pass, so the final live runtime evidence was Samsung-only. The issue reproduced there with the same symptom.

3. Screenshot was captured for the Samsung device:
   - `/tmp/weather_widget_samsung_screen.png`

4. Database snapshot was pulled read-only for inspection:
   - `/tmp/weather_database_samsung.sqlite`
   - `/tmp/weather_database_samsung.sqlite-wal`
   - `/tmp/weather_database_samsung.sqlite-shm`

5. App log sequence showed the user opened the temperature graph from today's daily column, then returned to daily:
   - `2026-05-18 18:36:26 CLICK_DAILY index=2, date=2026-05-18, isHistory=false, showHistory=false, targetView=TEMPERATURE, offset=0, clickSource=graph_day:col=1:date=2026-05-18`
   - `2026-05-18 18:36:26 SET_VIEW_TIMING ... mode=TEMPERATURE`
   - `2026-05-18 18:36:29 TEMP_PIPELINE_PERF ... widget=345 view=TEMPERATURE ...`
   - `2026-05-18 18:36:30 SET_VIEW_TIMING ... mode=DAILY`

6. Normal worker/all-widget daily path around `18:36:16` to `18:36:17` used the live blended actual:
   - `TODAY_BAR_DEBUG widget=345 mode=GRAPH obsHigh=79.035 obsLow=63.94965 fHigh=83.0 fLow=58.0 trueHigh=82.56303 ...`
   - `TODAY_HIGH_PROVENANCE widget=345 ... source=NWS forecastHigh=83.00 forecastLow=58.00 dailyActualHigh=82.56 dailyActualLow=63.95 currentTemp=79.04 observedAt=18:00:00 graphObservedHigh=79.04 graphObservedLow=63.95 graphForecastHigh=83.00 graphForecastLow=58.00 graphGhostHigh=82.56 graphSnapshotHigh=81.00 obsRows=417 ...`

7. Because daily view chooses the visible high from the maximum of current/observed, forecast, and actual/ghost values, the normal path had:
   - Current/observed high: `79.04`
   - Forecast high: `83.00`
   - Live actual/ghost high: `82.56`
   - Displayed high: `83`

8. After returning from the temperature graph through `SET_VIEW`, the widget used persisted today extreme data:
   - `TODAY_BAR_DEBUG widget=345 mode=GRAPH obsHigh=79.035 obsLow=62.6 fHigh=83.0 fLow=58.0 trueHigh=83.40072 ...`
   - `TODAY_HIGH_PROVENANCE widget=345 ... source=NWS forecastHigh=83.00 forecastLow=58.00 dailyActualHigh=83.40 dailyActualLow=62.60 currentTemp=79.04 observedAt=18:00:00 graphObservedHigh=79.04 graphObservedLow=62.60 graphForecastHigh=83.00 graphForecastLow=58.00 graphGhostHigh=83.40 ...`

9. The post-temperature-graph path therefore had:
   - Current/observed high: `79.04`
   - Forecast high: `83.00`
   - Persisted actual/ghost high: `83.40`
   - Displayed high: `83.4`

10. Direct database inspection confirmed the latest NWS forecast row was still `83.0`, not `83.4`:
    - `NWS highTemp=83.0 lowTemp=58.0 condition=Clear batch_local=2026-05-18 18:36:15 fetchedAt=1779154575407`

11. Direct database inspection confirmed `daily_extremes` held the temporary display value:
    - `NWS highTemp=83.4007186889648 lowTemp=62.5999984741211 updated_local=2026-05-18 17:23:19`

12. Station maxima in the NWS observation set included values above and below the display value:
    - `AW020 max=84.0019989013672 at 16:55 distance=2.94km`
    - `KSJC max=82.3999938964844 at 16:55 distance=15.79km`
    - `KPAO max=82.3999938964844 at 16:47 distance=5.73km`
    - `LOAC1 max=82.0039978027344 at 16:10 distance=9.04km`
    - `KNUQ max=80.5999984741211 at 16:55 distance=3.66km`

## Code Path Analysis

1. Normal repository/worker path:
   - `ObservationRepository.getDailyActualsWithLiveToday(...)` uses live today blended actuals directly.
   - It intentionally does not merge persisted today `daily_extremes`, because persisted rows can differ from the time-aligned live blender and reintroduce daily/hourly discrepancies.

2. Return-from-temperature-graph path:
   - `WidgetIntentRouter.handleSetView(... targetMode=DAILY ...)` calls `refreshDailyView(...)`.
   - `refreshDailyView(...)` calls the router-local `getDailyActuals(...)` when daily actuals are not passed in.
   - Before the fix, that private router helper duplicated the daily-actual logic but still read today's `daily_extremes`, converted it to actuals, and merged it with live today actuals.

3. The merge behavior mattered:
   - `ObservationResolver.mergeDailyActualsBySource(...)` preserves widest bounds for overlapping dates.
   - For highs, that means `maxOf(primary.highTemp, secondary.highTemp)`.
   - When persisted today high was `83.40072` and live today high was `82.56303`, the persisted value won.

4. The display behavior then exposed the wrong source:
   - Today daily graph/text uses the maximum of current/observed, forecast, and actual/ghost high.
   - With live today actuals: `max(79.04, 83.00, 82.56) = 83.00`.
   - With persisted today extremes: `max(79.04, 83.00, 83.40) = 83.40`.

## Changes Made

1. Added `TODAY_HIGH_PROVENANCE` database logging in `DailyViewHandler`.
   - Logs forecast high/low.
   - Logs daily actual high/low.
   - Logs current temperature and observation time.
   - Logs graph-observed, graph-forecast, graph-ghost, and snapshot high fields.
   - Logs source observation count, local observation span, and per-station max summaries.

2. Added `DailyViewHandler.buildTodayHighProvenanceMessage(...)` as a `@VisibleForTesting` helper.

3. Added unit coverage for the provenance message in `DailyViewHandlerUnitTest`.

4. Fixed `WidgetIntentRouter.getDailyActuals(...)`.
   - Removed the today `daily_extremes` read.
   - Removed the persisted-today merge.
   - Kept past days sourced from persisted `daily_extremes`.
   - Kept today sourced from live raw observations aggregated through `ObservationResolver.aggregateObservationsToDailyBySource(...)`.

5. Made `WidgetIntentRouter.getDailyActuals(...)` `@VisibleForTesting internal` so the return-to-daily data path can be regression tested directly.

6. Added a Robolectric regression test in `WidgetIntentRouterRobolectricTest`.
   - Seeds live NWS today actual `82.56303`.
   - Seeds persisted NWS today `daily_extremes` high `83.40072`.
   - Asserts `WidgetIntentRouter.getDailyActuals(...)` returns the live value, not the persisted one.

## Files Changed

1. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`

2. `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`

3. `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewHandlerUnitTest.kt`

4. `app/src/test/java/com/weatherwidget/widget/handlers/WidgetIntentRouterRobolectricTest.kt`

5. `session-logs/260518-nws-today-high-after-temperature-graph.md`

## Verification

1. Focused JVM/Robolectric tests passed:

   ```bash
   ./gradlew testDebugUnitTest --tests '*WidgetIntentRouterRobolectricTest*' --tests '*DailyViewHandlerUnitTest*' --tests '*DailyViewLogicTest*' --tests '*ObservationRepositoryDailyMergeTest*'
   ```

2. Result:
   - `BUILD SUCCESSFUL in 16s`

3. The regression test specifically covers the failure shape that produced the observed UI behavior:
   - Live today NWS high lower than forecast high.
   - Persisted today NWS `daily_extremes` high above forecast high.
   - Router daily-actual path must return live today actual, so the daily display remains `83` instead of `83.4`.

## Final Answer

The `83.4` appeared after viewing the temperature graph because the `SET_VIEW` return path used persisted NWS `daily_extremes` for today, whose high was `83.40072`, while the normal worker refresh path used live time-aligned NWS blended actual `82.56303`. Since the daily UI displays the maximum of current/observed, forecast, and actual/ghost high, that persisted value temporarily won and displayed as `83.4`. Later worker refreshes returned the widget to the normal live path, where the forecast high `83.0` was the maximum and the display returned to `83`.
