# API toggle triggers a targeted refresh of the newly-selected source

**Date:** 2026-07-20
**Plan:** `plans/260720-refresh-nonprimary-source-on-api-toggle.md`

Combats staleness in non-primary sources: tapping the API indicator now force-refreshes that source
when its cached data is missing or older than 15 minutes.

## What changed

**`RefreshScheduler.kt`** — `enqueueForcedRefresh` gained `targetSourceId: String? = null`, forwarded
as `WeatherWidgetWorker.KEY_TARGET_SOURCE`. Defaulting to null means every existing caller keeps its
current force-all behavior. Also added a `lastForcedRefreshForTesting` record in the existing
test-mode early-return, so tests can assert targeting without a WorkManager harness.

**`WidgetIntentRouter.kt`** — `sourceDataMissingForCurrentWindow` (returned `Boolean`) became
`sourceWindowState` (returns a `SourceWindowState` snapshot), with the policy split into the pure,
testable `sourceNeedsRefresh(state, nowMs)`. The query logic is untouched, including the
`unifyToNearestSite` guard. The toggle now enqueues a refresh targeted at just the newly-selected
source, with reason `toggle_api_stale`.

**Tests** — 9 new pure policy tests (`SourceNeedsRefreshTest`) including the 14-min/15-min boundary
pair, plus a new integration test in `DailyViewApiToggleIntegrationRoboTest` asserting each toggle
targets only the source switched to.

## Two things worth flagging

**The untargeted-refresh bug was worse than the staleness one.** Because `enqueueForcedRefresh` never
set a target, `getWeatherData`'s `if (targetSourceId == null) return true // Force all` meant every
indicator tap was already force-fetching all seven providers — real quota burn on the key-based
sources. That's now one source per tap.

**Deviated from the plan on ordering.** The plan said keep the enqueue after the repaint; writing the
test proved that's wrong. `refreshDailyView` throws `NullPointerException: Cannot read field
"layoutId" because "widgetInfo" is null` when the widget isn't host-bound, and `handleToggleApi`
swallows it — so the enqueue was unreachable. In production that coupling means *any* render failure
silently cancels the fetch, i.e. a broken widget suppresses the data refresh most likely to fix it.
The enqueue is a non-blocking WorkManager hand-off, so moving it first costs nothing.

## Design notes

- The 15-minute age check **is** the per-source cooldown — after a toggle-triggered fetch `fetchedAt`
  is young, so toggling away and back is a no-op. No separate debounce needed.
- `newestFetchedAtMs` is the **max** across the daily and hourly streams, not the min: it means "when
  was this source last fetched". Min would mark sources that populate one stream sparsely as
  permanently stale and refetch on every toggle.
- `forceRefresh = true` still bypasses `MIN_NETWORK_INTERVAL_MS` (10 min) by design; the age gate is
  what bounds tap-spam now.

## Diagnostics retained

Per user preference, the `ShadowLog.stream = System.out` line stays in the test permanently with a
comment on what it revealed — that's the line that made the swallowed NPE visible (Robolectric drops
`Log` output otherwise, and the handler catches internally so failures never reach the test's catch).
The `println` in the test's own `catch` was dropped: that catch is dead code.

## Status

`assembleDebug` and all affected suites (`WidgetIntentRouter*`, `CloudCover*`, `Toggle*`,
`SourceNeedsRefresh*`) pass.

**Not yet verified on-device.** Verification step: toggle to a long-throttled source and confirm
`NET_FETCH_START force=true target=<id>` in `app_logs`, with only that source in the subsequent
`NET_FETCH_COMPLETE sources=` list.
