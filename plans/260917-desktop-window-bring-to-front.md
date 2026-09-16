# Bring Desktop Windows To Front On Open / Re-Show

## Problem Statement
When windows such as "Weather Settings" or "Set Weather Location" are already open/rendered but minimized or buried behind other application windows (e.g. browser, terminal, or the main popup):
1. In `DesktopUiApplication.kt`, clicking the trigger (e.g. settings gear or "Set Location…") only sets the visibility boolean (e.g. `settingsVisible = true`, `pickerVisible = true`). Because the boolean is already `true`, Compose sees no state mutation and performs a no-op.
2. In the window composables (`SettingsWindowHost`, `LocationPickerWindowHost`, `AppLogsWindow`, `IconGalleryWindowHost`, `StatisticsWindow`, `ForecastHistoryWindow`):
   - Some only run `LaunchedEffect(Unit)` on initial composition and never again.
   - Some have no `toFront()` or `requestFocus()` calls at all (like `LocationPickerWindowHost` and `IconGalleryWindowHost`).
   - None of them check `state.isMinimized` or `(extendedState and Frame.ICONIFIED) != 0` to un-minimize before bringing to front (except `ObservationsWindow` and `PopupWindowHost`).

## Proposed Solution
1. **`DesktopWindowFocus.kt`**:
   - Centralize window restoration logic:
     ```kotlin
     internal fun bringWindowToFront(window: java.awt.Window, state: WindowState? = null) {
         if (state?.isMinimized == true) {
             state.isMinimized = false
         }
         if (window is java.awt.Frame) {
             if ((window.extendedState and java.awt.Frame.ICONIFIED) != 0) {
                 window.extendedState = java.awt.Frame.NORMAL
             }
         }
         window.toFront()
         window.requestFocus()
     }

     @Composable
     internal fun BringToFrontOnShow(
         window: java.awt.Window,
         state: WindowState? = null,
         showRequestId: Int,
     ) {
         LaunchedEffect(showRequestId) {
             bringWindowToFront(window, state)
         }
     }
     ```
2. **Update all Desktop Windows**:
   - Add `showRequestId: Int = 0` to:
     - `SettingsWindowHost`
     - `LocationPickerWindowHost`
     - `AppLogsWindow`
     - `IconGalleryWindowHost`
     - `StatisticsWindow`
     - `ForecastHistoryWindow` (already has `showRequestId`, standardize to use `BringToFrontOnShow`)
     - `ObservationsWindow` & `PopupWindowHost` (standardize to use `BringToFrontOnShow`)
3. **Update `DesktopUiApplication.kt`**:
   - Maintain `settingsShowRequestId`, `pickerShowRequestId`, `appLogsShowRequestId`, `iconGalleryShowRequestId`, `statsShowRequestId`.
   - Increment `showRequestId++` on every show/open callback.
4. **Integration & Unit Tests**:
   - `DesktopWindowFocusTest.kt`: unit test `bringWindowToFront` and `BringToFrontOnShow`.
   - `DesktopWindowBringToFrontIntegrationTest.kt`: integration test verifying all windows use `BringToFrontOnShow` and `DesktopUiApplication` increments request IDs.
5. **Empirical Verification**:
   - Build, run, bury windows, trigger re-show, verify stacking and focus via X11 properties.
