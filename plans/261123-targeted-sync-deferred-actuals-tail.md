# Targeted Forecast Syncs: Release the Sync Queue at Paint, Run the Actuals Tail as Its Own Debounced Work

Supersedes the post-paint section of
[`261123-fast-paint-first-forecast-sync-and-backfill-deferral.md`](261123-fast-paint-first-forecast-sync-and-backfill-deferral.md)
(§2, "then run backfill and `recomputeActuals` after `paintAll()`") and replaces the drop-the-tail
proposal in [`261123-fast-targeted-forecast-sync-release-worker.md`](261123-fast-targeted-forecast-sync-release-worker.md).
Both build on the same uncommitted working tree, so this plan finishes that change rather than adding a new layer.

## Evidence

Pixel 7 Pro (`2A191FDH300PPW`) `app_logs`, 2026-11-23 15:47:23–15:47:36, toggling
`GOOGLE_WEATHER → OPEN_METEO → SILURIAN` (detailed timeline in `261123-fast-targeted-forecast-sync-release-worker.md`):

1. `GOOGLE_WEATHER` reached `worker_paint_done` at 15:47:23.257.
2. Its worker then ran `runPostPaintBackfillsAndActuals()` inline (Synoptic 227 rows, METAR, NWS, full
   `recomputeActuals`) and held the serial `weather_widget_full_sync` queue for **6.2 s**.
3. The `OPEN_METEO` toggle queued at 15:47:24.08 could not start until 15:47:30.5, even though its network
   fetch then took only **661 ms**.
4. `GOOGLE_WEATHER`'s `post_fetch_actuals` repaint overlapped `OPEN_METEO`'s persist (`FORECAST_FETCH ms=5009`).

## Root Cause

The paint-first change moved the backfills and `recomputeActuals` after the paint, but they still run **inside the
same `WeatherWidgetWorker`**. The worker's lifetime, not its paint time, decides when the next toggle on
`APPEND_OR_REPLACE` unique work can start. Each toggle therefore costs about 8 s of queue time, and every toggle
in a burst repeats the whole observation and actuals tail.

## Constraints Found in Review (Why Not Simply Skip the Tail)

1. **`dailyHistoryAfterFetch` must run after every fetch.** `DailyHistoryRun.afterFetch` takes the rain-chance
   freeze, which "must see each window while it is open". In the current diff, `runRecompute=false` also skips it
   (`FullSyncPipeline.recomputeActuals`, the `if (runRecompute)` block). A toggle fetch just before a window closes
   would lose that source's frozen chance.
2. **The full sync is where METAR and Synoptic reliably get a turn.** The comment the diff removed said every
   user refresh and toggle runs through it. On battery the current-temp loop is sparse, so dropping the tail
   without a replacement could leave actuals hours stale for a user who browses sources but never opens Settings.
3. **Reason strings are not a reliable predicate.** The Android toggle reasons are `toggle_api_stale` and
   `reset_source_stale` (`SyncDoneNotice.TAP_STARTED`), not `stale_on_toggle_api`. Only Settings → "Refresh data"
   refreshes all sources, so `targetSourceId != null` already identifies a targeted sync.

## Proposed Fix

### 1. Fast path for targeted syncs (`FullSyncPipeline.kt`, `:app`)

- Keep the existing condition `paintFirst = !input.uiOnlyRefresh && input.forceRefresh && input.targetSourceId != null`.
  Do not add a reason-string list.
- On `paintFirst`:
  1. Fetch the target forecast, load snapshots and hourly data (`runBackfills = false`), and read cached daily
     actuals (`recompute = false`).
  2. **Always run `weatherRepository.dailyHistoryAfterFetch(...)`** before the paint. Split it out of the
     `if (runRecompute)` block so it runs for every non-UI-only fetch. It is a two-day pass and cheap.
  3. `paintAll()` (`SYNC_STAGE stage=paint_first`), `scheduleAfterSync`, and `SyncDoneNotice.announce`.
  4. `PostSyncActualsWork.enqueue(context, lat, lon, targetSourceId, reason)` (below), then
     `return Result.success()`. **Remove** the inline `runPostPaintBackfillsAndActuals()` call.
- Routine syncs (`forceRefresh == false`) and Settings → "Refresh data" (`targetSourceId == null`) keep today's
  inline order: backfills, then recompute, then paint.

### 2. A separate, debounced post-sync actuals tail (`:app`)

- New unique work `WidgetWorkScheduler.WORK_NAME_POST_SYNC_ACTUALS = "weather_widget_post_sync_actuals"`, run
  by `WeatherWidgetWorker` under a new input flag (`KEY_POST_SYNC_ACTUALS`). It is a separate queue, so it never
  holds `weather_widget_full_sync`.
- **Debounce:** initial delay `POST_SYNC_ACTUALS_DELAY_MS = 12_000`. Rule 6 forbids `REPLACE` on work that may
  be running, so the debounce works like this:
  - If the unique work is `ENQUEUED`, re-enqueue it with `REPLACE`, carrying the union of served source ids.
    Only delayed, not-yet-running work is replaced, which Rule 6 allows.
  - If it is `RUNNING`, use `APPEND_OR_REPLACE` so one more tail runs after the current one. Never cancel it.
  - Cap the total push-out at 60 s from the first request, so a user who keeps toggling still gets actuals.
  - The decision (`replace` / `append` / `cap_reached → keep`) is a pure `:shared` policy,
    `PostSyncTailDebounce`, unit-tested in the style of `BackfillDeferralPolicy`.
- **The tail's work** is what `runPostPaintBackfillsAndActuals` does today, moved out of the toggle worker:
  1. `runBackfills(...)` for the served sources (NWS gap fill, METAR deep, Synoptic), still gated by each
     refresher's `refreshIfDue`.
  2. `recomputeActuals(runRecompute = true)` plus `repairFrozenRainChanceIfNeeded` and `dailyHistoryUpkeepIfDue`.
  3. `enqueueUiRepaint(reason = "post_sync_actuals")` **only** if the displayed `DailyActualsBySource` changed.
     The worker compares its result with a fresh cached read taken at its own start, not with a value held over
     from the toggle worker.
- **Yield to active user syncs:** at start, reuse `WidgetBackfillDeferral.policy.yieldAtStart(...)`, the same
  20 s yield and 90 s cap as `OBS_HOURLY_BACKFILL`. The tail then never contends with a toggle that is still
  persisting. Log `POST_SYNC_ACTUALS_DEFERRED`, `POST_SYNC_ACTUALS_RUN`, and
  `POST_SYNC_ACTUALS_RESULT changed=<bool> ms=<n>`.

Net effect for `Google → Meteo → Silurian`: three toggle workers that each finish at paint (about 1.5–2.5 s), then
one tail about 12 s after the last tap, instead of three 6 s tails in line on the sync queue.

### 3. Desktop parity

- Desktop's `viewing_forecast_stale` `FetchRequest` defaults to `observationWindow = false`, and the daemon has no
  serial WorkManager queue. Confirm during implementation (logs: `REFRESH_ENTER reason=viewing_forecast_stale`
  followed by no observation-window fill) that desktop already skips the observation tail on a viewing fetch and
  still runs `DailyHistoryRun.afterFetch`. If it does, no desktop change is needed; record that in the
  cadence doc. If it does not, route desktop through the same `PostSyncTailDebounce` policy.

### 4. Living documentation

- `arch/forecast-and-observation-cadence.md`: one canonical paragraph covering:
  - targeted syncs paint first, run `afterFetch`, and release the sync queue
  - observation backfills and actuals recompute run as the debounced `post_sync_actuals` work (12 s debounce,
    60 s cap, yields to active user syncs)
  - routine and Settings syncs are unchanged
- `arch/operations.md`: add `POST_SYNC_ACTUALS_*` tags to the log-tag list.
- `rg` `arch/` for `paint_first` and `post_fetch_actuals` and remove any duplicate numbers.

## Tests

1. **`:shared` unit:** `PostSyncTailDebounceTest`:
   - `ENQUEUED` → replace with a fresh delay
   - `RUNNING` → append, never replace
   - pushing out past the 60 s cap → keep
   - served-source union is preserved across replacements
2. **`:app` integration (Robolectric, real WorkManager test driver plus the real `FullSyncPipeline` with fake
   repositories), `TargetedSyncPostActualsIntegrationTest`:**
   - A targeted `toggle_api_stale` sync returns `success` **before** any NWS, METAR, or Synoptic refresher is
     called, and `dailyHistoryAfterFetch` was called once.
   - After it returns, `weather_widget_post_sync_actuals` is `ENQUEUED` with a delay of about 12 s, and
     `weather_widget_full_sync` is empty.
   - Three targeted syncs in a row leave **one** enqueued tail, carrying all three source ids.
   - Running the tail calls the backfills and `recompute = true`. It enqueues `post_sync_actuals` UI work only
     when the fake actuals change.
   - An untargeted Settings sync (`targetSourceId = null`) still runs backfills inline and enqueues no tail.
   - **Regression guard:** on the current working tree, the "returns before refreshers" and
     "`afterFetch` called" assertions must fail.
3. Gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.

## Verification

1. Pixel 7 Pro and Fold 4: toggle `Google → Meteo → Silurian` on stale sources. In `app_logs`:
   - each `SYNC_START target=<next>` follows the previous `worker_paint_done` within about 1 s
   - no `SYNOPTIC_FETCH`, `METAR`, or `NWS` backfill rows inside the toggle workers
   - exactly one `POST_SYNC_ACTUALS_RUN` about 12 s after the last tap, then `POST_SYNC_ACTUALS_RESULT`
2. Check `daily_history` for the toggled sources: rain-chance columns are updated by the toggle fetch (`afterFetch`),
   and actual highs and lows are updated after the tail.
3. Desktop: toggle sources in the popup and confirm the behavior recorded in §3.
