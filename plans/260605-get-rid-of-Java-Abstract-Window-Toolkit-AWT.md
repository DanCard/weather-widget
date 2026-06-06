# Headless daemon spike — eliminate the AWT-XAWT ~2/s idle waker

## Context

The desktop weather widget idles at ~0.05% CPU but still wakes ~3.2 times/sec. After fixing the
Monitor Deflation timer this session (`-XX:AsyncDeflationInterval=0`, ~46% fewer wakes), the single
dominant remaining waker is **AWT-XAWT at ~2/s** — Compose-for-Desktop's X11 toolkit thread, parked
in `sun.awt.X11.XToolkit.waitForEvents` and woken by X server traffic. It uses ~0.037% CPU but keeps
the process perpetually nonzero, so in long-interval `top` (`-d 30`) it reliably shows ~0.1% and
floats near the top of the active-process list.

**Root constraint:** AWT initialization is one-shot per JVM. `runApp()` calls `application {}`
(`Main.kt:150`), which starts the AWT toolkit *immediately* — before any `Window` is composed — and
it cannot be torn down and restarted. So AWT-XAWT exists for the whole process lifetime even with the
popup closed. The only way to not pay for it while idle is to **not initialize AWT in the idle
process** → eventually a two-process split (headless daemon + on-demand UI). The user's intent: there
are no windows open most of the time, so the idle process shouldn't run a GUI toolkit.

**This plan covers the SPIKE only** (user chose "spike the headless daemon first"): prove that a
headless process running the real daemon workload has **no AWT-XAWT thread** and idles at <1 wake/s,
before committing to the full split. Tray fallback decision: **genmon-only is acceptable.**

## Goal of the spike

A throwaway-but-faithful headless `main` that wires up the exact daemon building blocks used today
(DB, Ktor clients, repository, IPC server, the 3 fetch loops, the file watcher), runs `java.awt.
headless=true`, and idles. Then measure per-thread wakeups and confirm AWT-XAWT is gone.

## Implementation

### 1. New file: `desktop/src/main/kotlin/com/weatherwidget/desktop/DaemonSpike.kt`
A `fun main()` that:
- As the very first statement: `System.setProperty("java.awt.headless", "true")` (belt-and-braces;
  the JavaExec task below also passes it as a JVM arg).
- Sets thread name to `WeatherDaemon` for `top` legibility.
- Reuses, verbatim, the same components instantiated in `Main.kt` (no Compose):
  - `DesktopConfigStore().load()` (`Main.kt:158`)
  - `DesktopWeatherDatabase(DesktopDbPaths.defaultDbPath()).apply { initialize() }` + `DesktopWeatherDao` (`Main.kt:162-163`)
  - `DesktopClients()` (Ktor CIO — important: it contributes the Ktor/coroutine threads the real daemon will have) (`Main.kt:179`)
  - `DesktopWeatherService` + `DesktopWeatherRepository` (`Main.kt:199-208`)
  - `PanelIpcServer(appDataDir()).apply { start() }` (`Main.kt:194`)
- Replaces Compose state with plain state: a `MutableStateFlow<ForecastResult?>` and
  `MutableStateFlow<DataStatus>`; after each loop update, call `ipcServer.update(forecast, dataStatus,
  config)` (mirrors the `LaunchedEffect` at `Main.kt:195-197`).
- Runs the daemon body inside `runBlocking { coroutineScope { ... } }`:
  - Launch refresh on startup via `determineLaunchRefreshAction(...)` + `repo.loadCached()` /
    `repo.refresh()` / `repo.refreshObservations()` (lift logic from `Main.kt:215-282`).
  - The **current-temp UI loop** (`Main.kt:300-311`) — the legitimate 2-min `CURRENT_TEMP_UI_INTERVAL_MS` tick.
  - The **observations loop** (`Main.kt:314-350`).
  - The **forecast loop** (`Main.kt:353-429`).
  - The **WatchService loop** (`Main.kt:456-507`), but on `.show` just `Log.i(...)` "would spawn UI"
    instead of `requestShowPopup()`; keep `.quit`/`$QUIT_PREFIX` handling → `exitProcess(0)`.
  - End with `kotlinx.coroutines.awaitCancellation()` so the process stays alive.
- Reuses existing helpers as-is: `isOfflineException`, `deriveDataStatus`, `PowerDetector`,
  `DesktopFetchStrategy`, `appDataDir()`, `appLaunchId`, and the `QUIT_*`/interval constants in `Main.kt`.

Note: keep `DaemonSpike.kt` self-contained; do NOT refactor `Main.kt` yet (that's the follow-on).
If a helper/constant is `private` in `Main.kt`, copy it into the spike rather than widening visibility.

### 2. Gradle run task: `desktop/build.gradle.kts`
Add a `JavaExec` task `runDaemonSpike` (does not disturb the `compose.desktop.application` config):
- `mainClass = "com.weatherwidget.desktop.DaemonSpikeKt"`, `classpath = sourceSets.main.runtimeClasspath`.
- `jvmArgs("-Djava.awt.headless=true", "-XX:+UseSerialGC", "-XX:-UsePerfData",
  "-XX:+UnlockDiagnosticVMOptions", "-XX:GuaranteedSafepointInterval=0",
  "-XX:GuaranteedAsyncDeflationInterval=0", "-XX:AsyncDeflationInterval=0")` — same idle-tuning flags
  as the packaged app so the comparison is apples-to-apples.

## Verification

1. **Stop the running tray app** (it shares `weather.sock` + DB): `touch ~/.local/share/weather-widget/.quit`.
2. **Run the spike:** `./gradlew :desktop:runDaemonSpike` (background). Confirm it starts without a
   `HeadlessException` (proves nothing in the daemon path transitively needs AWT) and that the genmon
   panel still updates (IPC/`weather.sock` serving works headlessly).
3. **Confirm no AWT thread:** `jstack <pid> | grep -E "AWT-XAWT|AWT-EventQueue"` → expect **no matches**.
   Also `ls /proc/<pid>/task/*/comm | xargs cat | sort -u` should not list `AWT-XAWT`.
4. **Measure idle wakeups** (reuse this session's method): after ~4 min warmup, sample
   `/proc/<pid>/task/*/status` `voluntary_ctxt_switches` deltas over 60s.
   - **Success:** AWT-XAWT absent; total wakes **< 1/s** between the 2-min temp ticks (only VM Periodic
     + compiler floor + parked Ktor/coroutine/IPC/watcher threads). Compare against this session's
     baselines: original 8.76/s → deflation-fix 3.2/s → **spike target <1/s**.
   - Also capture avg CPU% over the window for the before/after story.
5. **Restore the real app:** relaunch `scripts/desktop-app-launcher-and-autostart.sh` so daily use
   continues with the tray.

## If the spike succeeds (follow-on, NOT part of this spike)

Promote the spike into the real architecture:
- Refactor the daemon body out of `application {}` in `Main.kt` into a reusable `runDaemon(...)` used
  by a headless daemon entry point; make `.show`/tray-click **spawn a short-lived UI process** that
  runs `application {}` + the Compose `Window` and `exitProcess(0)` on window close (AWT exists only
  while a window is on screen).
- Daemon goes **genmon-only** for panel display (user-accepted); the on-demand UI process owns all
  Compose/AWT. Update `scripts/desktop-app-launcher-and-autostart.sh` to launch the daemon, and the
  genmon `.show` path to trigger the UI process.
- Preserve the existing `.quit` last-launch-wins single-instance handoff for the daemon.

## Out of scope / notes
- Committing the already-applied `-XX:AsyncDeflationInterval=0` change is independent; offer separately.
- The ~0.5–0.7/s HotSpot floor (VM Periodic Task + C1/C2 compiler) is irreducible on the JVM and
  remains after the split; that is expected and acceptable.
