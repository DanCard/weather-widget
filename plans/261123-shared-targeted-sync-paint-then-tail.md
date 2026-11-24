# Shared Targeted-Sync Runner: Persist → afterFetch → Paint → Debounced Actuals Tail (Android + Desktop)

Supersedes [`261123-targeted-sync-deferred-actuals-tail.md`](261123-targeted-sync-deferred-actuals-tail.md).
That draft shared only the debounce rule and left the ordering, tail contents, gates and logging in
`:app`, against AGENTS.md ("a shared runner owns the complete rule … a shared planner with substantial
per-platform glue is not sufficient"). Also replaces the post-paint parts of
[`261123-fast-paint-first-forecast-sync-and-backfill-deferral.md`](261123-fast-paint-first-forecast-sync-and-backfill-deferral.md) §2
and [`261123-fast-targeted-forecast-sync-release-worker.md`](261123-fast-targeted-forecast-sync-release-worker.md).

## Evidence

### Android: Pixel 7 Pro `app_logs`, 2026-11-23 15:47:23–15:47:36, toggling `GOOGLE_WEATHER → OPEN_METEO → SILURIAN`

1. `GOOGLE_WEATHER` reached `worker_paint_done` at 15:47:23.257. Its worker then ran
   `runPostPaintBackfillsAndActuals()` inline (Synoptic 227 rows, METAR, NWS, full `recomputeActuals`) and held
   the serial `weather_widget_full_sync` queue for **6.2 s**.
2. The `OPEN_METEO` toggle could not start until 15:47:30.5. Its network fetch then took 661 ms, but its persist
   overlapped Google's `post_fetch_actuals` repaint (`FORECAST_FETCH ms=5009`).

### Desktop: code path, `DesktopWeatherRepository.refreshWithOutcome` → `DaemonRuntime` `Kind.FORECAST`

1. A viewing or toggle fetch (`viewing_forecast_stale`, `observationWindow = false`) runs these steps **before**
   `publish(snapshot)`:
   - `runFollowUps`, which includes the NWS station actuals fill
   - `runPostFetchBackfills`: `recomputeDailyExtremes`, `afterFetch`, `upkeepIfDue`
   - `applyRetention`
2. So desktop has the same "actuals before paint" cost Android had before paint-first.
3. Desktop's tail also has different contents from Android's: no METAR/Synoptic deep pull and no NWS gap fill for
   the served sources, but an NWS station-actuals follow-up that Android runs elsewhere.

## Root Cause

1. **The ordering is per-platform.** Android paints first but keeps the tail inside the worker. Desktop
   recomputes before it publishes. Neither order is shared.
2. **The tail's contents are per-platform.** Which observation feeds are refreshed, and which recompute and upkeep
   steps run after a targeted fetch, are decided separately in `FullSyncPipeline` (`:app`) and
   `DesktopWeatherRepository` (`:desktop`).
3. **Constraints carried over from review:**
   - `DailyHistoryRun.afterFetch` must run after every fetch; it holds the rain-chance freeze.
   - The tail must still run, not be dropped. Toggles are where METAR reliably gets a turn on Android.
   - A targeted sync is identified by its target source, not by reason strings.

## Proposed Fix

### 1. `:shared` `TargetedForecastSyncRun`: the complete rule

New file `shared/src/main/kotlin/com/weatherwidget/shared/util/TargetedForecastSyncRun.kt`. It owns the ordering,
the gates, the tail contents, the debounce, the yield, and every log line. The host only does I/O.

```kotlin
object TargetedForecastSyncRun {
    interface Host {
        // fast path
        suspend fun fetchAndPersist(sourceId: String): Boolean   // forecast + hourly persisted
        suspend fun afterFetch()                                  // DailyHistoryRun.afterFetch at the site
        suspend fun paintFromCache()                              // Android paintAll / desktop publish(loadCached)
        // tail scheduling (platform timer; must never cancel a running tail)
        fun tailState(): TailState                                // NONE / PENDING / RUNNING
        fun scheduleTail(delayMs: Long, sourceIds: Set<String>, replacePending: Boolean)
        // tail I/O, reached only from runTail
        suspend fun refreshObservations(feeds: Set<ObservationFeed>, servedSourceIds: Set<String>)
        suspend fun runFollowUps(sourceId: String)                // SourceFetchTable.runFollowUps jobs
        suspend fun displayedActualsSignature(): Int
        suspend fun recomputeActuals()                            // recompute extremes + repairFrozen + upkeepIfDue
        suspend fun repaint(reason: String)
        val userSyncActive: Boolean; val lastInteractionMs: Long?
        suspend fun log(tag: String, message: String, level: String = "INFO")
    }

    /** A user forced exactly one source: the fast path. Settings "Refresh data" (target=null) and routine syncs are not. */
    fun isTargeted(forceRefresh: Boolean, uiOnly: Boolean, targetSourceId: String?): Boolean

    suspend fun runFast(host: Host, sourceId: String, nowMs: Long)   // fetch → afterFetch → paint → schedule tail
    suspend fun runTail(host: Host, sourceIds: Set<String>, nowMs: Long) // yield? → obs → follow-ups → recompute → repaint-if-changed
}
```

What the runner decides:

- **Fast-path order:** `fetchAndPersist` → `afterFetch` (only when something was persisted, matching
  `SourceFetchTable`'s `stored`) → `paintFromCache` → schedule the tail (only after a successful fetch).
- **Tail contents:** one shared table `observationFeedsFor(sourceIds)`. It says which of NWS gap fill, METAR deep,
  Synoptic and NWS station actuals each served source needs, using the existing shared gates
  (`NwsGapFillRun.dailyForSources`, `MetarFetchPolicy`, `SynopticFetchPolicy`, `SourceFetchTable.followUps`).
  Both platforms run the same set, so the current per-platform difference becomes one rule.
- **Debounce (folds in the earlier `PostSyncTailDebounce`):**
  - delay `TAIL_DELAY_MS = 12_000`
  - a `PENDING` tail is replaced, with the union of source ids
  - a `RUNNING` tail gets one more tail appended, never cancelled
  - total push-out is capped at `TAIL_MAX_DEFER_MS = 60_000`
- **Yield:** at tail start, if `userSyncActive` or an interaction happened in the last 5 s and the cap has not
  been reached, re-schedule the tail. This reuses `BackfillDeferralPolicy`.
- **Repaint gate:** repaint only if `displayedActualsSignature()` changed across `recomputeActuals()`.
- **Logs:** `TARGETED_SYNC stage=fetched|after_fetch|painted|tail_scheduled`, `POST_SYNC_ACTUALS_DEFERRED`,
  `POST_SYNC_ACTUALS_RUN`, `POST_SYNC_ACTUALS_RESULT changed= ms=`.

### 2. Android host (`:app`): `AndroidTargetedSyncHost`

- `FullSyncPipeline`: when `TargetedForecastSyncRun.isTargeted(...)` is true, delegate to `runFast` and return
  `Result.success()`. Delete `runPostPaintBackfillsAndActuals`, the inline `paintFirst` branches and the
  `runBackfills`/`runRecompute` flags the working tree added. Routine and Settings syncs go back to the
  committed code path.
- **Tail scheduling:** unique work `weather_widget_post_sync_actuals`.
  - `replacePending` → `REPLACE` on `ENQUEUED` work only. This is delayed work, which Rule 6 allows.
  - `RUNNING` → `APPEND_OR_REPLACE`.
  - `WeatherWidgetWorker` handles `KEY_POST_SYNC_ACTUALS` by calling `runTail`.
- **Tail I/O:** wraps the existing `backfillNwsObservationsIfNeeded`, `metarRefresher`, `synopticRefresher`,
  `dataBundleLoader.fetchDailyActuals(recompute = true)`, `repairFrozenRainChanceIfNeeded`,
  `dailyHistoryUpkeepIfDue`, and `enqueueUiRepaint`.

### 3. Desktop host (`:desktop`): `DesktopTargetedSyncHost`

- `DaemonRuntime` `Kind.FORECAST` with `userRefresh == false && !observationWindow` (a viewing or toggle fetch)
  calls `runFast`. `refreshWithOutcome` gains a `postFetch = DEFERRED` mode that persists, runs `afterFetch`, and
  returns without `runFollowUps` or `runPostFetchBackfills`. Then `publish(loadCached())`.
- **Tail scheduling:** a single daemon-scoped `Job` with `delay(...)`.
  - `replacePending` cancels only a job that is still in its `delay`. That job is pending, not running a fetch.
  - A running tail sets a `rerunAfter` flag instead of being cancelled.
  - Cancellation and the WorkManager crash risk do not apply to desktop, but the runner keeps one rule for both
    platforms.
- **Tail I/O:**
  - observations: `refreshObservations` → the existing `refreshObservationWindow` and `fetchFeedObservations`
  - `runFollowUps` → the existing `runFollowUps`
  - `recomputeActuals` → `recomputeDailyExtremes` + `upkeepIfDue`
  - `repaint` → `publish(loadCached())` + `notifyDataUpdated`
- **Unchanged:** the Refresh button (`user_refresh:*`, `observationWindow = true`) and the launch/wake catch-up keep
  their current paths. On Android the matching paths, Settings refresh and routine syncs, are also unchanged.

### 4. What is not shared (and why)

- **Scheduling primitive:** WorkManager vs. a coroutine `Job`. The decision is shared; only the timer is
  per-platform.
- **Paint:** RemoteViews vs. Compose publish.
- If desktop lacks a METAR or Synoptic deep-pull entry point that the shared feed table asks for, add a desktop
  host method for it. Do not let the table differ by platform.

### 5. Living documentation

- `arch/forecast-and-observation-cadence.md`: one canonical section, "Targeted forecast syncs", for both platforms.
  It covers the order, the shared feed table, the 12 s debounce and 60 s cap, the yield, and the repaint gate.
  Remove `paint_first` and `post_fetch_actuals` wording elsewhere.
- `arch/ARCHITECTURE.md`: list `TargetedForecastSyncRun` among the shared runners.
- `arch/operations.md`: add the new log tags.

## Tests

1. **`:shared` unit, `TargetedForecastSyncRunTest`, with a fake recording `Host`:**
   - `runFast` order: fetch → afterFetch → paint → scheduleTail. No observation or recompute calls happen.
   - A failed fetch: no afterFetch, paint from cache, no tail.
   - Debounce: pending → replace with the union of sources; running → append; capped → keep.
   - `runTail` yields while `userSyncActive`, up to the cap.
   - `runTail` repaints only when the signature changed.
   - Feed table: the same served sources give the same feeds. Cover NWS, Google with NWS-provided actuals,
     Open-Meteo, and Silurian.
   - `isTargeted`: true for a targeted forced sync; false for `target = null`, `uiOnly`, or routine.
2. **`:app` integration, Robolectric with the WorkManager test driver and the real `FullSyncPipeline` plus
   `AndroidTargetedSyncHost` with fake repositories:**
   - A targeted `toggle_api_stale` sync returns before any refresher is called.
   - `afterFetch` was called once.
   - `weather_widget_post_sync_actuals` is `ENQUEUED` at about 12 s, and the full-sync queue is empty.
   - Three toggles leave one tail.
   - A Settings sync runs inline and enqueues no tail.
   - Must fail on the current working tree.
3. **`:desktop` integration, real `DesktopWeatherDatabase` SQLite plus `DaemonRuntime` with a fake service:**
   - A viewing `FORECAST` request publishes **before** `recomputeDailyExtremes` runs.
   - `afterFetch` rows are written.
   - The tail runs once after the debounce and re-publishes only when extremes changed.
4. **Parity guard:** one test drives both hosts' feed selection for the same inputs and asserts the same set
   (in `CrossPlatform*`, next to `CrossPlatformDuplicateNamesTest`).
5. **Gate:** `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.

## Verification

1. **Pixel 7 Pro and Fold 4:** toggle `Google → Meteo → Silurian` on stale sources. Expect:
   - `SYNC_START` for the next source within about 1 s of the previous `TARGETED_SYNC stage=painted`
   - one `POST_SYNC_ACTUALS_RUN` about 12 s after the last tap
2. **Desktop:** toggle the same three sources in the popup. Expect:
   - `TARGETED_SYNC stage=painted` before any `REFRESH extremes=` work
   - one tail
   - the same feed list in `POST_SYNC_ACTUALS_RUN` as on Android for the same sources
3. **`daily_history` on both databases:** rain-chance columns are updated at the toggle, and actuals are updated
   after the tail.

## Implementation Notes (deviations from the plan above)

1. **No `REPLACE` anywhere.** Replacing a pending tail can race into cancelling one that just started
   (Rule 6). The runner instead KEEPs a pending tail, which re-arms itself for the rest of the 12 s when it
   comes due early (`POST_SYNC_ACTUALS_DEFERRED reason=settling`). Enqueue kinds: `SCHEDULE`, `KEEP_PENDING`,
   `APPEND_AFTER_RUNNING`.
2. **The fetch stays with each platform; the runner starts at `onFetched`.** Android's fetch is entangled
   with location resolution, banners and climate normals in `FullSyncPipeline.run`, so the shared rule
   begins after it: `afterFetch` → paint → tail request.
3. **`SourceFetchTable` follow-ups stay with the fetch on both platforms.** Android runs them inside
   `ForecastFetchCoordinator`, so the desktop deferred mode keeps them too; the tail does not repeat them.
4. **Served sources:** the tail serves requested ∪ visible sources (the runner's rule), so a toggle burst
   lost across a process death still serves what is on screen.
5. **Desktop busy signal:** the daemon sees no clicks, so `lastInteractionMs` is null there; in-flight
   targeted fetches (`activeTargetedSyncs`) are its `userSyncActive`.
6. **Remaining per-platform difference in the tail's feeds:** Android refreshes METAR (deep) and Synoptic
   through their refreshers; desktop runs `refreshObservations(visibleSources = served)`. Both are due-gated;
   unifying them is a separate change.

## Verification Results (2026-11-23)

1. Gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh` passed (7,364 JVM
   tests, all emulator tests, installed on 3 devices, desktop restarted). The first run failed on
   `CrossPlatformDuplicateNamesTest`, `HostContractCoverageTest` and a timing-dependent repaint assertion;
   all three were fixed (allowlisted adapters, `TargetedForecastSyncHostContract` on both platforms,
   repaints counted at the host).
2. Pixel 7 Pro, toggle to a stale NWS (`viewing_forecast_stale`): `FORECAST_FETCH ok ms=3891` →
   `worker_paint_done` 17:09:14.882 → `TARGETED_SYNC stage=painted` → `tail_scheduled` 17:09:14.914, with no
   Synoptic/METAR/NWS work in the toggle's worker. The tail ran as its own job (served 5 visible sources,
   `NWS_DAILY_BACKFILL_RESULT need=none`, Synoptic stored 180, `changed=false ms=3607`, no repaint).
   Toggles to fresh sources (`refresh=FRESH`) started no sync, as before.
   **Observed:** with the screen off, JobScheduler held the 12 s tail until the screen woke (~4.5 min).
3. Desktop, popup open on a 141-min-old WEATHER_API: `REFRESH … extremes=deferred` 17:06:17.797 →
   `TARGETED_SYNC stage=painted` 17:06:18.302 → tail 17:06:30.308 (12.0 s), served 7 visible sources,
   `changed=false ms=1711`. The desktop source-switch path was not exercised live (covered by
   `refreshTargeted`, shared with the viewing path).
