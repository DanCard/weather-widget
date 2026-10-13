# User refreshes outlive their screen — one shared rule, guarded on both platforms

## Problem

On the Fold 4 (2026-10-13), the user tapped Refresh on the Observations screen with NWS shown.
No new current actuals were stored. The newest NWS row stayed at KNUQ 13:55, fetched 14:14.

This is the second time the bug has appeared. The desktop had it in July: Refresh ran on the
Settings window's scope, and closing the window discarded the fetch (memory
`desktop_refresh_window_scope_cancel`). That fix was desktop-only, so Android kept the bug. The user
asked that a rule like this be shared, so it doesn't have to be fixed once per platform.

## Evidence (device event log + `app_logs`)

| Time | Event |
|---|---|
| 14:27:40 | `wm_create_activity … WeatherObservationsActivity` (opened from the widget) |
| 14:27:49.068 | `input_interaction` on the screen → `CURR_FETCH_START reason=user_observations_screen targets=NWS` |
| 14:27:54.220 | `wm_finish_activity … WeatherObservationsActivity, app-request` (Close or Back) |
| 14:27:54 | `CURR_FETCH_ERROR … (reason=user_observations_screen, totalMs=5704): Job was cancelled` |

## Root cause

A fetch the user started runs on the scope of the screen that started it. Closing the screen
cancels the fetch. On Android today:

- **`WeatherObservationsActivity.refreshData()`**: `lifecycleScope` → `refreshCurrentTemperature`
  plus the METAR / Synoptic pulls. This is the reported bug.
- **`WeatherObservationsActivity`'s automatic stale-display repair** (`forceRefreshDisplayedSource`):
  the same scope.
- **`ForecastHistoryActivity.refreshViewedSource()`**: `lifecycleScope` → `fetchSourceOnDemand`. For
  a past Google day this is a billed `history/hours` request (quota 20 a day), and the quota is
  spent whether or not the result is kept.

Settings → Refresh data already goes through WorkManager, so it is not affected. Desktop's two
Refresh paths (`requestFullRefresh`, `requestSourceRefresh`) run on the application-level `uiScope`.
They are correct, but by a hand-written rule that nothing enforces.

## Fix

### 1. `:shared` `UserRefreshRunner`, the rule written once

```kotlin
class UserRefreshRunner(scope: CoroutineScope, log: (tag, message, level) -> Unit) {
    fun <T> start(origin: String, afterDetached: suspend (T) -> Unit = {}, fetch: suspend () -> T): UserRefresh<T>
}
class UserRefresh<T> { suspend fun await(): T }
```

- `start` runs `fetch` in the app-lifetime `scope`. A `SupervisorJob` sits between the fetch and
  that scope, so a failing fetch can't cancel a plain-`Job` scope (desktop's `uiScope`) and
  everything else running on it.
- `await` is all a screen does. If the screen goes away first, only the waiting is cancelled. The
  fetch keeps running and logs `USER_REFRESH_DETACHED origin=… outcome=waiting`. When it finishes it
  runs `afterDetached(result)` — the follow-up a closed screen can no longer do, such as repainting
  the widget — and logs `outcome=completed|failed`.
- Completion and detach are decided by one atomic state, so a fetch that lands while the screen is
  closing still gets its `afterDetached`.

### 2. `:shared` `UserRefreshCallSites`, the guard's rule

A pure function: given a source file's text and the pattern for "a fetch call", it returns the
lines where such a call is *not* inside a `…RefreshRunner.start(…) { … }` block. It matches parens
and braces and skips `//` comments.

Both platforms' guard tests use it, so the meaning of "inside the runner" is defined once and
unit-tested once.

### 3. Android

- Hilt provides a `UserRefreshRunner` singleton on the existing `@ApplicationIoScope`
  (`SupervisorJob + Dispatchers.IO`). Its log goes to `AppLogDao.log`.
- **Observations Refresh:** the fetch (repository plus METAR / Synoptic) goes inside
  `userRefreshRunner.start("user_observations_screen", afterDetached = repaint the widget)`. The
  button and toast code then awaits it. The `afterDetached` repaint replaces the `onDestroy` repaint
  this screen would have skipped, because `widgetContentChanged` was never set.
- **Observations stale repair:** `forceRefreshDisplayedSource` becomes a nullable test override.
  The real call goes inside `start("stale_observations_screen")`.
- **History Refresh:** `fetchSourceOnDemand` goes inside `start(...)`. Its `afterDetached` sends the
  same ui-only widget repaint the attached path sends when the fetch succeeds.
- **Test seam:** `refreshFetchOverride` replaces only the network part. The fetch call stays
  inside `start`, so the guard still checks the real code.

### 4. Desktop

`requestFullRefresh` / `requestSourceRefresh` start through a `UserRefreshRunner` built on `uiScope`
(log → `weatherDao.log`). There is no behaviour change; the rule is now the shared one, and the
guard enforces it.

### 5. Guards

- **Android:** `UserRefreshScopeGuardTest` (`app/src/test/.../architecture/`). Every
  `refreshCurrentTemperature(` / `fetchSourceOnDemand(` / `.fetchObservations(` in
  `com/weatherwidget/ui/` must be inside a runner block.
- **Desktop:** the same test in `desktop/src/test`. Every `userRefresh = true` call in
  `desktop/src/main` UI files must be inside a runner block.
- Each guard also checks that it found at least one call site, so a renamed method can't make it
  pass vacuously.

## Not in scope

- The deeper duplication: Android `refreshCurrentTemperature` vs desktop `refreshObservations` are
  two implementations of the same job.
- On-demand fetches started by scrolling or a day tap (desktop `ensureHistory` / `extendHourlyFor`).

## Tests

- **`:shared` `UserRefreshRunnerTest`:**
  - the waiter is cancelled mid-fetch, yet the fetch completes and `afterDetached` runs with its
    result;
  - an attached await returns the result and `afterDetached` does not run;
  - a failing fetch reaches the waiter as its exception, and a plain-`Job` scope survives it;
  - a detached failure logs `outcome=failed`.
- **`:shared` `UserRefreshCallSitesTest`:** inside, outside, `afterDetached` argument, nested
  braces, string templates, comments.
- **Android Robolectric integration:** `WeatherObservationsActivity` + the real Hilt runner. Tap
  Refresh with a fetch blocked on a latch, finish the activity, release the latch. Assert the
  fetch completed, a `USER_REFRESH_DETACHED … completed` row exists, and the widget repaint was
  broadcast.
- **The control case:** the screen stays open; the button re-enables and no detach row is written.
- The two guard tests, plus mutation checks: revert one call site to `lifecycleScope` → the guard
  fails.
- **On the Fold 4:** tap Refresh, close at once, and confirm a new NWS `fetchedAt` row and
  `USER_REFRESH_DETACHED … completed`.

## Verification

Implemented 2026-10-13.

- **`:shared`:** `UserRefreshRunner` and `UserRefreshCallSites`.
  - `UserRefreshRunnerTest` (4 tests) and `UserRefreshCallSitesTest` (3 tests).
  - The scanner skips string-literal contents; a `"{"` in a string first threw off brace matching.
- **Android:** `AppModule.provideUserRefreshRunner` (on `@ApplicationIoScope`).
  - Observations Refresh and the stale repair go through it.
  - `forceRefreshDisplayedSource` is now a nullable override.
  - The stale repair rethrows `CancellationException` instead of logging it as `outcome=failed`.
  - History Refresh goes through it; its widget repaint moved to the companion
    (`sendUiOnlyWidgetRefresh`), so the detached lambda holds no activity reference.
- **Desktop:** `requestFullRefresh` / `requestSourceRefresh` start through a runner on `uiScope`.
  The source refresh's service `close()` moved inside the fetch.
- **Guards:** `UserRefreshScopeGuardTest` on both platforms.
  - Mutation check: restoring History's old `lifecycleScope` code fails the Android guard with
    `ForecastHistoryActivity.kt:752: weatherRepository.fetchSourceOnDemand(`.
- **Integration:** `WeatherObservationsRefreshOutlivesScreenIntegrationTest`, closed-mid-fetch plus
  the attached control case. No mutation check was run for this test; the runner test and the
  guard cover the same ground.
- **Suites:** `:shared` 2163, app 2502, desktop 580 (Short 460, Medium 39, Long 81); 0 failures.
- **Fold 4, 14:41:**
  - Steps: widget thermometer → Observations → Refresh → Back 0.5 s later.
  - Log rows: `USER_REFRESH_DETACHED … outcome=waiting` (14:41:52), `CURR_FETCH_SOURCE_RESULT …
    NWS success=true temp=73.52 observedAt=14:35`, `USER_REFRESH_DETACHED … outcome=completed`
    (14:41:54), then `WIDGET_PAINT widget=345 origin=ACTION_REFRESH` (14:41:58).
  - New NWS rows were fetched at 14:41:53 (KNUQ 14:35).
- Desktop rebuilt and restarted via `scripts/buildStart-desktop.sh`.
