# Desktop: recover post-resume fetch when network isn't up — NM monitor + short retry (hybrid)

## Context

The staleness/fetch dot appeared slow to update after suspend/resume. `app_logs` showed the real cause: at 20:34:11 the app detected resume (logind) and kicked a catch-up refresh, but the fetch failed with `UnresolvedAddressException` — the network/DNS wasn't back up yet. There is **no recovery**: the failure sets offline `DataStatus` and fresh data only arrived ~95s later via another trigger. The same race exists at **startup** (login autostart can beat NetworkManager).

User decision (hybrid): **event-driven NetworkManager connectivity monitor as primary** (kick a refresh the moment the network returns), plus a **short retry backoff as belt-and-suspenders** for when the gdbus stream is dead or NM reports connected before DNS is truly ready.

(Separate finding, **out of scope**: the dot's age label only re-renders on `forecast` state recomposition — no clock ticker in `TemperatureGraph.kt:125`, and `Main.kt:362` `forecast = it` no-ops on structurally-equal reloads — so the "Nm" label can freeze between data changes; it's also hidden entirely at >12h span in the 24h default view.)

## Approach

Mirror the existing resume-detection architecture in `DaemonProcess.kt`, which is already "event-driven primary (logind gdbus monitor, lines 557-577) + dumb fallback (30s heartbeat)":

- **Primary**: a second `gdbus monitor` coroutine watching `org.freedesktop.NetworkManager` for connectivity-restored signals; on restore, kick `runLaunchRefresh` — it already self-gates via `determineLaunchRefreshAction` (a failed fetch never updated `lastSuccessfulFetch`, so staleness still holds; if nothing is stale the kick is a cheap no-op).
- **Fallback**: short offline retry backoff (5s/15s) inside `runLaunchRefresh`, gated on the existing `isOfflineException()` (`DaemonProcess.kt:6` import). Applies to both call sites (`startup` at line 227, `resume:*` at line 211).
- Existing 10-min observation / 60-120-min forecast loops remain the last-resort safety net.

## Changes

### 1. `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopProcess.kt` — pure, testable pieces

Next to `RESUME_DEBOUNCE_MS` (line 45) and `isResumeSignalLine` (lines 71-76):

```kotlin
// Short belt-and-suspenders backoff for launch/resume fetches that fail offline.
// Primary recovery is the NetworkManager connectivity monitor; this only covers the
// gdbus-stream-dead case and NM reporting connected before DNS actually resolves.
val OFFLINE_RETRY_DELAYS_MS = listOf(5_000L, 15_000L)

fun offlineRetryDelayMs(attempt: Int, isOffline: Boolean): Long? =
    if (isOffline) OFFLINE_RETRY_DELAYS_MS.getOrNull(attempt) else null

// Debounce for connectivity-restored kicks (Wi-Fi roams can flap the signal).
const val NETWORK_RESTORE_DEBOUNCE_MS = 30_000L

// Matches gdbus monitor output for NetworkManager reaching full connectivity:
//   .../NetworkManager: org.freedesktop.NetworkManager.StateChanged (uint32 70,)   [CONNECTED_GLOBAL]
//   ...PropertiesChanged ... 'Connectivity': <uint32 4> ...                        [NM_CONNECTIVITY_FULL]
fun isNetworkRestoredSignalLine(line: String): Boolean
```

Predicate matches either `StateChanged` with state 70 or a `Connectivity` property of 4 — same string-containment style as `isResumeSignalLine`, no D-Bus library.

### 2. `DaemonProcess.kt` — NetworkManager monitor coroutine (primary)

New function modeled directly on the logind monitor (lines 555-578), started alongside it:

```kotlin
// Primary network-restored detector: kicks a catch-up refresh the moment NM reports
// connectivity, healing fetches that failed while the network was down (post-resume
// race, login autostart, mid-day Wi-Fi drop). Best-effort: if the stream dies we log
// once and lean on the retry backoff + periodic fetch loops.
private fun startNetworkMonitor() { ... }
```

- Spawns `gdbus monitor -y -d org.freedesktop.NetworkManager -o /org/freedesktop/NetworkManager`, reads lines, and on `isNetworkRestoredSignalLine(line)` calls a new `kickNetworkRestoredRefresh()`.
- `kickNetworkRestoredRefresh()`: same shape as `kickResumeRefresh` (line 196) — no-repo guard, own `lastNetworkKickMs` debounced by `NETWORK_RESTORE_DEBOUNCE_MS`, durable `NETWORK_DETECT` app_logs rows for all outcomes (kicked / debounced / not-ready), then `daemonScope.launch { runLaunchRefresh(activeRepo, activeConfig, "network:restored") }` via the shared job holder (change 4).
- Stream-end / spawn-failure handling copied from the logind monitor: one `NETWORK_DETECT` WARN row, then rely on fallbacks.

### 3. `DaemonProcess.kt` — short retry loop in `runLaunchRefresh` (fallback)

Restructure the fetch block (lines 134-168) into an attempt loop:

- On failure: `CancellationException` rethrown; otherwise classify with `isOfflineException(e)`, ask `offlineRetryDelayMs(attempt, isOffline)`.
- Delay non-null → log lightweight `REFRESH_RETRY` row (`"$reason fetch offline (${e.message}); retry #N in Ns"`), `delay()`, retry the same `launchRefreshAction` (staleness can't change in ≤20s; no recompute).
- Delay null (non-offline or exhausted) → existing final-failure handling verbatim (current lines 156-166: `REFRESH_FAIL` log + `deriveDataStatus`). Intermediate attempts must NOT touch `dataStatusState` — UI keeps showing cached data as `Live(lastFetch)`.

### 4. `DaemonProcess.kt` — don't stack catch-up refreshes

`kickResumeRefresh` and `kickNetworkRestoredRefresh` share one job holder; cancel the previous before launching (last-wins, same spirit as the `.quit` handoff):

```kotlin
catchUpRefreshJob?.cancel()
catchUpRefreshJob = daemonScope.launch { runLaunchRefresh(...) }
```

(`CancellationException` is rethrown throughout `runLaunchRefresh`, so cancel mid-retry-sleep is safe.)

## Tests

Add to `desktop/src/test/kotlin/com/weatherwidget/desktop/RefreshDelayTest.kt` (already tests `determineLaunchRefreshAction` / `isSuspendJump` — same pure-function pattern, no mocks):

- `offlineRetryDelayMs`: returns 5s then 15s for offline attempts 0/1; null at attempt 2 (exhausted); null for non-offline at any attempt.
- `isNetworkRestoredSignalLine`: true for a real `StateChanged (uint32 70,)` line and a `'Connectivity': <uint32 4>` PropertiesChanged line; false for disconnect states (`uint32 20`), other NM signals (`DeviceAdded`), Connectivity 1-3, and unrelated logind lines (no cross-matching with `isResumeSignalLine`).

## Verification (end-to-end)

1. `./gradlew :desktop:test` for the new unit tests.
2. Live test via the startup path (same code as resume, easier to trigger):
   - Stop the app, `nmcli networking off`, start via `scripts/buildStart-desktop.sh`.
   - Watch `app_logs`: `LAUNCH_REFRESH_CHECK`, then `REFRESH_RETRY` at 5s/15s, then one `REFRESH_FAIL` + offline status.
   - `nmcli networking on` → expect a `NETWORK_DETECT` "kicking" row within seconds, followed by `REFRESH`/`OBS_REFRESH` and the staleness dot going fresh with no interaction.
   - Flap the network again briefly to confirm the 30s debounce logs a "debounced" `NETWORK_DETECT` row rather than double-fetching.
3. Sanity-check the monitor line format on this machine first: `gdbus monitor -y -d org.freedesktop.NetworkManager -o /org/freedesktop/NetworkManager` while toggling `nmcli networking off/on`, and adjust the predicate's expected strings if the emitted lines differ.
