# Refresh the newly-selected source when the user toggles the API indicator

**Date:** 2026-07-20
**Goal:** Combat staleness in non-primary sources. When the user taps the API indicator to switch
sources, trigger a forced refresh of *that source* if its data is missing **or older than 15 minutes**.

## Background — what already exists

`WidgetIntentRouter.handleToggleApiInternal` already ends with a conditional forced refresh:

```kotlin
if (missingDataForSelectedSource) {
    RefreshScheduler.enqueueForcedRefresh(context)
}
```

Two gaps make this ineffective for the staleness case:

1. **The gate only detects absence, not age.** `sourceDataMissingForCurrentWindow` returns
   `sourceDaily.isEmpty() || sourceHourly.isEmpty() || !hasRequiredFutureCoverage`. A throttled
   non-primary source with 9-hour-old rows that still span today+2 passes this gate as "fine".

2. **The refresh is untargeted → fans out to every enabled source.**
   `RefreshScheduler.enqueueForcedRefresh` never sets `WeatherWidgetWorker.KEY_TARGET_SOURCE`, so
   `ForecastRepository.getWeatherData` receives `targetSourceId = null`, and:

   ```kotlin
   if (targetSourceId == null) return true // Force all if no target specified
   ```

   One indicator tap currently force-fetches NWS + Open-Meteo + Silurian + Tomorrow.io + WeatherAPI +
   Visual Crossing + OpenWeatherMap — real quota burn on the key-based sources.

Good news on the plumbing: `KEY_TARGET_SOURCE` **already exists** and the worker already reads it
(`WeatherWidgetWorker.kt:61`) and passes it to `getWeatherData` (`:180`). `WeatherWidgetProvider.kt:698`
already uses it for the day-click path. Only `enqueueForcedRefresh` lacks the parameter.

## Changes

### 1. `RefreshScheduler.enqueueForcedRefresh` — add an optional target source

`app/src/main/java/com/weatherwidget/widget/handlers/RefreshScheduler.kt`

Add `targetSourceId: String? = null`; when non-null, `putString(WeatherWidgetWorker.KEY_TARGET_SOURCE, it)`.
Default `null` preserves today's force-all behavior for every existing caller — no behavior change
anywhere else.

### 2. Extract a pure staleness/missing decision function

`app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`

Per [[testing-strategy]] (no mocking framework — prefer pure-function extraction), split the DB reads
from the decision:

```kotlin
internal const val TOGGLE_REFRESH_STALE_MS = 15 * 60 * 1000L // 15 min

internal data class SourceWindowState(
    val hasDaily: Boolean,
    val hasHourly: Boolean,
    val hasRequiredFutureCoverage: Boolean,
    val newestFetchedAtMs: Long?,   // max across both streams; null when no rows at all
)

@VisibleForTesting
internal fun sourceNeedsRefresh(state: SourceWindowState, nowMs: Long): Boolean {
    if (!state.hasDaily || !state.hasHourly || !state.hasRequiredFutureCoverage) return true
    val fetchedAt = state.newestFetchedAtMs ?: return true
    return nowMs - fetchedAt >= TOGGLE_REFRESH_STALE_MS
}
```

Rename `sourceDataMissingForCurrentWindow` → `sourceWindowState(...)`, returning the data class
instead of a `Boolean`. Its existing query logic is unchanged, including the
`GraphDataLoader.unifyToNearestSite` call that stops a frozen fragment from an earlier GPS fix
satisfying the gate (see [[daily_noon_cloud_refresh_path_unmerged]]).

**Why `max` of the two streams, not `min`:** `newestFetchedAtMs` should mean *"when did we last
successfully fetch this source"*. Some sources legitimately populate one stream more sparsely than
the other; taking the min would mark such a source permanently stale and refetch on every single
toggle. Both `ForecastEntity.fetchedAt` and `HourlyForecastEntity.fetchedAt` exist, and
`loadGraphWindowHourlyForecasts` returns `List<HourlyForecastEntity>`, so both are readable at the
call site without new queries.

### 3. Wire the toggle handler

In `handleToggleApiInternal`, replace the `missingDataForSelectedSource` gate:

```kotlin
if (sourceNeedsRefresh(state, System.currentTimeMillis())) {
    RefreshScheduler.enqueueForcedRefresh(
        context,
        reason = "toggle_api_stale",
        targetSourceId = newSource.id,
    )
}
```

**Deviation from the original plan — the enqueue moved *before* the repaint.** The plan said to keep
it after `refreshGraphView` / `refreshDailyView`, as it is today. Implementing the test surfaced why
that is wrong: `refreshDailyView` throws (`NullPointerException: Cannot read field "layoutId"
because "widgetInfo" is null`) whenever the widget is not host-bound, and `handleToggleApi`'s outer
`catch` swallows it — so the enqueue after it never runs. That is not merely a Robolectric artifact:
in production, **any** render failure would also silently cancel the network fetch, meaning a broken
widget suppresses the data refresh most likely to fix it (cf.
[[samsung_widget_dead_native_sigsegv]], [[widget_blank_selfheal_render_ok]]).

The enqueue is a non-blocking WorkManager hand-off, so ordering it first does not delay the
cache repaint; the network result still lands on the worker's later partial push
(see [[widget_worker_partial_push]]).

A distinct `reason` string keeps this path greppable in `app_logs` (`NET_FETCH_START` already logs
`force=` and `target=`).

## Throttling — no separate cooldown needed

The 15-minute staleness check **is** the per-source cooldown. After a toggle-triggered fetch, that
source's `fetchedAt` is young, so toggling away and back is a no-op. This makes the feature
idempotent without new state.

Note that `forceRefresh = true` bypasses `MIN_NETWORK_INTERVAL_MS` (10 min) in
`ForecastRepository.getWeatherData` by design — that global throttle stays bypassed, but with change
(1) the blast radius is one source instead of seven, and with the age gate a user cycling the
indicator through six sources fetches each at most once per 15 min.

## Tests

- **New pure unit test** for `sourceNeedsRefresh`: fresh-and-complete → false; missing daily → true;
  missing hourly → true; no future coverage → true; complete but `fetchedAt` 16 min old → true;
  complete and 14 min old → false; `newestFetchedAtMs == null` → true. Needs `@Category` per
  [[daily_noon_cloud_refresh_path_unmerged]].
- **Extend `DailyViewApiToggleIntegrationRoboTest`** to assert the refresh is *targeted*. That test
  sets `RefreshScheduler.setIsRefreshDisabledForTesting(true)`, which suppresses the enqueue
  entirely, so rather than standing up a WorkManager harness the test-mode early-return now records
  what it *would* have enqueued in `RefreshScheduler.lastForcedRefreshForTesting`
  (`ForcedRefreshRequest(reason, targetSourceId)`). The test asserts each toggle records
  `targetSourceId == newSource.id` and `reason == "toggle_api_stale"`.

  The test DB has no rows, so this exercises the *missing-data* arm; the staleness arm is covered by
  the pure unit test above. It also now pins the enqueue-before-repaint ordering — it fails if the
  enqueue moves back below `refreshDailyView`.
- Re-run `WidgetIntentRouterCrashSafetyRoboTest` and `CloudCoverViewModeRoboTest` (both touch the
  renamed function).

## Verification

```bash
./gradlew testDebugUnitTest --tests "*WidgetIntentRouter*" --tests "*ApiToggle*"
./gradlew installDebug
```

Then on-device: toggle the indicator to a long-throttled source and confirm in `app_logs` a
`NET_FETCH_START` with `force=true target=<that source id>` and exactly that source in the
subsequent `NET_FETCH_COMPLETE sources=` list.

## Out of scope

- Changing `MIN_NETWORK_INTERVAL_MS` or the background battery-aware intervals.
- The desktop app's equivalent source-switch path (worth a follow-up given
  [[feedback_share_android_desktop_logic]], but the toggle handler here is Android-widget-specific).
