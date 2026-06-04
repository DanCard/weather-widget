# Desktop single-instance: replace the file lock with a best-effort `.quit` signal

## Context

The desktop app currently guards against duplicate instances with an advisory **file lock**
(`~/.local/share/weather-widget/.lock`, `Main.kt`). The user finds it unhelpful, and rightly so:

- Its docs claim a second launch "exits immediately," but the code actually boots a full second
  Compose app just to render an "already running" window the user must dismiss.
- The lock soft-fails (any exception → `true`), so it isn't even a hard guarantee.
- `desktop-full-rebuild-and-restart.sh` already `pgrep`/`kill`s old processes, so the real workflows
  never depend on it.

The desired behavior is **last-launch-wins, best-effort**: a new launch asks any running instance to
exit, then starts immediately. **No readiness wait, no polling, no blocking** — per the user, "will
deal with issues if the other app doesn't exit." This mirrors the existing `.show` trigger
(genmon touches `~/.local/share/weather-widget/.show`; the app's `WatchService` reacts) — we add a
`.quit` sibling. We also add an explicit **"Exit app" button to the settings screen**, which also
gives `WEATHER_DESKTOP_NO_TRAY` mode a way to quit (today only the tray's Quit calls
`exitApplication`).

A partial edit is already in the working tree (`Main.kt` was changed to a poll/takeover lock
variant); **the file does not currently compile** because `main()` still calls the old
`acquireSingleInstanceLock` name. This plan reworks that partial edit into the simpler design below.

## Why not the socket / blocking-lock alternatives

The earlier socket-EOF and blocking-`FileChannel.lock()` ideas existed only to make the *readiness
wait* interrupt-driven. With no readiness wait at all, both are dead weight. Note for the record:
`.show` is **not** a socket command — `PanelIpcServer` (`weather.sock`) is pull-only (writes markup,
never reads). The mechanism that actually matches `.show` is another trigger file, so `.quit` it is.

## Changes

### 1. `Main.kt` — drop the lock, add a fire-and-forget `.quit` signal

Rework the partial edit:

- **Delete** `instanceLockChannel`, `acquireSingleInstanceLockOrTakeOver`, and the
  `TAKEOVER_TIMEOUT_MS` / `TAKEOVER_POLL_MS` constants. Keep the `QUIT_TRIGGER = ".quit"` constant.
- **Simplify `signalIncumbentToQuit(dir)`** to just touch `.quit` (create dir if needed, create the
  file, then `Files.setLastModifiedTime(...)` so an already-existing file still emits `ENTRY_MODIFY`,
  matching the genmon `.show` `open(...,"a"); utime` pattern).
- **`main()`**: replace the lock acquisition with a best-effort signal, then always run:

  ```kotlin
  fun main() {
      Thread.currentThread().name = "WeatherWidget"
      if (System.getProperty("weatherwidget.desktop.startupSmoke") == "true") return
      runCatching { signalIncumbentToQuit(appDataDir()) } // ask any running instance to exit
      maybePackagedSetup()
      runApp()
  }
  ```

  The `.quit` touch happens *before* the Compose composition (and thus before this instance registers
  its own `WatchService`). inotify does not replay a pre-existing file, so **the new instance never
  quits itself**; only the already-running incumbent (whose watcher is live) reacts.

- **`runApp()`**: remove the `lockAcquired: Boolean` parameter and the
  `if (!lockAcquired) { Window { CenteredMessage("...already running...") } } else { ... }` wrapper —
  always run the full app. (`CenteredMessage` stays; it's still used for the Loading/Error/NoData
  states.)

### 2. `Main.kt` — handle `.quit` in the existing WatchService

- **Move the local `fun quit()`** (`desktopClients.close(); exitApplication()`) to *above* the
  `.show` `WatchService` `LaunchedEffect` (local functions can't be forward-referenced).
- In the watcher's event loop, dispatch both triggers:

  ```kotlin
  when ((event.context() as? java.nio.file.Path)?.toString()) {
      ".show"      -> requestShowPopup()
      QUIT_TRIGGER -> SwingUtilities.invokeLater { quit() } // a newer instance is taking over
  }
  ```

  `quit()` does window/tray teardown, so marshal it to the EDT via `SwingUtilities.invokeLater`
  (already imported); `requestShowPopup()` only mutates Compose state so it stays as-is.

### 3. Settings screen — add an "Exit app" button

`SettingsWindow.kt` (`SettingsWindow` composable):

- Add an `onExit: () -> Unit` parameter to the signature.
- Change the right-aligned footer `Box` (the lone "Save" `Button`) into a `Row` spanning the width:
  an `OutlinedButton("Exit app")` (left, `Modifier.testTag("exit_app")`) and the existing
  `Button("Save")` (right). Match the existing Material3 button style.

`Main.kt` `SettingsWindow(...)` call site: pass `onExit = { quit() }`.

## Net effect

- One launch always wins: a fresh launch (`:desktop:run` or the distributable) replaces whatever's
  running, no `pgrep`/`kill` needed. Brief overlap (two trays / two `weather.sock` binds — the new
  `PanelIpcServer.start()` does `deleteIfExists` + bind) until the incumbent processes the event and
  exits. Accepted as best-effort.
- The `.lock` file is simply no longer used (a stale one is harmless; no cleanup required).
- Settings has a visible Exit, usable in `WEATHER_DESKTOP_NO_TRAY` mode (reach it via the genmon
  `.show` popup → Settings).

## Verification

1. **Compile/tests**: `./gradlew :desktop:compileKotlin` then `./gradlew :desktop:test`. The
   `DesktopStartupTest` smoke test still passes — `main()` returns on the `startupSmoke` guard before
   `signalIncumbentToQuit`, so it never touches `.quit`.
2. **Handoff (build + restart)**: run `scripts/desktop-full-rebuild-and-restart.sh` to get instance A
   running (one tray). Launch a second instance (`./gradlew :desktop:run` or run the restart script
   again); confirm A's tray disappears and the new instance's tray remains — last launch won. Confirm
   `~/.local/share/weather-widget/.quit` exists.
3. **Exit button**: open Settings → click "Exit app" → process exits cleanly (tray removed, no
   `weather-widget-desktop` process). Verify with `pgrep -f weather-widget-desktop`.
4. **No-tray exit**: run with `WEATHER_DESKTOP_NO_TRAY=1`, click the genmon panel to open the popup →
   Settings → "Exit app"; confirm clean exit.
5. **No self-quit**: after a single launch with no prior instance, confirm the app keeps running
   (it touched `.quit` at startup but must not quit itself).

## Follow-ups (not blocking)

- Update the `## Desktop App (Linux port)` section of `CLAUDE.md`: the bullet describing the
  single-instance `.lock` ("a stray `:desktop:run` or second launch exits immediately") is now
  inaccurate — replace with the `.quit` last-launch-wins behavior.
- Record the user preference (best-effort, fire-and-forget; prefer interrupt-driven over polling) in
  memory after implementation.
