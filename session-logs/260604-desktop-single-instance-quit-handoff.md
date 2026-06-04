# Session Log: Desktop Single-Instance — `.lock` → best-effort `.quit` handoff

## Date: 2026-06-04

## Summary
Replaced the desktop app's advisory single-instance **file lock** (`~/.local/share/weather-widget/.lock`)
with a **best-effort, last-launch-wins `.quit` trigger**, mirroring the existing `.show` mechanism. A
new launch touches a `.quit` file that any running instance's `WatchService` is watching; the incumbent
exits and the new launch takes over. No lock, no poll, no blocking readiness wait.

Motivation: the `.lock` was unhelpful — its docs claimed a second launch "exits immediately" but it
actually booted a full second Compose app just to show an "already running" window (added in the
260603 hang-fix session); it also soft-failed on any exception, and the real workflows already rely on
`desktop-full-rebuild-and-restart.sh`'s `pgrep`/`kill`.

Design was steered by the user across the conversation: prefer interrupt/event-driven over polling, and
"kill other instance should be best effort — no need to block." That collapsed the design to
fire-and-forget: signal and start, don't wait. Also added an explicit **"Exit app"** button to the
Settings screen (the only quit affordance under `WEATHER_DESKTOP_NO_TRAY`).

## Changes

### `:desktop`
- **`Main.kt`**:
    - **Removed the file lock** entirely: deleted `instanceLockChannel`, the takeover/poll function,
      and all `.lock` usage. `runApp()` lost its `lockAcquired` param and the "already running" window
      branch — the full app always runs now.
    - **`signalIncumbentToQuit(dir)`**: touches `~/.local/share/weather-widget/.quit` (create +
      `setLastModifiedTime` so an existing file still emits `ENTRY_MODIFY`, matching the genmon `.show`
      `open(...,"a"); utime` pattern). `main()` calls it best-effort (`runCatching`) *before* the
      composition registers this instance's own `WatchService`, so the toucher never quits itself
      (inotify doesn't replay a pre-existing file); only the already-watching incumbent reacts.
    - **`.quit` dispatch**: the existing `.show` `WatchService` loop now `when`-dispatches
      `".show" -> requestShowPopup()` and `QUIT_TRIGGER -> SwingUtilities.invokeLater { quit() }`.
      `quit()` was moved above the watcher (local funcs can't be forward-referenced).
    - **Hard-exit fix in `quit()`** (see below): after `exitApplication()`, a short-delay daemon
      thread calls `kotlin.system.exitProcess(0)`.
    - Passed `onExit = { quit() }` to the `SettingsWindow` call site.
- **`SettingsWindow.kt`**:
    - Added required `onExit: () -> Unit` param.
    - Footer `Box` → `Row` (SpaceBetween) with an `OutlinedButton("Exit app", testTag "exit_app")` on
      the left and the existing `Save` `Button` on the right.

### Tests / Docs
- **`DesktopUiTest.kt`**: fixed two existing `SettingsWindow(...)` call sites for the new param; added
  `testSettingsExitButtonInvokesOnExit` (clicking `exit_app` invokes `onExit`).
- **`CLAUDE.md`**: rewrote the inaccurate single-instance `.lock` bullet to describe the `.quit`
  last-launch-wins behavior, the no-self-quit ordering, the `exitProcess` requirement, and the Exit
  button.

## The bug found via live testing
The first two live handoff tests **failed**: the `.quit` signal fired correctly (incumbent log showed
`TrayIcon removed from SystemTray`, so its watcher ran `quit()`), but the process stayed alive headless.
Root cause: `exitApplication()` disposes the Compose UI but `application {}` **never returns** —
Dorkbox's GTK native event loop and AWT's EDT are non-daemon and keep the loop blocked. So an
`exitProcess(0)` placed after `runApp()` was never reached.

Fix: force the hard exit from inside `quit()` on a 400ms-delay daemon thread (grace lets the tray
teardown finish cleanly, avoiding a panel ghost icon). The old tray "Quit" menu item shared this same
latent lingering bug and is now fixed too.

## Verification Results

### Unit / build
- `./gradlew :desktop:compileKotlin` — clean.
- `./gradlew :desktop:test` — BUILD SUCCESSFUL (incl. new exit-button test).
- `./gradlew :desktop:createDistributable` — built.

### Manual (live, distributable via `desktop-full-rebuild-and-restart.sh`)
1. **Handoff / last-launch-wins**: instance A running (one tray); launched instance B directly. A
   exited cleanly (`TrayIcon removed` + process gone), B is the sole survivor.
2. **Hard exit confirmed**: A's JVM actually terminated (`kill -0` → NO) — the regression the first two
   runs exposed.
3. **No self-quit**: B touched `.quit` at its own startup yet kept running, with its own tray
   (`SystemTray -- Successfully loaded`).
4. **`.quit` trigger**: `~/.local/share/weather-widget/.quit` mtime advances on each new launch.
5. **Exit button**: wiring unit-tested; the `quit()` → process-death path is the same one the handoff
   test exercised directly (couldn't synthesize a GUI click).

Left a single autostart-launcher-managed instance running at session end.

## Notes / Decisions
- "Setup screen" interpreted as the **Settings** window (ongoing config), not the first-run location
  picker. Exit styled as a secondary `OutlinedButton` opposite Save.
- The stale `.lock` file is simply no longer used (harmless; no cleanup needed).
- Rejected alternatives (recorded for posterity): blocking `FileChannel.lock()` and a socket/EOF
  handshake — both existed only to make a *readiness wait* interrupt-driven, which the user's
  "no need to block" requirement eliminated. Also noted: `.show` is a file trigger, not a socket
  command (`PanelIpcServer`/`weather.sock` is pull-only), so a `.quit` file is the true analogue.
