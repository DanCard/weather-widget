# Stored-data version invalidates the widget interaction cache

## Problem

On the Pixel 7 Pro and emulator-5556 (2026-10-29), the user tapped Refresh for NWS on the Forecast
History screen. The fetch took about 0.3 s, but the widget kept showing Saturday's old rain chance
(12 %) for 46–70 s before it switched to the new one (21 %, night 38 %).

### Evidence

Emulator-5556 (`backups/20261029_124438_sdk_gphone64_x86_64_emulator-5556`, `app_logs` and logcat):

| Time | Event | Sat (2026-10-31) drawn |
|---|---|---|
| 12:42:06 | Widget Refresh tap: `refresh_action_cache_first` loads `WidgetInteractionCache` | 12 % |
| 12:42:12 | Home button → NWS (`SOURCE_SWITCH_E2E`), painted from the same cache entry | 12 % |
| 12:42:18.732 | History refresh saves the new NWS row: `forecasts.fetchedAt=1793302938732`, `precipProbability=21`, `hourlyNightPrecipMax=38` | — |
| 12:42:18.814 | `HISTORY_REFRESH_DONE ok=true ms=325`; `sendUiOnlyWidgetRefresh` → `ACTION_REFRESH` with `EXTRA_UI_ONLY=true` | — |
| 12:42:18.887–19.021 | `DailyInteractionRenderer` `dailyDataMs=4`, `Direct cache repaint uiOnly=true`; `dayRainLabel date=2026-10-31 text="12%"`, night `"12%"` | **12 % (stale)** |
| 12:43:05 | An unrelated `OBS_HOURLY_BACKFILL` worker repaints through `WidgetRenderer` (`origin=WORKER_CACHE`) | **21 % / night 38 %** |

Pixel 7 Pro (`backups/20261029_124410_pixel_7_pro_2A191FDH300PPW`): the same sequence. The new row
was saved at 12:40:13, the cache-path repaint ran at 12:40:14, and the backfill worker's repaint
caught up at 12:41:24.

### Root cause

`WidgetInteractionCache` keeps the daily forecast rows, actuals, snapshots and hourly for each
`(lat, lon, day, window)` for `TTL_MS = 60_000`. It is cleared only in two places:

- `WidgetActionReceiver` for a user-initiated (non-`uiOnly`) refresh
- `WidgetRenderer` for a non-`uiOnly` paint

Every repaint that follows a screen fetch is sent `uiOnly` ("don't touch the network"). That means it
also never clears the cache, so it repaints the copy loaded before the fetch. The cache's KDoc still
describes the 2 s TTL ("Correctness never depends on invalidation; the TTL alone bounds it"), which
stopped being true when the TTL was raised to 60 s.

Other paths with the same exposure:
- `WeatherObservationsActivity` (`repaintWidgetAfterDetach`, `onDestroy` after a fetch)
- `ScreenOnReceiver`'s paint-debt repaint
- `HourlyOnDemandWorker` when its repaint takes the interaction path

Desktop has no interaction cache. Its windows reload on `dataUpdateCount`, so the bug is
Android-only. The invalidation rule still belongs in `:shared` (below), so both platforms get it the
same way.

## Design

The rule "a stored-data write makes every cached read of that data stale" moves into `:shared`. It
attaches to the shared runners that already own the writes, so no caller has to remember it.

1. **`StoredDataVersion` (`:shared`, `com.weatherwidget.shared.util`)**
   - A monotonic `AtomicLong` with `current()` and `bump(reason: String)`; `StoredDataVersion.process`
     is the process's one instance, and each runner and cache takes an instance (defaulting to it) so
     tests get their own.
   - No Android types.
   - `bump` logs `Log.v` only (`STORED_DATA_VERSION reason=… version=…`), because it fires on
     every write.

2. **Bump at the shared write points, not at call sites**
   - **`ForecastPersistRun.persist`:** bump once after its writes when it wrote anything
     (`liveHours`, `snapshotHours`, `dailyWritten` or `dailyRestamped` > 0). This one shared path
     covers every forecast write on both platforms: worker syncs, `fetchSourceOnDemand` (History
     refresh, `HourlyOnDemandWorker`), user refreshes and the desktop's
     `DesktopWeatherRepository`.
   - **`CurrentConditionsRun.run`:** bump when a feed's `Host.fetch` returned a stored `Reading`.
     This covers the Observations screen's `refreshCurrentTemperature` and the charging loop.
   - **`NwsGapFillRun` (`daily`, `recent`, `dailyForSources`):** bump after each station's
     `host.store` and after each `host.recomputeExtremes`. It is an `object`, so the version is a
     defaulted parameter rather than a constructor argument.

3. **`VersionedTtlCache<K, V>` (`:shared`)**
   - The generic core of today's `WidgetInteractionCache`, with no Android types:
     - an injected clock
     - TTL
     - a per-key `Mutex` so one load serves concurrent callers
     - lazy expiry
   - New: each entry stores the `StoredDataVersion` value **read before its loader started**. A
     write that lands while a load is in flight therefore makes that entry stale immediately, instead
     of letting it be served for 60 s.
   - `get` misses on TTL expiry **or** version mismatch.

4. **`WidgetInteractionCache` (`:app`) becomes a thin wrapper**
   - It keeps its `Key` (3 dp quantization, window), its `Data` (Room entities) and the existing
     `TTL_MS`.
   - It delegates to `VersionedTtlCache` with `SystemClock.elapsedRealtime`.
   - The KDoc is rewritten to state the 60 s TTL and the version rule.
   - The two explicit `clear()` calls stay. They are cheap, and a non-`uiOnly` paint should still
     load fresh.

5. **Desktop: no new consumer**
   - Desktop gets the bumps for free through the shared runners. Its `dataUpdateCount` path already
     reloads after each in-process refresh, and daemon writes happen in another process, so a
     process-local version could not see them anyway.
   - Wiring the version into `dataUpdateCount` would add a reload per source mid-refresh without
     fixing a bug, so it is left out.
   - The shared contract test (below) still runs against the desktop hosts, so the bump behavior is
     pinned on both platforms.

### Rejected alternatives

- **An `EXTRA_DATA_CHANGED` flag on each post-fetch `uiOnly` broadcast.** Every sender has to
  remember it. A sender that didn't remember is exactly how this bug happened.
- **Calling `WidgetInteractionCache.clear()` from `ForecastRepository`.** A data → widget layering
  inversion that is Android-only and misses `CurrentConditionsRun`.
- **Dropping the TTL back to 2 s.** This undoes the tap-burst and screen-on reuse the 60 s was raised
  for, and still leaves a window.

## Phases

1. **Audit write paths.** List every `forecasts`, `hourly_forecasts` and observation write reached
   from a screen or on-demand fetch on both platforms. Confirm each goes through
   `ForecastPersistRun`, `CurrentConditionsRun` or a shared gap-fill run, and record any direct DAO
   write that bypasses them (for example, rain-chance repairs and climate gap-fill). A bypass that a
   `uiOnly` repaint can follow gets a bump at its shared owner, or is listed here as worker-only
   (worker paints already clear the cache).
2. **`:shared`.** Add `StoredDataVersion` and `VersionedTtlCache`. Add the bumps in
   `ForecastPersistRun`, `CurrentConditionsRun` (and the gap-fill run per phase 1).
3. **`:app`.** Re-base `WidgetInteractionCache` on `VersionedTtlCache`, keeping its public API so
   `DailyInteractionRenderer`, `DailyLoadWindowResolver` and the existing tests are unchanged. Fix the
   KDoc.
4. **Docs.** `arch/hourly-forecast-update-cadence.md` gains an "Interaction cache and stored-data
   version" note under "Targeted and early refreshes": the 60 s TTL, the version rule, and that a
   `uiOnly` repaint after a fetch draws the fetched rows.

## Tests (all must fail on the current code where marked ✗)

**`:shared` JVM**
- `StoredDataVersionTest`: monotonic, `StateFlow` reflects bumps.
- `VersionedTtlCacheTest`:
  - hit within TTL at the same version
  - ✗-equivalent: miss after a bump within TTL
  - miss after TTL
  - one load for concurrent callers
  - **a bump during an in-flight load leaves that entry stale on the next `get`**
  - keys stay independent
- `ForecastPersistRunTest`:
  - ✗ `persist` that writes daily rows bumps the version exactly once
  - a no-op persist (empty fetch) does not bump
- `CurrentConditionsRunTest`:
  - ✗ a stored reading bumps
  - a feed returning null or throwing does not

**Shared contract:** dropped during implementation. The bump lives in the runner, not the host, so a
host contract would only re-test the runner; `ForecastPersistHostContract` already pins both hosts to
`ForecastPersistRun`.

**`:app` Robolectric integration (2+ classes)**
- `DailyInteractionRendererStaleAfterFetchTest`, mirroring the incident:
  1. Seed NWS Sat at 12 % and load through `DailyInteractionRenderer.loadData`, which fills the
     cache.
  2. Persist a new NWS fetch (21 %) through `ForecastPersistRun` + `RoomForecastPersistHost`.
  3. Load again within TTL and assert the 21 % row.
  4. ✗ On current code, step 3 returns the cached 12 % row.
- Existing `WidgetInteractionCacheTest` and `DailyInteractionRendererMultiSeriesCacheIntegrationTest`
  pass unchanged.

**Runtime verification (emulator-5556, then Pixel)**
1. `./gradlew installDebug` (not concurrently with instrumentation).
2. On emulator-5556:
   1. Tap widget Refresh, then the home button (this fills the cache).
   2. Open History for a forecast day and tap Refresh for NWS (or any source past its 10-minute
      throttle).
   3. Confirm the repaint right after `HISTORY_REFRESH_DONE` shows the stored row's values: logcat
      `dayRainLabel` text matches `forecasts.precipProbability` for the newest `fetchedAt`, and
      `DailyInteractionRenderer dailyDataMs` reflects a reload, not ~4 ms.
3. Repeat with the Observations screen refresh and check the current temperature.
4. Desktop: `./gradlew :desktop:createDistributable`, restart, History refresh, and confirm there is
   no behavior change.

## Files

- New: `shared/src/main/kotlin/com/weatherwidget/shared/util/StoredDataVersion.kt`
- New: `shared/src/main/kotlin/com/weatherwidget/shared/util/VersionedTtlCache.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/util/ForecastPersistRun.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/observations/CurrentConditionsRun.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/observations/NwsGapFillRun.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetInteractionCache.kt`
- Tests as above
- `arch/hourly-forecast-update-cadence.md`

## Outcome

- Phase 1 audit: every write a UI-only repaint can follow goes through `ForecastPersistRun`,
  `CurrentConditionsRun` (whose `Host.fetch` stores the reading and recomputes `daily_history`) or
  `NwsGapFillRun`. Repairs and climate gap-fill run only in worker syncs, whose paints clear the cache.
- `StoredDataVersionTest` merged into `VersionedTtlCacheTest` (the version has no other behavior).
- `DailyInteractionRendererStaleAfterFetchTest` fails without the `ForecastPersistRun` bump with
  `expected:<21> but was:<12>`, the incident's values.
