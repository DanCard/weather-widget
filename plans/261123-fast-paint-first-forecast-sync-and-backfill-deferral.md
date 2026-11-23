# Fast Paint-First Forecast Sync, Async Prior-Day Cloud, and Interaction-Deferred Backfill

## Evidence

Fold 4 (`SM-F936U1`, `RFCT70G352Y`) live `logcat` + Room `app_logs` on `2026-11-23 15:13:16–15:13:30` after tapping API toggle to `OPEN_METEO` (`ageMin=553`):

1. **`OpenMeteoApi.getForecast` was fast (614 ms):** `15:13:17.720 D/OpenMeteoApi: getForecast: Raw response length=121202 ms=614`.
2. **Parsing + persisting forecast took ~2.9 s:** `15:13:20 FORECAST_FETCH source=OPEN_METEO ok=true ms=3564`.
3. **Synchronous `getPriorDayCloudForecast` added ~1.5 s before `NET_FETCH_COMPLETE`:** `15:13:22.112 D/OpenMeteoApi: getPriorDayCloudForecast: hours=768 kept=760` (`NET_FETCH_COMPLETE durationMs=5657`).
4. **Synchronous `recomputeActuals` + concurrent 72 h NWS backfill added ~6.0 s before `worker_paint_start`:**
   - At `15:13:02` (screen-on paint), `HourlyObservationBackfill` saw `latest_gap_min=48` and scheduled `OBS_HOURLY_BACKFILL_REQ` (`delayMs=12536` / `16241`, `lookbackHours=72`).
   - That delayed worker woke at **`15:13:21`**—right in the middle of the user's `OPEN_METEO` toggle sync—and pulled **72 hours × 5 NWS stations (~1,335 rows)** despite only a 48-minute gap (and `NWS_DAILY_BACKFILL_RESULT` at `15:13:23` logged `need=none rows=0`).
   - Meanwhile `FullSyncPipeline.recomputeActuals` ran **before `paintAll`** and spent **`4,310 ms`** (`extremes=3814ms`) recomputing all 7 sources for `2026-11-22` while contending with the backfill on SQLite and CPU.
   - Result: `worker_paint_start` did not begin until **`15:13:29`** (~13 s after the tap, despite a 614 ms network fetch).

## Root Cause

1. **Non-critical post-fetch work blocks the first widget paint:** both `PriorDayCloudRun` (cloud-graph day-ago curve) and `recomputeActuals` (daily-history extremes across all 7 sources) run synchronously before `paintAll()` on a user-initiated forecast sync.
2. **`OBS_HOURLY_BACKFILL` over-fetches and ignores active user interactions:** it always requests 72 h regardless of gap size (48 min → 72 h = 24× excess), and its one-shot startup delay (`12–16 s`) does not back off when the user starts tapping widgets or triggers a forecast sync.

## Proposed Fix

### 1. Run `PriorDayCloudRun` asynchronously after/beside forecast paint (`:shared`, `:app`, `:desktop`)

- Mirror `ClimateNormalsRun.ensureForSite` (which already runs in parallel and triggers a paint-only repaint only when new rows are stored):
  - In Android `ForecastFetchCoordinator` / `FullSyncPipeline`: remove `priorDayCloud` from the synchronous `NET_FETCH` critical path; launch `PriorDayCloudRun.run(...)` as a child coroutine in `FullSyncPipeline` (only when `OPEN_METEO` was fetched and `networkAllowed`), calling `WidgetWorkScheduler.enqueueUiRepaint(context, reason = "prior_day_cloud")` only when `stored > 0`.
  - In Desktop `DesktopWeatherRepository` / `DaemonRuntime`: publish the forecast snapshot first, then run `PriorDayCloudRun` in the background follow-up and notify the UI only when new rows were stored.
- **Savings:** ~1.5 s off the critical path to first paint on every Open-Meteo fetch.

### 2. Paint-first on user-initiated forecast syncs (`FullSyncPipeline`, `:app`)

- On a targeted / user-initiated forecast sync (`input.forceRefresh && input.targetSourceId != null`, e.g. `stale_on_toggle_api`, `widget_tap`, `manual_refresh`, `source_retry`):
  1. Immediately after `weatherList` and `hourlyForecasts` are loaded, run `paintAll()` using the **existing cached `daily_history` actuals** (logging `SYNC_STAGE stage=paint_first`).
  2. Then run `backfill` (`NwsGapFillRun`, METAR, Synoptic) and `recomputeActuals` **after `paintAll()`** on the background `SyncDispatcher` pool.
  3. Only enqueue a follow-up paint-only repaint (`enqueueUiRepaint(reason = "post_fetch_actuals")`) if `recomputeActuals` actually modified any stored `daily_history` row for the displayed window.
- Routine background full syncs (`forceRefresh == false`) keep the existing order (no extra paint needed when the user isn't watching).
- **Savings:** ~4–6 s off the critical path to first paint on toggle/refresh.

### 3. Interaction-deferred & gap-sized `OBS_HOURLY_BACKFILL` (`:shared`, `:app`)

- **Size `lookbackHours` to the actual gap** (`:shared` helper `HourlyBackfillWindow.lookbackHours(gapMinutes, hasNoObservations)`):
  - When `no_nws_observations` (empty site): keep `72` h (`DEFAULT_OBSERVATION_BACKFILL_HOURS`).
  - When a finite gap (`latest_gap_min=48`, `cloud_series_break_min=…`, etc.): `ceil(gapMinutes / 60.0) + 2` h, clamped to **`3..72` h** (so a 48-minute gap pulls **3 h** instead of **72 h**—~24× fewer rows and SQLite writes).
- **Defer pending `OBS_HOURLY_BACKFILL` on user interaction (with a cap):**
  - Add a shared `:shared` policy `BackfillDeferralPolicy` (parallel to `TimedRepaintGate`):
    - On each widget interaction (`ACTION_TOGGLE_API`, `ACTION_DAY_CLICK`, `ACTION_REFRESH`, `ACTION_SET_VIEW`), if an `OBS_HOURLY_BACKFILL` WorkManager job is `ENQUEUED` (**never** `RUNNING` — `AGENTS.md` Rule 6), reschedule it with `ExistingWorkPolicy.REPLACE` to **`20 s`** from now, capped at **`90 s`** total from the original request so a busy user never starves it forever (logged as `OBS_HOURLY_BACKFILL_DEFERRED reason=user_interaction delayMs=… totalDeferredMs=…`).
    - When `WeatherWidgetWorker` starts `OBS_HOURLY_BACKFILL_RUN`, if a user-initiated `FullSyncPipeline` is actively running (or `TimedRepaintGate` shows an interaction within the last 5 s) and total deferral < 90 s, yield and re-enqueue 20 s later instead of contending on SQLite.
    - Before fetching NWS stations inside `OBS_HOURLY_BACKFILL_RUN`, re-check whether the gap was already closed by `charging_screen_on_catchup` or a recent sync (`OBS_HOURLY_BACKFILL_SKIP reason=gap_closed_before_run`).

### 4. Living Documentation Updates

- `arch/forecast-and-observation-cadence.md`: document async `PriorDayCloudRun`, paint-first user-initiated syncs, gap-sized `OBS_HOURLY_BACKFILL` lookback (`3..72 h`), and interaction deferral (`20 s` debounce, `90 s` cap).
- `arch/operations.md`: note `OBS_HOURLY_BACKFILL_DEFERRED` and `paint_first` stage in `SYNC_STAGE`.

## Tests

1. **`:shared` unit tests:**
   - `HourlyBackfillWindowTest`: verify `48 min → 3 h`, `6 h → 8 h`, `empty → 72 h`, clamped to `3..72 h`.
   - `BackfillDeferralPolicyTest`: verify each interaction pushes delay out by `20 s`, caps at `90 s` from first request, and never defers once capped.
2. **`:app` Robolectric / integration tests:**
   - `FullSyncPipeline` paint-first test: verify a targeted `stale_on_toggle_api` sync paints before `recomputeActuals` runs and skips a second paint when actuals are unchanged.
   - `HourlyObservationBackfill` test: verify interaction deferral replaces only `ENQUEUED` work (never `RUNNING`), and `OBS_HOURLY_BACKFILL_RUN` skips when the gap closed before it started.
3. **`:desktop` tests:**
   - Verify `DesktopWeatherRepository` / `DaemonRuntime` publishes the forecast before `PriorDayCloudRun` finishes.

## Verification

1. Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
2. On Fold 4, toggle to a stale `OPEN_METEO` source and check `app_logs` (`SOURCE_SWITCH_E2E`, `FORECAST_FETCH`, `SYNC_STAGE stage=paint_first`, `WIDGET_LIFECYCLE phase=worker_paint_done`, `OBS_HOURLY_BACKFILL_DEFERRED`): verify widget paint completes in ~2–3 s instead of ~13 s, with prior-day cloud and actuals recompute finishing afterward.
