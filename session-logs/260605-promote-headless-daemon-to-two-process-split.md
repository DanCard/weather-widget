# Session Log: Promote Headless Daemon Spike to Real Two-Process Split (2026-06-05)

## Overview
This session focused on transitioning the Desktop weather companion application from a single monolithic process to a decoupled, two-process architecture. We promoted the previously validated headless daemon spike into a robust, long-lived background daemon (`java.awt.headless=true`, genmon-only) and split the Compose UI into a short-lived, ephemeral user interface process that is launched on-demand via the genmon panel and cleanly exits when all its windows are closed.

## User Prompts
1. "implement plans/260605-Promote-headless-daemon-spike-to-real-two-process-split.md"
2. "create very detailed , comprehensive session log in session-logs/ dir"

## Key Accomplishments

### 1. Extracted Shared Process Plumbing
- Created [DesktopProcess.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopProcess.kt) to unify process management utility constants (`QUIT_TRIGGER`, `QUIT_PREFIX`, `SHOW_TRIGGER`, `UI_SHOW_TRIGGER`, `CONFIG_CHANGED_TRIGGER`).
- Implemented `launchUiProcess()` to handle spawning the child UI process under both packaged (`jpackage` native launcher) and dev (JVM classpath) run modes.
- Configured environment isolation in the `ProcessBuilder` of `launchUiProcess()`, clearing AS/Android SDK-specific JVM variables (`JAVA_HOME`, `JAVA_LD_LIBRARY_PATH`) and preserving only critical OS variables (`DISPLAY`, `XAUTHORITY`, `HOME`, `PATH`, `LD_LIBRARY_PATH`) to ensure consistent startup behavior.
- Moved and unified shared network client definitions into `DesktopClients`.

### 2. Promoted headless background daemon
- Replaced the throwaway spike `DaemonSpike.kt` with [DaemonProcess.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DaemonProcess.kt), running with `java.awt.headless=true` and custom thread naming to prevent any graphics context creation.
- Added support for null configurations, serving placeholder `--` text to the genmon socket and waiting for `.config-changed` to reload the settings after location selection.
- Created the `WatchService` directory observer loop to capture external actions:
  - `.show` -> Spawns the UI process or bumps the active window to front via `.ui-show`.
  - `.config-changed` -> Dynamically reloads settings and restarts forecast/observation loops only if coordinates or API sources changed.
  - `.quit` / `.quit-*` -> Triggers graceful daemon shutdown.
- Refined the `quit(killUi: Boolean)` logic:
  - Set `killUi = false` when exiting due to newer daemon handoff to allow an open UI window to remain active.
  - Set `killUi = true` when exiting due to manual `.quit` signals to terminate the entire application suite.

### 3. ephemaral UI Process Conversion
- Refactored `main` in [Main.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt) to dispatch to `runApp()` in UI mode or `runDaemon()` otherwise.
- Completely removed background fetch loops, Unix socket listeners, and the system tray implementation from the UI process, ensuring it only reads cached DB records and updates local graphics.
- Structured settings/picker/geometry saves to touch the `.config-changed` trigger file so the daemon reloads settings dynamically.
- Implemented a raise-to-front listener in the UI process watching `.ui-show` to handle focus updates.
- Added exit-on-close logic that terminates the JVM via `exitProcess(0)` when all active Compose windows are closed.

### 4. Build, Packaging, and Script Integration
- Modified [build.gradle.kts](file:///home/dcar/projects/weather-widget/desktop/build.gradle.kts) to register separate tasks:
  - `runDaemon` -> starts the main class in headless mode with VM optimization flags.
  - `runUi` -> starts the Compose UI directly.
- Updated the autostart launcher script and [weather-widget-desktop.desktop](file:///home/dcar/.config/autostart/weather-widget-desktop.desktop) to drop the obsolete `--minimized` flags.
- Successfully verified that both manual dev execution (`./gradlew runDaemon` / `runUi`) and native distributables built with `createDistributable` compile and run seamlessly.

## Technical Details
- **Module**: `:desktop`
- **Daemon JVM Args**: `-Djava.awt.headless=true -XX:+UseSerialGC -XX:-UsePerfData -XX:AsyncDeflationInterval=0 -XX:TieredStopAtLevel=1`
- **Decoupled Concurrency**: Room SQLite WAL mode handles thread/process database access.
- **Trigger Protocols**:
  - `~/.local/share/weather-widget/.show` -> Raise/Spawn UI
  - `~/.local/share/weather-widget/.ui-show` -> Raise active window
  - `~/.local/share/weather-widget/.config-changed` -> Reload daemon settings
  - `~/.local/share/weather-widget/.quit` -> Terminate daemon & UI
  - `~/.local/share/weather-widget/.quit-<launchId>` -> Handoff signal to older daemon instances

## Final Status
The two-process decoupling is 100% complete and fully verified. The daemon process runs in an AWT-free headless context at less than 1 wakeup per second when idle, while the UI process starts instantly on-demand, raises correctly, saves configuration changes dynamically, and exits cleanly upon closure. All unit/integration tests pass.
