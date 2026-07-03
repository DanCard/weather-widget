# Desktop: retry catch-up refresh when network isn't up yet (resume/startup)

## Context

The staleness/fetch dot appeared slow to update after a suspend/resume. `app_logs` showed the real cause: at 20:34:11 the app detected resume (logind) and immediately kicked a catch-up refresh, but the fetch failed with `UnresolvedAddressException` — the network/DNS wasn't back up yet. There is **no retry**: the failure just sets an offline `DataStatus`, and fresh data only arrived ~95s later when another trigger fired. The same race exists at **startup** (login autostart can beat NetworkManager).

User decision: check/wait for network and retry the fetch after resume, rather than treating the first failure as final.

(Separate finding, **out of scope** unless requested later: the dot's age label only re-renders when the `forecast` Compose state changes — `now` in `TemperatureGraph.kt:125` has no clock ticker, and `Main.kt` `forecast = it` no-ops when `loadCached()` returns a structurally-equal result — so the "Nm" label can freeze between data changes. Also the label is hidden entirely at >12h span in the new 24h default view.)

## Approach

Retry-with-backoff **inside `runLaunchRefresh`**, gated on the existing `isOfflineException(e)` classification (`shared` — already imported in `DaemonProcess.kt:6`). The fetch attempt itself is the "network up" check — no new connectivity primitive or host mapping needed, and it reuses the exact failure signal we observed. Applies to both call sites (`startup` at `DaemonProcess.kt:227` and `resume:*` at `DaemonProcess.kt:211`) since both are catch-up paths racing the network. Best-effort and bounded, per project preference.

## Changes

### 1. `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopProcess.kt` — pure, testable schedule

Next to `RESUME_DEBOUNCE_MS` (line 45):

```kotlin
// Backoff for launch/resume catch-up fetches that fail offline (network not up yet
// after resume/login). Bounded; cumulative ~155s. Best-effort: after the last retry
// we give up and leave the existing offline DataStatus in place.
val OFFLINE_RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 45_000L, 90_000L)

// Returns the delay before retry [attempt] (0-based), or null when retries are
// exhausted or the failure isn't offline-classified.
fun offlineRetryDelayMs(attempt: Int, isOffline: Boolean): Long? =
    if (isOffline) OFFLINE_RETRY_DELAYS_MS.getOrNull(attempt) else null
```

The last retry fires at ~155s cumulative — late enough to catch the observed case where DNS came back between 65s and 95s post-resume.

### 2. `desktop/src/main/kotlin/com/weatherwidget/desktop/DaemonProcess.kt` — attempt loop in `runLaunchRefresh`

Restructure the fetch block (lines 134–168) into a retry loop. Keep behavior identical on success and on non-offline failure; intermediate offline failures log a lightweight row instead of the full failure treatment:

```kotlin
if (launchRefreshAction != LaunchRefreshAction.NONE) {
    var attempt = 0
    while (true) {
        try {
            val result = when (launchRefreshAction) { /* unchanged FULL_FORECAST / OBSERVATIONS */ }
            forecastState.value = result
            dataStatusState.value = DataStatus.Live(System.currentTimeMillis())
            break
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val isOffline = isOfflineException(e)
            val retryDelay = offlineRetryDelayMs(attempt, isOffline)
            if (retryDelay != null) {
                // Network likely not up yet (post-resume/login race): wait and retry.
                weatherDao.log("REFRESH_RETRY", "$reason fetch offline (${e.message}); retry #${attempt + 1} in ${retryDelay / 1000}s", "INFO")
                delay(retryDelay)
                attempt++
                continue
            }
            /* existing final-failure handling: REFRESH_FAIL log + deriveDataStatus(...) */
            break
        }
    }
}
```

Notes:
- **Do not** set the offline `DataStatus` on intermediate attempts — only on final failure (current lines 156–166 move to the exhausted branch). The UI keeps showing cached data as `Live(lastFetch)` meanwhile, which is accurate.
- `determineLaunchRefreshAction` is not recomputed between retries — staleness can't meaningfully change in ≤155s.

### 3. `DaemonProcess.kt` — don't stack resume refreshes

Retries keep the coroutine alive up to ~155s, longer than `RESUME_DEBOUNCE_MS` (120s). In `kickResumeRefresh` (line 196), hold the job and cancel the previous one before launching (interrupt-driven, last-wins — same spirit as the `.quit` handoff):

```kotlin
resumeRefreshJob?.cancel()
resumeRefreshJob = daemonScope.launch { runLaunchRefresh(activeRepo, activeConfig, "resume:$reason") }
```

(`CancellationException` is already rethrown everywhere in `runLaunchRefresh`, so cancel is safe mid-retry-sleep.)

## Tests

Add to `desktop/src/test/kotlin/com/weatherwidget/desktop/RefreshDelayTest.kt` (already tests `determineLaunchRefreshAction` / `isSuspendJump` — same pure-function pattern, no mocks):
- `offlineRetryDelayMs(0..3, isOffline = true)` returns the schedule values in order.
- Returns `null` when attempts exhausted (`attempt = 4`).
- Returns `null` for non-offline failures regardless of attempt.
- Schedule invariant: cumulative sum > `RESUME_DEBOUNCE_MS` is acceptable *only because* of the cancel-previous-job guard — assert delays are positive and monotonic non-decreasing.

## Verification (end-to-end)

1. `./gradlew :desktop:test` for the new unit tests.
2. Live startup-path test (same code path as resume, easier to trigger):
   - Stop the app, disable network (`nmcli networking off`).
   - Start via `scripts/buildStart-desktop.sh` (builds + restarts).
   - Watch `app_logs`: expect `LAUNCH_REFRESH_CHECK` then `REFRESH_RETRY` rows on the 5/15/45/90s cadence.
   - Re-enable network (`nmcli networking on`) mid-schedule; expect the next attempt to succeed → `REFRESH` / `OBS_REFRESH` rows and the staleness dot going fresh without any user interaction.
   - Also confirm final-failure path once by leaving network off through all retries → single `REFRESH_FAIL` at the end, offline DataStatus.
