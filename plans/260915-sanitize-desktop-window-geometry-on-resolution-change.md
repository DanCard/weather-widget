# Sanitize Desktop Window Geometry on Resolution Change

**Date**: 2026-09-15  
**Goal**: Ensure all desktop app windows (`SettingsWindow`, `WidgetPopup`, `ObservationsWindow`, `ForecastHistoryWindow`) open visibly on-screen when switching between monitor resolutions (e.g. 4K to 720p).

---

## 1. Problem & Root Cause

- When windows are moved or resized, their coordinates `(x, y, width, height)` are persisted to `~/.config/weather-widget/config.json`.
- Coordinates saved on a 4K display (3840x2160) — such as `settingsWindowX = 1251.0, settingsWindowY = 691.0` with height `708.0` — place windows outside the bounds of lower-resolution displays (e.g. 1280x720).
- At `X=1261, Y=749` on a 720p screen, the top of `Weather Settings` starts 29 pixels below the bottom edge of the monitor, making the window completely invisible.
- Window size can also exceed screen bounds (e.g. 708dp window height on a 720px screen leaves no room for window decorations or panels).

---

## 2. Proposed Architecture

### 2.1 Screen Dimensions Detection (`DisplayResolutionDetector`)
- Enhance `DisplayResolutionDetector` to provide `DisplayDimensions(val width: Int, val height: Int)`.
- In the UI process (Compose Desktop), also support fallback to `java.awt.Toolkit.getDefaultToolkit().screenSize`.

### 2.2 Geometry Sanitizer (`DesktopWindowSanitizer`)
Location: `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWindowSanitizer.kt`
Pure function `sanitizeWindowGeometry(...)`:
- **Size Clamping**:
  - Clamps window width to `(screenWidth - 40).coerceAtLeast(minWidth)`.
  - Clamps window height to `(screenHeight - 60).coerceAtLeast(minHeight)`.
- **Position Validation**:
  - If saved `X` or `Y` is null, returns default position (e.g. `Alignment.Center` or `Alignment.TopEnd`).
  - If `X + 100 > screenWidth`, `X < -width + 100`, `Y + 50 > screenHeight`, or `Y < 0`, the position is deemed invalid/off-screen and resets to `defaultAlignment` (e.g. `Alignment.Center`).
  - Otherwise, gently clamps `X` and `Y` so the title bar and window edges stay fully visible within display margins.

### 2.3 Window Host Integration
Adopt `rememberSanitizedWindowState` or `sanitizeWindowGeometry` across all windows that persist coordinates:
- `SettingsWindowHost` (`DesktopWindowHosts.kt`)
- `PopupWindowHost` (`DesktopWindowHosts.kt`)
- `ObservationsWindow` (`ObservationsWindow.kt`)
- `ForecastHistoryWindow` (`ForecastHistoryWindow.kt`)
- Add window raise effect (`window.toFront()` and `window.requestFocus()`) on open.

---

## 3. Verification Plan

1. **Unit Tests**:
   - Create `DesktopWindowSanitizerTest.kt` verifying:
     - 4K saved coordinates (`1251, 691, 500, 708`) on 720p screen ($1280 \times 720$) reset position to Center and clamp height to $\le 660\text{dp}$.
     - Normal on-screen coordinates on 720p preserved.
     - Null coordinates default to specified alignment.
     - Negative or off-screen coordinates handled cleanly.
2. **Full Suite**:
   - Run `./scripts/unit-tests.sh` to confirm 0 regressions.
3. **Live Desktop Verification**:
   - Rebuild and restart desktop app via `scripts/buildStart-desktop.sh`.
   - Click the settings icon in the popup and confirm `Weather Settings` opens centered and fully visible on the 720p screen.
