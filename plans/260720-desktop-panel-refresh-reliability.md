# Desktop panel refresh reliability (backend → frontend notification)

**Date:** 2026-07-20
**Trigger:** Desktop popup showed a ~3-day-stale Silurian forecast (Tuesday high 85° from Jul 17)
while the DB and daemon held the current value (90°, fetched 10:58). The emulator matched its DB
(90°); only the desktop *render* was stale. It affected the whole panel, including today's column.

## Root cause

The desktop is two processes — a **daemon** (background fetches, holds `forecastState`) and an
**ephemeral UI process** (`runApp`, tray + Compose popup). Daemon → UI notification already exists,
but it is unreliable:

1. **Push signal is lossy.** `notifyDataUpdated()` (`DesktopProcess.kt`) touches a `.data-updated`
   file; the UI's `WatchService` (`Main.kt`) reloads on it. Java `WatchService` drops/coalesces
   events (the repo admits this in `DaemonProcess.kt`), and the UI watcher does `if (!key.reset())
   break` — one reset failure kills the watcher permanently.
2. **Fallback poll freezes on suspend.** The UI's 10-minute safety-net uses `delay()`, which runs on
   the monotonic clock and does not advance while the machine is suspended (already documented for
   the daemon). After a long sleep the "10-minute bound" does not hold.
3. **UI has no resume awareness.** The daemon detects resume via a wall-clock heartbeat gap and
   re-fetches; the UI never force-reloads on wake — it depends entirely on the daemon's post-resume
   `.data-updated`, i.e. the lossy signal from (1).
4. Compounded by **orphaned/superseded UI processes** (old build inode still rendering), whose
   reload paths are all dead — the likely trigger for this specific Jul-17 freeze.

`loadCached()` reads the live DB tables directly, so any reload reflects current data — the fix is
purely about *reliably triggering a reload*, not about the data.

## Plan (implement all)

### 1. UI resume-awareness reload
Give the UI process the daemon's wall-clock heartbeat trick (`isSuspendJump`). A short tick compares
`System.currentTimeMillis()` deltas; on a suspend-sized gap, immediately reload the cache. Also
reload whenever the popup is (re)shown. Closes the suspend hole directly.

### 2. Suspend-proof fallback poll
Replace the single long `delay(UI_FALLBACK_RELOAD_MS)` with a short monotonic tick
(`UI_FALLBACK_TICK_MS`, 30 s) that reloads when either enough wall-clock elapsed (≥
`UI_FALLBACK_RELOAD_MS`) OR a wall-clock jump signals resume. Merged with (1) into one heartbeat loop.
A short tick still fires promptly after wake even though `delay()` froze during sleep.

### 3. Self-healing watcher
On `key.reset()` failure (or a dead `WatchService`), re-register the directory watch and continue
instead of `break`ing. A dead watcher should restart, not die forever.

### 4. Daemon → UI push socket (non-lossy notification)
Add `UiNotifyChannel.kt`:
- `UiNotifyServer(appDataDir)`: binds `ui-notify.sock`, accept loop keeps connected UI clients;
  `pushDataUpdated()` writes one byte to each and drops dead ones.
- `UiNotifyClient(appDataDir, onNotify)`: connects with retry; invokes `onNotify()` on connect
  (catches anything missed while disconnected) and on each pushed byte; reconnects on drop (a daemon
  restart / post-resume reconnect becomes a natural reload point).

Wire the daemon so `notifyDataUpdated()` also pushes over the socket (via a `@Volatile` server ref,
so all existing call sites benefit with one wiring point). Keep the `.data-updated` file trigger as
a fallback — belt and suspenders. Wire the UI to start a `UiNotifyClient` that reloads on notify.

## Verification
- Unit/integration test for `UiNotifyChannel` (server ↔ client round-trip over a temp socket).
- `./gradlew :desktop:compileKotlin :desktop:test` green.
- Restart the desktop app (`scripts/buildStart-desktop.sh`); confirm the panel reflects the current
  Silurian Tuesday high (90°), and that a subsequent background fetch repaints without manual action.

## Notes
- `weather.sock` / `PanelIpcServer` is the **genmon** panel channel (pull-based), NOT the Compose UI
  channel — #4 adds a separate `ui-notify.sock`.
- Items 1–3 already bound staleness across suspend; #4 is the reliability upgrade the report asked for.
