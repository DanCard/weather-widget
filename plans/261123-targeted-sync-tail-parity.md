# Targeted-Sync Parity: One Shared Rule for Triggers, Tail Feeds, Retry, Recompute Window and Busy Signal

Follow-up to [`261123-shared-targeted-sync-paint-then-tail.md`](261123-shared-targeted-sync-paint-then-tail.md)
(commit `2fd02edae`). That change shared the order, debounce and yield; its "Implementation Notes" listed what
still differs. User, 2026-11-23: "fix differences between android and desktop, make them work the same, use
shared code".

## Evidence: What Still Differs (from code, 2026-11-23)

| # | Behavior | Android | Desktop |
|---|---|---|---|
| 1 | **Tail observation feeds** | `MetarObservationRefresher` (deep 24 h) + `SynopticObservationRefresher`, tiered by the shared `MetarFetchPolicy` / `SynopticFetchPolicy` over the background sources | `refreshObservations(visibleSources)`: the current-conditions run (`CurrentConditionsRun` feeds). METAR/Synoptic only when the **displayed** source borrows one, recent window. It also re-runs recompute + `afterFetch` + upkeep, so the tail does them twice |
| 2 | **NWS fill unreachable** | No retry in the tail | `ObservationWindowRetry` (desktop-only object) on the shared `ObservationBackfillRetryPolicy` delays |
| 3 | **Recompute window** | `fetchDailyActuals(recompute=true)` → `DailyBlendRecomputeRun.range(today−2 … yesterday)` | `recomputeDailyExtremes` → `DailyBlendRecomputeRun.range(today−9 … today)` |
| 4 | **"Did actuals change" signature** | Displayed `DailyActualsBySource` (30-day read, live today) | `daily_history` rows `today−9 … today` |
| 5 | **Busy signal for the yield** | Any forced non-UI sync in flight (`BackfillDeferralPolicy`) or a widget interaction < 5 s ago | Only targeted fast paths in flight; `lastInteractionMs = null` |
| 6 | **Refresh button for the displayed source** | `widget_tap_to_refresh`: targeted, so fast path + tail | Popup/panel Refresh sends `observationWindow = true`, so the full inline path (recompute before publish) |
| 7 | **Where the shared rule decides what the tail does** | Feed selection, recompute window and signature are in `AndroidTargetedSyncHost` | Same three in `DesktopTargetedSyncHost` |

Root cause: the first change shared only the ordering. Each host still decided its own I/O content, so the two
hosts' "fill feeds / recompute / compare" steps were written separately and drifted.

## Proposed Fix

### 1. `:shared` `StationNetworkRefreshRun`: METAR and Synoptic in one rule (items 1, 7)

New `shared/.../observations/StationNetworkRefreshRun.kt`. It owns, for each feed:
- the tier (`MetarFetchPolicy.tierFor` / `SynopticFetchPolicy.tierFor` over the host's background sources)
- the window (`MetarFetchWindow.hours` or an explicit deep window; `SynopticFetchWindow.recentMinutes`)
- the Synoptic backoff gate (`SynopticFetchGate`, already shared)
- the shared write (`ObservationWriteRun`)
- the log lines (`METAR_OBS_STORED|FAIL`, `SYNOPTIC_OBS_STORED|FAIL`, unchanged text)

`Host` supplies only: background sources, displayed source ids, the actuals preference, the newest stored
timestamp per feed, the two network fetches, the write, and the log.

- **Android:** `MetarObservationRefresher` and `SynopticObservationRefresher` become thin hosts over it. Their
  existing call sites (current-temp loops, full sync) are unchanged.
- **Desktop:** a new `DesktopStationNetworkHost` over `DesktopWeatherService`'s METAR and Synoptic fetchers,
  `DesktopSourceFetchGate`, and `DesktopObservationWriteHost`.

### 2. `TargetedForecastSyncRun` owns the tail's content, not just its order (items 1–4, 7)

The Host's coarse steps become narrow I/O, so the runner decides what runs:

| Runner step (shared) | Host I/O |
|---|---|
| History fill for served sources; on `unreachable`, re-arm the tail on `ObservationBackfillRetryPolicy` delays (10 s → 1 m → 4 m), logged `OBS_HOURLY_BACKFILL_RETRY reason=post_sync_actuals` (**item 2**) | `fillObservationHistory(sources): NwsGapFillRun.Result?` |
| `StationNetworkRefreshRun` for both tiers, METAR deep window (**item 1**) | `stationNetworkHost()` |
| Recompute `DailyBlendRecomputeRun.range(today − MAX_DAYS_BACK … today)` (**item 3**; Claude's proposal, the wider of the two) | `blendRecomputeHost()` (both platforms already have one) |
| Platform repairs + `DailyHistoryRun.upkeepIfDue` | `repairsAndUpkeep()` (Android: frozen-rain-chance repair; desktop: UTC-date repair; both one-time install repairs) |
| Signature = `daily_history` rows in the same window (**item 4**) | `dailyHistoryRows(startKey, endKey)` |

- Desktop's `ObservationWindowRetry` is no longer used by the tail. Its launch-refresh use stays.
- Desktop's tail stops calling `refreshObservations`, which removes the duplicate recompute, `afterFetch` and
  upkeep.
- **Android today row (item 3):** Android assembles today live (`getDailyActualsWithLiveToday`), so a stored
  today row is not what it draws. The plan adds a test that the displayed today is unchanged after a tail
  writes it.

### 3. One busy signal (item 5)

- `userSyncActive` = any user-forced forecast fetch in flight.
  - Android: unchanged.
  - Desktop: count every user `FetchRequest` (FORECAST, OTHER_SOURCES) and the source-switch launch refresh,
    not just the targeted ones.
- `lastInteractionMs`: the daemon records the time of every user request it receives (fetch requests, source
  switch). This is the desktop counterpart of Android's widget-tap timestamp, read through the same 5 s window.

### 4. One trigger rule (item 6)

- The desktop popup/panel Refresh becomes targeted (`observationWindow = origin == "settings"`), like
  Android's `widget_tap_to_refresh`. The observation window is filled by the tail's history fill.
- Settings "Refresh data" (every source) stays inline on both platforms.
- A shared predicate `TargetedForecastSyncRun.isTargeted(...)` is used on desktop as well. Its input is
  `userForced && singleSource && !allSources`, from each platform's request model.

### 5. Not changed (and why)

1. **Desktop has no tiered METAR/Synoptic background loops** (Android's current-temp and non-primary loops).
   This plan gives desktop the shared refresh run and uses it in the tail only. Adding desktop's loops is a
   separate change; tell me if you want it here too.
2. **Desktop's "paint" loads the snapshot and its caller publishes it right after.** On Android the paint
   draws the widgets. The behavior is the same; the launch path's publish keeps its own checks.
3. **Android's Refresh tap still also enqueues `enqueueHistoryGapFill`.** It is need-gated, so running it
   alongside the tail's fill costs no extra request.

### 6. Living documentation

- `arch/forecast-and-observation-cadence.md#targeted-forecast-syncs`: update the tail steps, retry, window,
  busy signal and triggers. Replace the per-platform feed sentence with `StationNetworkRefreshRun`.
- `arch/ARCHITECTURE.md`: list `StationNetworkRefreshRun`.
- `ActualsFeedPolicy.STATION_NETWORK_FEEDS` KDoc: name the shared run.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `StationNetworkRefreshRunTest`: tier gating (NONE fetches nothing; PRIMARY/NON_PRIMARY accepted only when asked), METAR window (explicit deep vs gap), Synoptic through the backoff gate, write + log line | unit, `:shared` fake host |
| 2 | `StationNetworkHostContract`: newest-stored timestamp and write round-trip on Room and desktop SQLite | contract, both platforms |
| 3 | `TargetedForecastSyncRunTest`: unreachable fill re-arms on 10 s / 1 m / 4 m then stops; recompute window = today−9…today; signature compare on rows; station step runs both tiers with the deep window | unit |
| 4 | `TargetedSyncPostActualsIntegrationTest` (Android): tail runs the shared station run; unreachable fill enqueues a 10 s tail; Android's displayed today is unchanged by the tail's stored today row | integration, Robolectric + real WorkManager + Room `TestDatabase` |
| 5 | `DesktopTargetedSyncIntegrationTest`: tail fetches METAR/Synoptic for a background borrower (it did not before); no `refreshObservations` call; unreachable retry through the tail; popup Refresh request takes the fast path | integration, real SQLite |
| 6 | `CrossPlatformDuplicateNamesTest` / `HostContractCoverageTest` stay green | guards |
| 7 | Every new assertion fails on `2fd02edae` (checked by running them against it once) | regression |
| 8 | Gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh` | gate |

## Verification

1. Pixel (screen on): toggle to a stale source. Then:
   - `POST_SYNC_ACTUALS_RUN`, then `METAR_OBS_STORED` / `SYNOPTIC_OBS_STORED` with the same text as before,
     then `POST_SYNC_ACTUALS_RESULT`
   - no extra `NWS_DAILY_BACKFILL_RESULT` beyond one per tail
2. Desktop: popup Refresh on the displayed source. Then:
   - `TARGETED_SYNC stage=painted` before any recompute, and `REFRESH … extremes=deferred`
   - a tail with `METAR_OBS_STORED` / `SYNOPTIC_OBS_STORED` when a visible source borrows them
   - no `OBS_REFRESH` from the tail
3. Both: compare the `POST_SYNC_ACTUALS_*` and station-network lines for the same sources. They should show the
   same steps in the same order.
