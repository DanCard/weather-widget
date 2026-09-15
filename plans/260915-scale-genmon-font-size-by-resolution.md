# Scale Genmon Font Size by Screen Resolution

**Date**: 2026-09-15  
**Goal**: Dynamically scale XFCE genmon panel temperature and delta font sizes based on active monitor resolution (e.g. 720p vs 4K), preventing oversized text on lower-resolution displays.

---

## 1. Problem & Root Cause

- The desktop app formats XFCE genmon markup in `PanelIpcServer.kt` using fixed Pango font tags:
  `font='Sans Bold 22'` for the current temperature and `font='Sans Bold 20'` for the temperature delta.
- In `genmon/genmon-weather.c`, the offline fallback `--` similarly hardcodes `Sans Bold 20`.
- In a vertical XFCE panel (e.g. 26px width), text wraps character-by-character.
- 9–10 stacked glyphs at 22pt/20pt take up ~176px vertically:
  - On a 4K (2160p) display, this is ~8% of the vertical screen space (comfortable).
  - On a 720p (1280x720) display, this occupies ~24.4% of the vertical screen space, dominating the entire panel.

---

## 2. Proposed Design

### 2.1 Resolution Detection (`DisplayResolutionDetector`)
- Location: `desktop/src/main/kotlin/com/weatherwidget/desktop/DisplayResolutionDetector.kt`
- Implements headless, non-blocking resolution query:
  1. Executes `xrandr --current` and extracts connected display geometry (primary / first connected display height, e.g. `1280x720`).
  2. Fallback to `xwininfo -root` or DRM sysfs (`/sys/class/drm/*/modes`).
  3. Default fallback to 1080 if detection unavailable.
- Caches detected height with a 60-second TTL so display changes are picked up without repeated process spawning on high-frequency polls.

### 2.2 Scaling Formula
- Pure function `resolveGenmonFontSizes(screenHeight: Int): Pair<Int, Int>`:
  - 720p ($H \le 720$): `10pt` temp / `9pt` delta (~80px vertical height, ~11% screen space, proportional to clock's `Sans 10`).
  - 1080p ($H = 1080$): `13pt` temp / `12pt` delta.
  - 1440p ($H = 1440$): `16pt` temp / `15pt` delta.
  - 2160p (4K, $H \ge 2160$): `22pt` temp / `20pt` delta.
  - Linear interpolation between 720 and 2160:
    ```kotlin
    val clamped = screenHeight.coerceIn(720, 2160)
    val tempSize = 10 + Math.round((clamped - 720) * (22.0 - 10.0) / (2160 - 720)).toInt()
    val deltaSize = 9 + Math.round((clamped - 720) * (20.0 - 9.0) / (2160 - 720)).toInt()
    ```

### 2.3 `PanelIpcServer.kt`
- In `buildPanelMarkup`, accept `tempFontSize: Int` and `deltaFontSize: Int`, defaulting to the resolved size.
- In `generateMarkup`, call `DisplayResolutionDetector.currentScreenHeight()` and `resolveGenmonFontSizes(...)` to produce scaled markup.

### 2.4 `genmon/genmon-weather.c`
- Update fallback string to use `Sans Bold 10` so offline state matches 720p/1080p standard panel sizing.
- Recompile `genmon-weather-bin`.

---

## 3. Verification Plan

1. **Unit Tests**:
   - Test `resolveGenmonFontSizes` across various resolutions (720, 900, 1080, 1440, 2160, 4320, 480) in `PanelIpcServerTest`.
   - Test `DisplayResolutionDetector` parsing logic.
   - Run `./scripts/unit-tests.sh` to verify all tests pass.
2. **Build & Live Verification**:
   - Recompile `genmon-weather-bin` via `make -C genmon`.
   - Rebuild and restart the desktop app via `scripts/buildStart.sh`.
   - Send refresh signal to `xfce4-panel --plugin-event=genmon-16:refresh:bool:true`.
   - Capture a screenshot of the 720p panel and inspect the scaled font size.
