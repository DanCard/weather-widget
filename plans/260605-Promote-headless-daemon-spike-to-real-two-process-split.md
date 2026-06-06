# Promote the headless-daemon spike to the real two-process split

## Context

The desktop app idles at ~0.05% CPU but the single dominant idle waker is **AWT-XAWT (~2/s)** —
Compose-for-Desktop's X11 toolkit thread, which `application {}` starts immediately and keeps alive
for the whole process even when no window is shown. AWT init is one-shot per JVM (can't be torn down
and restarted), so the only way to stop paying for it while idle is to **not initialize AWT in the
idle process**.

The spike (committed `DaemonSpike.kt` + `runDaemonSpike` gradle task) **validated this**: running the
background workload headless has zero AWT threads, drops idle wakes from 3.2/s → ~0.77/s, and works
end-to-end (real NWS fetch, DB, genmon socket). This plan promotes that throwaway spike into the real
architecture: a long-lived **headless daemon** (genmon-only, no AWT) plus a **short-lived UI process**
spawned on demand that owns all Compose/AWT and exits when its window closes.

De-risking facts already confirmed this session:
- DB is **WAL + busy_timeout=5000** (`shared/.../DesktopWeatherDatabase.kt:17-21`) → daemon-writer +
  UI-reader concurrency is already supported.
- Config is at `~/.config/weather-widget/config.json` (`DesktopConfig.kt`), a *different* dir from the
  trigger dir `~/.local/share/weather-widget/` → UI must signal config changes via a trigger file.
- genmon's panel click already does `touch .show` (`PanelIpcServer.kt:78-79`) → daemon's WatchService
  is the spawn hook; no genmon change needed.

## Architecture

```
login/autostart ──> DAEMON (headless, no AWT, genmon-only)
                      • config+DB+Ktor+repository, PanelIpcServer, 3 fetch loops
                      • WatchService on ~/.local/share/weather-widget:
                          .show         -> spawn UI process (or raise if alive)
                          .config-changed -> reload config, restart loops if location/source changed
                          .quit/.quit-<id> -> daemon single-instance handoff (UNCHANGED)
                      • tracks uiProcess: Process?
                          │ spawn (--ui)
                          ▼
                    UI PROCESS (Compose/AWT, ephemeral)
                      • application{} + all Windows (popup/picker/settings/stats/observations)
                      • reads cached DB for display, light no-network current-temp re-interp
                      • config save -> touch .config-changed
                      • raises on .ui-show; exitProcess(0) when ALL windows closed
                      • does NOT touch .quit / does NOT run fetch loops or IPC server
```

## Implementation

### 1. Extract shared process plumbing — new `desktop/.../DesktopProcess.kt`
Move (single source of truth, deleting the copies in `Main.kt` and `DaemonSpike.kt`):
- `appDataDir()`, `isPackaged()`, `QUIT_TRIGGER`, `QUIT_PREFIX`, `appLaunchId`,
  `signalIncumbentToQuit()`, `maybePackagedSetup()`/`extractGenmonScript()` (currently `Main.kt:71-127`).
- New trigger-name constants: `SHOW_TRIGGER=".show"`, `UI_SHOW_TRIGGER=".ui-show"`,
  `CONFIG_CHANGED_TRIGGER=".config-changed"`.
- New `launchUiProcess(): Process` — relaunch self with `--ui`:
  - Packaged: `[System.getProperty("jpackage.app-path"), "--ui"]`.
  - Dev: `ProcessHandle.current().info().command()` (java) + `-cp` `System.getProperty("java.class.path")`
    + `com.weatherwidget.desktop.MainKt --ui`. Inherit IO; do not block.

### 2. Promote the daemon — `DaemonProcess.kt` (replaces `DaemonSpike.kt`)
Start from `DaemonSpike.kt` but fix the spike caveats:
- **Reuse `DesktopClients`** (not the forked `SpikeDesktopClients`) and the shared plumbing from step 1.
- **Tolerate null config** (don't `exitProcess(1)` like the spike): serve genmon `--`, and wait for
  `.config-changed`/picker to produce a config, then start loops. (Daemon launched at login before
  first-run config must survive.)
- Body = `runDaemon()`: config load, `DesktopWeatherDatabase.initialize()`, `DesktopWeatherDao`,
  `DesktopWeatherService`/`DesktopWeatherRepository`, `PanelIpcServer.start()`, the launch refresh +
  the 3 fetch loops (lifted verbatim from `Main.kt:215-429`), and one WatchService loop.
- **WatchService dispatch** (extends the spike's): on `SHOW_TRIGGER` → if `uiProcess?.isAlive==true`
  touch `UI_SHOW_TRIGGER` else `uiProcess = launchUiProcess()`, then delete `.show`; on
  `CONFIG_CHANGED_TRIGGER` → reload config and, only if `lat/lon/weatherSource/visibleSources` changed,
  cancel+relaunch the fetch-loop child scope with a rebuilt repository (ignore window-geometry-only
  edits); on `.quit`/`.quit-<id>` → keep existing daemon handoff (also kill `uiProcess` on quit).
- Daemon sets `System.setProperty("java.awt.headless","true")` as its first statement; never calls
  `application{}`. Daemon never writes config (UI is the sole writer → no write race).

### 3. Convert `Main.kt`'s `runApp()` into the UI process
- `main(args)` becomes a dispatcher: `--ui` (or `--show`) → run the Compose UI; otherwise (default /
  `--daemon`) → `signalIncumbentToQuit(...)` + `maybePackagedSetup()` + `runDaemon()`.
- In `runApp()` (UI), **remove the daemon body**: delete the big `LaunchedEffect(repository)` fetch
  loops (`Main.kt:211-430`), the `PanelIpcServer` (`194-197`), and the daemon `.quit` WatchService
  (`456-513`). Keep config/db/dao/clients/locationResolver, `forecast`/`dataStatus`, and **all Windows**.
- UI shows cached data: on launch `repo.loadCached()` once + a light no-network current-temp
  re-interpolation timer (reuse `TemperatureInterpolator`); navigation already reads the DB.
- UI is **always shown** when spawned: popup visible on `--ui` (config present) or picker if config null.
- **Config writes** (`configStore.save` in picker/settings/observations/window-move paths) get wrapped
  to also `touch .config-changed` so the daemon reloads.
- **Raise-to-front**: a small WatchService in the UI process on `UI_SHOW_TRIGGER` bumps `showRequestId`
  (reuse the existing raise logic `Main.kt:656-667`). UI does NOT watch `.show`/`.quit`.
- **Exit on close**: derive `anyWindowOpen = popup||picker||settings||stats||observations`; when it
  goes false, `exitApplication()` then hard `exitProcess(0)` (reuse the `quit()` hard-exit pattern at
  `Main.kt:439-451`, minus the `.quit` file delete). UI must NOT call `signalIncumbentToQuit`.

### 4. Tray = removed from the daily flow
Daemon is genmon-only (user-approved); the UI process is ephemeral so a tray there is pointless. Leave
`TemperatureSystemTray`/`TemperatureTrayPainter` in the tree but **unreferenced** by both processes
(dead code, delete in a later pass). genmon remains the persistent display + launcher.

### 5. Wire-up: scripts + gradle
- `scripts/desktop-app-launcher-and-autostart.sh`: launch the **daemon** (default args; drop the
  `--no-tray`/`--minimized` flags — irrelevant now). Keep the rebuild-if-missing logic.
- Gradle: replace `runDaemonSpike` with `runDaemon` (mainClass `MainKt`, args `--daemon`, headless +
  idle flags) and add `runUi` (mainClass `MainKt`, args `--ui`) for dev. Keep the packaged `.cfg` idle
  flags (apply to both modes; headless is set programmatically in the daemon branch only).
- Optional polish: add `-XX:TieredStopAtLevel=1` to the daemon args to quiet the C1/C2 compiler tail
  seen in the spike (minor).

## Risks / decisions
- **First-run ordering**: daemon-null-config tolerance (step 2) is required, else login-before-config
  crashes the daemon. Picker (in UI) writes config + `.config-changed` → daemon starts fetching.
- **Double-spawn / raise**: daemon is the sole spawner and gates on `uiProcess.isAlive`, so genmon
  double-clicks raise rather than spawn a second popup.
- **No tray means** the only ways to open the app are the genmon click and (first run) the spawned
  picker; Settings still has the "Exit app" button. Acceptable per prior decision.
- Keep a `--legacy` single-process mode (old all-in-one `application{}` with tray + loops) ONLY if a
  fallback is wanted; default plan omits it to avoid keeping the fetch loops in `runApp()`.

## Verification
1. Build: `./gradlew :desktop:compileKotlin` then `:desktop:createDistributable`.
2. **Daemon idle**: stop production (`touch ~/.local/share/weather-widget/.quit`), launch the daemon,
   `jstack <pid> | grep -i AWT` → **none**; sample `/proc/<pid>/task/*/status` ctxt-switch deltas over
   60s (post-warmup) → **<1/s**; confirm genmon panel still shows temperature.
3. **Spawn-on-click**: `touch ~/.local/share/weather-widget/.show` → a `--ui` process appears, popup
   shows, has AWT-XAWT; close popup → UI process exits (`exitProcess(0)`), daemon stays alive at <1/s.
4. **Raise**: with popup open, `touch .show` again → existing window raises, no 2nd process.
5. **Config change**: open Settings in the UI, change location, save → daemon logs a reload and fetches
   the new location (check `app_logs` / daemon log); no daemon restart, no mutual-kill.
6. **Single-instance**: relaunch the daemon → old daemon exits, new one binds `weather.sock`; an open
   UI process is unaffected (does not die, does not kill the new daemon).
7. Run the suite: `./gradlew :desktop:test`.

## Status
- DONE this session (committed): `-XX:AsyncDeflationInterval=0` deflation fix; `--no-tray` switch;
  minimized-by-default (`--show` to override). The `--no-tray`/`--show`/minimized changes are partly
  superseded by this split (daemon has no tray/window) but harmless; fold during step 3.
