# Desktop staleness dot slow after resume: NM connectivity monitor + offline retry + DNS negative-cache fix (2026-07-02)

## Symptom

After suspend/resume the desktop app's staleness/fetch dot stayed stale for minutes. `app_logs`
showed the real story: resume was detected promptly (logind, 20:34:11) and a catch-up refresh was
kicked immediately, but the fetch failed with `UnresolvedAddressException` — the app woke before
the network/DNS stack did — and there was **no recovery**. Fresh data only arrived ~95s later via
an unrelated trigger (user interaction). The same race exists at startup (login autostart beats
NetworkManager).

## Diagnosis

Three stacked issues:

1. **No recovery after a failed launch/resume fetch.** `runLaunchRefresh` made exactly one attempt;
   an offline failure just set the offline `DataStatus` and gave up until the next periodic loop
   (up to 10 min for observations, 60–120 min for forecasts).

2. **`isOfflineException()` misclassified the DNS failure.** Ktor CIO surfaces DNS failure as
   `java.nio.channels.UnresolvedAddressException`, whose **message is null** — so neither the
   class-name list nor the message fallbacks (`"resolve"` etc.) matched. Failures logged as
   `source_error null` instead of `offline`, on desktop and Android alike.

3. **JVM negative DNS cache poisoned the first post-restore attempt.** The JVM caches *failed*
   lookups for 10s (`networkaddress.cache.negative.ttl`). In the first live test, NM signaled
   connectivity and the kick's first fetch *still* failed offline — it was reading the outage-era
   cached failure, not the (working) resolver.

## Fix (hybrid: event-driven primary + bounded retry fallback)

Mirrors the existing resume-detection architecture (logind gdbus monitor + heartbeat fallback):

- **NetworkManager gdbus monitor** (`DaemonProcess.kt`): `gdbus monitor --system --dest
  org.freedesktop.NetworkManager -o /org/freedesktop/NetworkManager`; on
  `isNetworkRestoredSignalLine` (pure predicate in `DesktopProcess.kt`: `StateChanged` → 70
  CONNECTED_GLOBAL, or `'Connectivity': <uint32 4>` FULL) it calls `kickNetworkRestoredRefresh()`
  — debounced 30s (`NETWORK_RESTORE_DEBOUNCE_MS`; one reconnect emits a burst of signals), durable
  `NETWORK_DETECT` log rows for every outcome. `runLaunchRefresh` self-gates via
  `determineLaunchRefreshAction`, so a kick when nothing is stale is a cheap no-op.
- **Thundering-herd pause**: the kick waits `NETWORK_RESTORE_KICK_DELAY_MS` (3s) + random jitter
  (≤2s) before fetching — a weather refresh is low priority and shouldn't join the link-up
  stampede. Test enforces delay + max jitter < debounce.
- **Offline retry backoff** in `runLaunchRefresh` (5s/15s, `OFFLINE_RETRY_DELAYS_MS` +
  `offlineRetryDelayMs()` in `DesktopProcess.kt`): belt-and-suspenders for a dead gdbus stream or
  NM reporting connected prematurely. Intermediate attempts log lightweight `REFRESH_RETRY` rows
  and do **not** touch `dataStatusState`; final failure keeps the original `REFRESH_FAIL` +
  `deriveDataStatus` handling. Applies to both call sites (startup + resume).
- **Shared job holder** `catchUpRefreshJob`: resume and network kicks cancel a predecessor still
  sleeping in its backoff (last-wins, same spirit as the `.quit` handoff).
- **`isOfflineException` fix** (`shared/ForecastTypes.kt`): added `UnresolvedAddressException` to
  the class-name list (message-based fallbacks can never catch it). Also corrects the
  offline-vs-error indicator on Android.
- **`networkaddress.cache.negative.ttl = 0`** set at `runDaemon()` top: never cache failed DNS
  lookups, so the post-restore first attempt hits the real resolver.

## Verification

Unit: new tests in `RefreshDelayTest.kt` (signal-line parsing incl. digit-lookahead and
logind cross-match rejection; backoff schedule/exhaustion/non-offline; pause < debounce) and
`DataStatusTest.kt` (null-message `UnresolvedAddressException` classified offline). Full
`:shared:test` + `:desktop:test` green.

Live (offline test recipe: `nmcli networking off` → age `REFRESH`/`OBS_REFRESH` rows −65min →
`fast-desktop-restart.sh` → `nmcli networking on`), final run:

```
21:27:18  LAUNCH_REFRESH_CHECK reason=startup action=FULL_FORECAST   (network down)
21:27:18  REFRESH_RETRY  startup fetch offline; retry #1 in 5s
21:27:23  REFRESH_RETRY  startup fetch offline; retry #2 in 15s
21:27:39  REFRESH_FAIL   startup fetch: offline        ← correctly classified now
21:27:41  (nmcli networking on)
21:27:47  NETWORK_DETECT connectivity restored — catch-up refresh in 3650ms   (+ duplicate debounced)
21:27:51  LAUNCH_REFRESH_CHECK reason=network:restored action=FULL_FORECAST
21:27:54  REFRESH        source=NWS …                  ← first attempt, no retry needed
```

13 seconds from network-up to fresh data, no user interaction. An earlier run (before the
negative-TTL fix) needed the 5s retry after the kick — the cached DNS failure in action.

## Behavior when the network stays down

Cached data shows immediately; retries at 5s/15s; then one `REFRESH_FAIL` + offline indicator
(same end state as before, reached after trying). Recovery: NM monitor kick the moment
connectivity returns; if the monitor stream is dead, the periodic loops (≤10 min obs on AC) are
the safety net.

## Gotchas

- `gdbus monitor` line for a 1-tuple is `(uint32 70,)`; the predicate uses a non-digit lookahead
  after `uint32 70` so larger numbers can't false-positive.
- The offline test's row-aging (`UPDATE app_logs SET timestamp -= 65min`) permanently shifts
  historical `REFRESH`/`OBS_REFRESH` timestamps — harmless, but visible in old log queries.
