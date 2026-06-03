# Make System Tray Font Twice as Big (Elongated Icon)

## Context

On the Linux desktop port the weather temperature shows in a **vertical** XFCE panel as a small
yellow "66.0" on a black square (`createTemperatureTrayImage` in `Main.kt`). A live screenshot
confirmed:

- The panel is vertical, so the existing 90° rotation is **correct** — "66.0" reads the same
  direction as the panel clock ("19:11") and date ("Tue 02") just below it. **Keep the rotation.**
- The temperature glyphs are clearly **smaller** than the clock's.
- Root cause: the tray is a **square** icon sized to the panel thickness, and we pack **4 characters**
  ("66.0") into it, so each glyph is only ~¼ of the long axis. The clock isn't a tray icon — it's a
  panel plugin that spreads its text along the panel's length, so its glyphs are much taller.

**Constraint:** keep full precision — no rounding. "66.0" stays.

**Chosen approach:** make the tray icon **non-square / elongated** so its long axis runs along the
panel's length, mimicking the clock. This is experimental: some tray backends force square icons and
may squish it. We implement it, verify on-screen, and keep a square two-line fallback.

## Files

- `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt` — `createTemperatureTrayImage()` (lines ~258-301)
- (No change to shared `formatTrayTemperature()` in `TemperatureTrayPainter.kt:89` — popup/tooltip/setStatus keep their formatting.)

## Approach A — Elongated icon (primary)

Rewrite `createTemperatureTrayImage()` to produce a **tall** image (height > width) instead of a 64×64
square, with the text rotated 90° (as today) so it reads bottom-to-top:

1. Keep text = `formatTrayTemperature(temperature)` ("66.0"), fallback "--".
2. Pick `thickness = 64` for the **short** (across-panel) axis.
3. Measure the string at a probe font (`Font.SANS_SERIF, Font.BOLD, 64`) using a throwaway
   `Graphics2D`/`FontMetrics` to get `textWidth` and `textHeight = ascent + descent`.
4. Scale so the glyph **height fills the short axis**: `scale = thickness / textHeight`.
   - Long axis length `longLen = (textWidth * scale).roundToInt().coerceAtLeast(thickness)`.
   - Real font size = `(64 * scale).roundToInt()`.
5. Allocate `BufferedImage(thickness, longLen, TYPE_INT_RGB)` (keep opaque black bg — the existing
   comment notes some hosts drop alpha and render white-on-white).
6. Reuse the existing render pattern: antialias hints → fill black → yellow text → `translate` to the
   image center (`thickness/2, longLen/2`) → `rotate(90°)` → `drawString` centered. Generalize the
   center/translate math to the new non-square dimensions (the current code hardcodes `size`).
7. Drop the old `coerceAtMost(2.0)` square-fill hack — sizing now comes from the measured `scale`,
   so the glyphs fill the short axis exactly and the long axis grows to fit all 4 characters at that
   size. Net effect: each glyph ≈ panel thickness tall, like the clock.

`roundToInt` is already imported (`Main.kt:39`).

## Approach B — Fallback if XFCE squishes it to square

If the panel forces the icon square (glyphs get vertically squished/distorted), revert to a square
icon but lay "66.0" out on **two stacked rows** ("66" / ".0"), so each glyph is ~2× the current
single-row size while keeping full precision. Same rotate-90 rendering, two `drawString` calls.

## Verification (decides A vs B)

The app is running via `./gradlew :desktop:run` in a Konsole tab; changes need a restart.

1. Rebuild/run: `./gradlew :desktop:run`.
2. After the tray repopulates (`LaunchedEffect(temperature)` re-renders the icon), capture & crop the
   vertical left panel:
   ```bash
   import -window root /tmp/screen.png
   convert /tmp/screen.png -crop 30x520+0+1560 +repage /tmp/trayonly.png
   convert /tmp/trayonly.png -resize 600% /tmp/trayonly.jpg   # view /tmp/trayonly.jpg
   ```
   (Offsets match the current panel; adjust if it moved.)
3. **Decision:** if "66.0" now renders elongated with glyphs ~the size of the "19:11" clock below it
   and undistorted → keep Approach A. If it's squished/letterboxed into a square → switch to Approach B.
4. Sanity-check the popup window and tray tooltip still show "66.0°" unchanged.
