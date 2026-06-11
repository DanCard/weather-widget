# Desktop Settings parity with Android: Refresh Data + View App Logs

## Context

The desktop app's Settings screen is missing two controls that Android's Settings has in its
header. The user hit this directly: Android has a **Refresh Data** button to force an immediate
data fetch; desktop has none, so there is no way to manually pull fresh forecasts (e.g. to confirm
a just-landed data fix without waiting for the battery-aware background interval). Android also has
a **View App Logs** button; desktop only surfaces logs inside a tab of the Observations window.

This plan brings those two controls to the desktop Settings window, reusing the desktop's existing
refresh path and log plumbing so the behavior matches Android. The rest of the Settings screen is
already at parity (API sources with reorder, API keys, icon gallery, location editing).

Reference (Android): `app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt` (refresh button
lines 75–122, view-logs lines 124–128) and `AppLogsActivity.kt`.

## Part A — Refresh Data button

Android's button enqueues a forced forecast refresh + a forced current-temp refresh, disables itself
while running, and toasts "Data refresh queued". On desktop, a single `DesktopWeatherRepository.refresh()`
already does the full fetch (forecast + hourly + observations + current-temp resolution), so one call
covers both Android work requests. The UI process already owns the repository and
`ObservationsWindow` already calls `repository.refresh()` behind an `isRefreshing` guard — this
button reuses that exact pattern.

**Changes:**
- `desktop/.../SettingsWindow.kt`
  - Add params `onRefreshData: suspend () -> Unit` and (Part B) `onViewAppLogs: () -> Unit`.
  - In the header row (next to Back/title), add a **Refresh Data** button. Use
    `rememberCoroutineScope()` + a local `var isRefreshing by remember { mutableStateOf(false) }`;
    on click `scope.launch { isRefreshing = true; try { onRefreshData() } finally { isRefreshing = false } }`,
    `enabled = !isRefreshing`. Show a small progress indicator / "Refreshing…" label while running
    (parity with Android disabling the button). `testTag("refresh_data_btn")`.
- `desktop/.../Main.kt` (the `SettingsWindow(...)` call site around line 336, where `repository`
  and `forecast` are already in scope)
  - Pass `onRefreshData = { repository?.let { forecast = it.refresh() } }` (suspend lambda;
    assigning to the existing `forecast` state refreshes the open popup, mirroring `loadCached`).

**Notes:** The UI-process refresh writes to the same SQLite DB the daemon uses (`upsertForecasts`
is REPLACE) and does not interfere with the daemon's scheduling — this is the already-accepted
ObservationsWindow pattern. This button also provides the manual fetch needed to surface the
separately-fixed NWS daily-low behavior immediately rather than at the next background interval.

## Part B — View App Logs window

Mirror Android's `AppLogsActivity`: a scrollable log list with a text filter. Reuse the desktop's
existing `DesktopWeatherDao.getRecentLogs(limit)` (→ `List<DesktopLogEntity>`) and the existing
row renderer.

**Changes:**
- Extract the private `LogList` composable currently in
  `desktop/.../ObservationsWindow.kt` (around line 240) into a shared `internal` composable
  (either a new `LogList.kt` or top-level in a small file) so both the Observations "Fetch Logs"
  tab and the new window render identically. Keep the existing red-on-`FAIL` / green tag styling.
- New `desktop/.../AppLogsWindow.kt`: a `Window` with a header (title + close), a filter
  `OutlinedTextField` (case-insensitive match on tag+message, parity with Android's
  `filterInput`/`applyFilter`), and the shared `LogList` fed by `weatherDao.getRecentLogs(3000)`
  loaded in a `LaunchedEffect`. `testTag("app_logs_window")`.
- `desktop/.../SettingsWindow.kt`: add a **View App Logs** button in the header that calls
  `onViewAppLogs()`. `testTag("view_app_logs_btn")`.
- `desktop/.../Main.kt`: add `var appLogsVisible by remember { mutableStateOf(false) }`; render
  `AppLogsWindow(weatherDao = weatherDao, onClose = { appLogsVisible = false })` when true (pattern
  mirrors the existing `settingsVisible`/`observationsVisible` windows, and include it in the
  `anyWindowOpen` expression at line 176). Wire `onViewAppLogs = { appLogsVisible = true }` into the
  `SettingsWindow(...)` call.

Optional (only if cheap): a "Clear logs" button paralleling Android's `clearAllLogs()`. The desktop
DAO currently deletes by timestamp (retention) but has no clear-all; skip unless trivially added.

## Critical files

- `desktop/src/main/kotlin/com/weatherwidget/desktop/SettingsWindow.kt` — two header buttons + new params
- `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt` — wire `onRefreshData`, `onViewAppLogs`, `appLogsVisible`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/ObservationsWindow.kt` — extract reusable `LogList`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/AppLogsWindow.kt` — new window (Part B)

## Reused existing code

- `DesktopWeatherRepository.refresh()` — full forecast/obs/current-temp fetch (the refresh path)
- `ObservationsWindow.kt` `isRefreshing`/`scope.launch` pattern — the refresh button UX
- `DesktopWeatherDao.getRecentLogs(limit)` + `DesktopLogEntity` — log source
- existing `LogList` row renderer — log display

## Verification

1. Build + restart: `./scripts/build-start.sh`.
2. Open Settings → click **Refresh Data**: button disables + shows progress, then re-enables; the
   open popup updates. Confirm a fresh fetch with
   `python3 scripts/backup_databases.py` then check a new `forecasts.batchFetchedAt` and a fresh
   `REFRESH` row in `app_logs` (timestamps via `datetime(timestamp/1000,'unixepoch','localtime')`).
3. Open Settings → **View App Logs**: window lists recent logs; typing in the filter narrows by
   tag/message; close returns to Settings.
4. Add a desktop UI test alongside `DesktopUiTest.kt` asserting the two new header buttons exist
   (`refresh_data_btn`, `view_app_logs_btn`) and that the logs window opens (`app_logs_window`).
5. Regression: `./gradlew :desktop:test :shared:test` (the app is fine to leave running —
   `:desktop:test` is gated behind `WEATHER_DESKTOP_NO_TRAY`).
