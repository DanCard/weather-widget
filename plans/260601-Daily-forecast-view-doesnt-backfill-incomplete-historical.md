# Fix: Daily forecast view doesn't backfill incomplete historical NWS actuals

## Context

On the emulator, a past day (2026-05-31) showed a wrong actual high of **54°** in the
daily widget while neighboring days were 72–82°. Root cause confirmed from the device DB:

- `observations` for 2026-05-31 cover only **00:00–05:20** (123 overnight rows, max 55.4°)
  because the emulator was powered off during the daytime. Every full-coverage day
  computes a correct high.
- `daily_extremes` for 2026-05-31 = `high 54.06 / low 49.78`, **recomputed today** from
  that incomplete overnight slice.

There are three independent "do we need to fetch data?" gates. For a past day that has a
*present-but-incomplete* actual, the two that the daily view relies on both fail:

1. **Background full fetch** → `ObservationRepository.backfillNwsObservationsIfNeeded`
   checks only whether a `daily_extremes` **row exists** for the required dates
   (`ObservationRepository.kt:191`). May 31 has a (wrong) row → `missingDates` empty → skip.
2. **Daily view** → `computeMissingDataRefreshes` checks `dailyActuals[date] == null`
   (`MissingDataRefreshHelper.kt:51`). May 31 is non-null → skip.
3. **Hourly temp graph** → `evaluateHourlyBackfillNeed` checks **observation-coverage gaps**
   (`HourlyObservationBackfill.kt:30`). It detects the May-31 daytime gap → enqueues
   `backfillRecentNwsObservations`, which fetches the missing observations **and recomputes
   `daily_extremes` for the affected dates** (`ObservationRepository.kt:326-331`). ✅

Only #3 is gap-aware — which is why the user reported that visiting the temperature screen
healed the historical actuals. The user wants the **daily forecast view** to trigger the
same retrieval.

## Recommended approach

Reuse the proven gap-aware machinery (`maybeEnqueueHourlyObservationBackfill`) from the
daily view path instead of inventing new detection. It already handles gap evaluation,
the per-widget cooldown (`NWS_HOURLY_HISTORY` key), worker enqueue, and post-fetch
`daily_extremes` recompute.

**Wiring point:** `DailyViewHandler.kt` — both render paths (graph mode call site near
`:1023`, text mode near `:373`). Before/alongside the existing `computeMissingDataRefreshes`
call:

1. Load a bounded recent observation window via the existing
   `repository.getObservationsInRange(minEpoch, maxEpoch, lat, lon)` — window =
   `now − DEFAULT_OBSERVATION_BACKFILL_HOURS (72h)` to `now`. This matches the backfill's
   fetch horizon and is what NWS history can actually supply.
2. Call the existing `maybeEnqueueHourlyObservationBackfill(...)` with that window
   (`graphStart = now-72h`, `graphEnd = now`), the widget's `displaySource`, `repository`,
   `stateManager`, `database`, `appWidgetId`.
3. Gate the extra DB query so it only runs when the visible window includes recent past
   days (e.g. `dateOffset` near 0 / visible dates intersect the last ~3 days) — backfill
   can't help days older than NWS history anyway, and this keeps the daily render hot path
   cheap when the user has navigated far back.

`lat`/`lon`/`repository`/`stateManager`/`appLogDao`/`displaySource` are all already in scope
in `DailyViewHandler.updateWidget` (`:174-179`). The shared cooldown key means the daily
view and temp screen won't double-fetch.

(Decision pending from user: whether to *also* harden the background full-fetch gate
`backfillNwsObservationsIfNeeded` to be coverage-aware so it self-heals without any widget
interaction — see Open Question.)

## Files to modify

- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
  — add the observation-window load + `maybeEnqueueHourlyObservationBackfill` call in the
  graph and text render paths.
- (Reused as-is, no change): `HourlyObservationBackfill.kt`, `WeatherWidgetWorker`
  observation-backfill path, `ObservationRepository.backfillRecentNwsObservations`.

## Verification

- Unit: add a `DailyViewHandlerTest` case proving the backfill worker is enqueued when a
  visible past day has a coverage gap but a non-null cached actual (the May-31 shape).
  Reuse patterns in `DailyViewHandlerTest.kt` / `TemperatureViewHandlerActualsTest.kt`.
- Build + install: `./gradlew installDebug`.
- Live repro on emulator (it already exhibits the bug today):
  - Confirm widget shows May-31 high ≈ 54°.
  - Trigger a daily-view render (resize / `ACTION_REFRESH` broadcast) — **without** opening
    the temp screen.
  - `adb logcat` should show `OBS_HOURLY_BACKFILL_REQ` then `OBS_HOURLY_BACKFILL_RUN/RESULT`
    with May-31 in `affectedDates`.
  - Re-pull DB: `daily_extremes` for 2026-05-31 high should jump to a realistic ~70°+, and
    the widget should reflect it.
- Unit suite: `./gradlew testDebugUnitTest`.
