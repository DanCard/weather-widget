# Plan: Auto-fetch observations when the observations screen is empty

Date: 2026-08-15

## Problem

On the Samsung (and any device), opening the **Current Observations** screen
(`WeatherObservationsActivity`, Android) / **Observations window** (`ObservationsWindow`, desktop)
with an empty observation table shows a dead-end message — "No recent observations found for NWS." —
and does nothing else. The user has to notice the message and press the manual refresh button.

The screen should instead **fetch observations automatically** when the list comes back empty, and
only fall back to the "no recent observations" message if that fetch genuinely returns nothing.

Both platforms are in scope (project dual-platform rule); Android is primary because that is the
reported device.

## Evidence collected

### Android — the screen is read-only on open

1. `app/.../ui/WeatherObservationsActivity.kt`
   - `onCreate()` → `loadObservations()` (L531) only **reads** the DB
     (`observationRepository.getRecentObservationsNear(24h)`), filters to `currentSource`, and when
     the list is empty sets `R.string.obs_subtitle_none_found` = "No recent observations found for
     %1$s." (L603). No fetch is triggered anywhere in the load path.
   - The only fetch trigger is the manual refresh button → `refreshData()` (L307) →
     `weatherRepository.refreshCurrentTemperature(..., source = currentSource, forceRefresh = true)`
     (L322-329).
   - `observeCurrentObservationUpdates()` (L288) re-runs `loadObservations()` on any observation /
     fetch-log DB change (debounced `autoRefreshDebounceMs = 500ms`), so the list already refreshes
     itself **after** a successful fetch — the missing piece is only *initiating* the fetch.
2. `app/.../data/repository/CurrentTempRepository.kt`
   - `refreshCurrentTemperature` (L116) has a **5-minute freshness gate** at L135:
     `if (!forceRefresh && currentTime - lastFetchTime < CURRENT_TEMP_FRESHNESS_MS)` → returns
     `success(0)` with no network. `CURRENT_TEMP_FRESHNESS_MS = 300_000L` (L74).
   - `lastFetchTime` is stamped **unconditionally** at L210 after the source loop, i.e. even a
     *failed* fetch updates it. This is the key loop-prevention primitive for the auto-fetch: an
     empty-state fetch that fails cannot be re-triggered by the debounced reload for 5 minutes.
   - `source = currentSource` restricts the fetch to the selected source (matches the screen's
     scope); `forceRefresh = true` bypasses the gate (manual button), `false` respects it.

### Desktop — same dead-end, heavier refresh callback

3. `desktop/.../ObservationsWindow.kt`
   - Empty state text at L569: `"No recent observations found"` — render-only, no fetch.
   - `loadData` (L245-ish) loads `visibleStationRows(weatherDao.getRecentObservations(sinceMs), currentSource)`
     and is re-run by `LaunchedEffect(currentSource, logFilter, dataUpdateCount, showRequestId, selectedTab)`.
   - The refresh control is caller-owned: `ObservationRefreshButton` (L451) → `onRefreshData`.
4. `desktop/.../Main.kt`
   - `ObservationsWindow(...)` is wired with `onRefreshData = { requestFullRefresh("observations") }`
     and `isRefreshing = refreshInFlight` (L830-838).
   - `requestFullRefresh(origin)` (L757) already guards re-entrancy (`if (repo == null || refreshInFlight)`
     at L768) and bumps `dataUpdateCount` after `repo.refresh()` completes, which re-runs the
     window's `loadData`.
   - **Desktop quirk:** `onRefreshData` is a **full** refresh of `config.settings.weatherSource`,
     not the window's independently-cycled `currentSource`. The manual button has the same behavior
     today, so auto-fetch on empty is no worse — but it is "best effort" when the tab's source
     differs from the configured source. Flagged as a known limitation, not fixed here.

## Design decisions

1. **Auto-fetch must be silent.** No toast, no refresh-button disabled state. The manual button keeps
   its existing toast/disable UX. Give the auto-fetch its own `reason` string so it is queryable in
   the Fetch Logs tab.
2. **Loop prevention is `forceRefresh = false`, not a bespoke lock.** Routing through the existing
   5-minute freshness gate means an auto-fetch that fails (offline, no NWS stations, 429) stamps
   `lastFetchTime` and cannot be re-triggered by the debounced DB-change reload for 5 minutes. At
   most one real network attempt per 5 minutes per empty state.
3. **One-shot per empty state, re-armed on data.** A per-source `autoFetchFiredForSource` flag fires
   the auto-fetch at most once while the list is empty, and resets when the list becomes non-empty
   (or the source is cycled). This makes the behavior deterministic and unit-testable, and lets the
   auto-fetch fire again later if observations age out of the 24h window. The freshness gate is the
   backstop; the flag is the determinism.
4. **Auto-fetch marks the widget repaint flag too.** Fetching observations changes the blend/current
   dot the widget paints, so `widgetContentChanged = true` is set on the auto-fetch path as well
   (UI-only repaint on exit, same as manual).
5. **Desktop must distinguish "not yet loaded" from "loaded and empty".** The window's `observations`
   state starts empty on every (re)open, so a naive `LaunchedEffect` keyed on emptiness would fire a
   full refresh on every open before the first DB read completes. The trigger must live inside
   `loadData` (after the DB query), not in a separate effect.

## Proposed change

### Step 1 — `:app` factor the fetch out of `refreshData()`

Extract a shared `performFetch(forceRefresh: Boolean, silent: Boolean)` from `refreshData()`
(`WeatherObservationsActivity.kt:307`):

```kotlin
private fun performFetch(forceRefresh: Boolean, silent: Boolean) {
    lifecycleScope.launch {
        val location = activeLocation ?: withContext(ioDispatcher) {
            weatherRepository.getLatestLocation()
        }
        if (location == null) {
            if (!silent) {
                android.widget.Toast.makeText(
                    this@WeatherObservationsActivity,
                    getString(R.string.obs_no_location_to_refresh),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            return@launch
        }
        if (!silent) {
            findViewById<View>(R.id.refresh_button).isEnabled = false
            findViewById<View>(R.id.refresh_button).alpha = 0.5f
        }
        withContext(ioDispatcher) {
            weatherRepository.refreshCurrentTemperature(
                location.first,
                location.second,
                if (silent) "Observations Auto-fetch" else "Manual Refresh",
                source = currentSource,
                reason = if (silent) "observations_empty_autofetch" else "user_observations_screen",
                forceRefresh = forceRefresh,
            )
        }
        widgetContentChanged = true
        loadObservations()
        loadFetchLogs()
        if (!silent) {
            findViewById<View>(R.id.refresh_button).isEnabled = true
            findViewById<View>(R.id.refresh_button).alpha = 1.0f
            android.widget.Toast.makeText(
                this@WeatherObservationsActivity,
                getString(R.string.obs_refreshed_source, currentSource.shortDisplayName),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
```

`refreshData()` becomes `performFetch(forceRefresh = true, silent = false)`. No behavior change to
the manual path.

### Step 2 — `:app` auto-fetch on empty in `loadObservations()`

Add a field: `private var autoFetchFiredForSource: WeatherSource? = null`.

In the `withContext(Dispatchers.Main)` block of `loadObservations()`, in the `observations.isEmpty()`
branch (currently L602-603):

```kotlin
if (observations.isEmpty()) {
    subtitleView.text = getString(R.string.obs_subtitle_none_found, currentSource.displayName)
    // Fetch instead of just reporting the gap. One-shot per empty state; the
    // forceRefresh=false freshness gate (5 min) is what prevents a reload loop when
    // the fetch fails and the debounced DB-change reload re-enters this branch.
    if (autoFetchFiredForSource != currentSource) {
        autoFetchFiredForSource = currentSource
        performFetch(forceRefresh = false, silent = true)
    }
} else {
    // Data arrived: re-arm the auto-fetch so it can fire again if this source's
    // observations later age out of the 24h window.
    autoFetchFiredForSource = null
    ...
}
```

- Reset `autoFetchFiredForSource = null` in `cycleSource()` (L347) right after `currentSource`
  changes, so each newly-selected source gets its own auto-fetch opportunity.
- Keep the existing "no recent observations" subtitle for the render; the auto-fetch runs in the
  background and the DB-change observer repaints the list when rows land.

### Step 3 — `:desktop` auto-fetch on empty inside `loadData`

In `ObservationsWindow.kt`, add a one-shot state and trigger inside the existing `loadData` lambda
(after the DB read, before the `withContext(Dispatchers.Main)` render):

```kotlin
var autoFetchFiredForSource by remember { mutableStateOf<WeatherSource?>(null) }

val loadData = {
    scope.launch(Dispatchers.IO) {
        val sinceMs = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        val obs = visibleStationRows(weatherDao.getRecentObservations(sinceMs), currentSource)

        // Auto-fetch when the DB genuinely has nothing for this source. Inside loadData, not a
        // separate LaunchedEffect: observations starts empty on every (re)open, so an
        // emptiness-keyed effect would fire before the first DB read and full-refresh every open.
        if (obs.isEmpty() && autoFetchFiredForSource != currentSource) {
            autoFetchFiredForSource = currentSource
            onRefreshData()
        } else if (obs.isNotEmpty()) {
            autoFetchFiredForSource = null
        }

        val recentLogs = weatherDao.getRecentLogsByTags(logFilter.tags, 100)
        val tables = if (selectedTab == TAB_BLEND) {
            loadBlendTables(weatherDao, config, currentSource)
        } else {
            emptyList()
        }
        withContext(Dispatchers.Main) {
            observations = obs
            logs = recentLogs
            if (selectedTab == TAB_BLEND) blendTables = tables
        }
    }
}
```

- `onRefreshData` = `requestFullRefresh("observations")` already has the `refreshInFlight` guard, so
  a re-entrant call while a refresh is in flight is a logged no-op; `dataUpdateCount++` after
  completion re-runs `loadData`, and the `autoFetchFiredForSource` flag prevents a second fire if
  the list is still empty.
- **Known limitation (accepted):** desktop `onRefreshData` refreshes `config.settings.weatherSource`,
  not the window's cycled `currentSource`. Same as the existing manual button; a source-scoped
  desktop refresh is out of scope.

### Step 4 — strings / logging

- No new user-facing string is required; the existing `obs_subtitle_none_found` stays as the "we
  tried and still have nothing" message.
- The new `reason = "observations_empty_autofetch"` appears in the existing `CURR_FETCH_START`,
  `CURR_FETCH_COMPLETE`, `CURR_FETCH_SOURCE_RESULT`, and failure log rows, so the Fetch Logs tab
  shows the auto-fetch without any new tag plumbing. (The desktop uses its existing `REFRESH_CLICK`
  origin `"observations"` from `onRefreshData`; no change.)

## Testing

1. **`:app` pure/extracted unit** — `WeatherObservationsActivityTest` (Robolectric, `@Category(LongDuration)`):
   - Empty observations for the current source + no prior auto-fetch → `refreshCurrentTemperature`
     called once with `forceRefresh = false`, `reason = "observations_empty_autofetch"`, and
     `source = currentSource`.
   - Empty observations + auto-fetch already fired for this source → no second call.
   - Non-empty observations → no auto-fetch call, and the one-shot flag is re-armed (a subsequent
     empty load would fire again).
   - Cycling the source resets the flag so the new source auto-fetches.
   - Manual refresh still calls with `forceRefresh = true` and `reason = "user_observations_screen"`.
   - No location resolvable → auto-fetch is a no-op (no crash, no toast on the silent path).
2. **`:app` loop-invariant check** — assert the auto-fetch path always passes `forceRefresh = false`,
   i.e. it can never bypass the `CurrentTempRepository` freshness gate. This is the invariant that
   makes "reload on DB change" safe.
3. **`:desktop` test** — extend `ObservationsWindow`-related Compose tests (or a pure test on
   `visibleStationRows` + a new `loadData`-decision helper if extracted) to assert: empty + not-yet-fired
   triggers `onRefreshData` once; empty + already-fired does not; non-empty re-arms; and the trigger
   happens only after a load, not on initial composition.
4. **Manual verify (Samsung + emulator)** —
   - Clear/observe an empty NWS observation table, open the screen, confirm the Fetch Logs tab shows
     `observations_empty_autofetch` and the list populates without pressing refresh.
   - Confirm the manual refresh button still behaves as before (toast, disabled state).
   - Confirm no fetch loop: with network off, open the empty screen and watch the Fetch Logs tab —
     exactly one auto-fetch attempt, then silence for ≥5 min.

## Out of scope / notes

1. **The "genuinely nothing" case still shows the message.** If the auto-fetch succeeds but the
   source truly has no stations (or all return QC-failed), the list stays empty and the existing
   "No recent observations found for X." remains — which is now accurate, not a missing feature.
   A future improvement could reword it to "Fetched — still no recent observations" but that is not
   part of this change.
2. **Desktop source mismatch.** `onRefreshData` refreshes the configured source, not the tab's
   cycled source. Acceptable because the manual button already behaves this way; making desktop
   refresh source-scoped is a separate piece of work.
3. **No new work/poll scheduling.** The auto-fetch is a one-shot tied to the screen being open and
   empty; it does not add any background periodic work.
