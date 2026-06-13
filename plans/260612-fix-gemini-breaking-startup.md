# Fix: Desktop UI fails to display (focus-loss auto-hide self-destruct)

## Context

The desktop Compose UI stopped displaying after a recent change. The change is
isolated to a single commit, **`c5996f8a` "Add focus-loss and Escape key close
handlers to desktop UI windows"** (working tree is clean; the running build —
binaries dated 21:47 — already contains it).

That commit added two unrelated things:

1. **Escape-key `onKeyEvent` close handlers** on all 5 windows (popup, settings,
   location picker, Forecast History, Forecast Accuracy, Observations & Logs,
   App Logs). These are independent and harmless.
2. **A focus-loss auto-hide ("light-dismiss")** on the main popup, plus a 300ms
   `lastFocusLostTime` guard on the `.ui-show` trigger. **This is the regression.**

### Root cause

The desktop UI process is *ephemeral*: `LaunchedEffect(anyWindowOpen)` at
`Main.kt:188–199` calls `exitApplication()` + `exitProcess(0)` the instant **no**
window is open. Gemini's new `WindowFocusListener` (`Main.kt:457–473`) sets
`popupVisible = false` whenever the popup loses focus to a non-Java window
(`oppositeWindow == null`). On Linux/XFCE that condition fires not only on a
deliberate click-away but also during window raise, tray/panel interaction, and
WM focus hand-off. So: focus blip → popup closes → only window closed → **UI
process kills itself**. The new 300ms guard then races the reopen path, so the
window appears to never display.

Confirmed in `~/.local/state/weather-widget/autostart-20260612-214739.log`:
`Window composed/visible now` (21:47:45) followed by
`Popup lost focus to external window. Closing popup.` (21:50:04). The daemon
process itself is healthy (weather fetch / current-temp / obs refresh all normal).

### Intended outcome

Popup displays and stays open reliably. Escape-to-close is retained on all
windows. Light-dismiss-on-focus-loss is removed (can be revisited deliberately
later if wanted, but it must not be coupled to process exit).

## Approach (surgical — keep Escape, remove auto-hide)

All edits in `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt`:

1. **Remove the focus listener block** — delete the entire `DisposableEffect(window)`
   that adds/removes the `WindowFocusListener` (`Main.kt:457–473`).

2. **Remove the show-trigger guard** — in the `.ui-show` `WatchService` handler
   (`Main.kt:272–279`), restore the original body to just
   `SwingUtilities.invokeLater { requestShowPopup() }`, dropping the
   `now - lastFocusLostTime < 300` check.

3. **Remove now-unused state** — delete `var lastFocusLostTime by remember { mutableStateOf(0L) }`
   (`Main.kt:135`).

4. **Remove now-unused imports** — `java.awt.event.WindowFocusListener` and
   `java.awt.event.WindowEvent` (added near `Main.kt:26–27`). Keep the
   `androidx.compose.ui.input.key.*` imports — the Escape handlers still use them.

**Do NOT touch** the Escape `onKeyEvent` blocks in `Main.kt` (popup/settings/
picker) or in `AppLogsWindow.kt`, `ForecastHistoryWindow.kt`, `ObservationsWindow.kt`,
`StatisticsWindow.kt`. Those stay.

## Verification

1. Compile: `./gradlew :desktop:compileKotlin` (must succeed with no unused-import /
   unresolved-reference warnings for the removed symbols).
2. Rebuild + restart the running app via the project's restart flow:
   `scripts/build-start.sh` (rebuilds the distributable and restarts; this is the
   sanctioned path for a code change per the memory note on auto-restarting desktop).
3. Confirm the popup is visible and **stays open** when you click another window /
   the genmon panel (it should no longer vanish on focus loss).
4. Confirm Escape still closes each window.
5. Tail `~/.local/state/weather-widget/autostart-*.log` and verify there is no
   `Popup lost focus to external window. Closing popup.` line and the process does
   not exit on focus changes.
