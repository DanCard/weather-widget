# Desktop Hourly Zoomed-In (NARROW) View Parity with Android

**Follows:** `260606-desktop-hourly-graph-parity.md` (wide hourly view done + scaled).
**Goal:** Make the desktop NARROW (click-to-zoom) temperature view match Android, and make
click-to-zoom land on the clicked time.

---

## Evidence (2026-06-06)

Reference screenshots — Android NARROW is the target:
- Samsung Z Fold3: `/tmp/samsung_zoom.jpg` (window 4a–8a, NOW centered, gentle curve in a middle band)
- Emulator: `/tmp/emu_zoom.jpg` (window 3a–7a, NOW at ~75%, gentle curve, "1h 25m" age label)
- Desktop (broken): `/tmp/desktop_zoom_now.jpg` (window 10p–2a — **all past, no NOW, no forecast**,
  curve plummets across the **full height**)

Android NARROW characteristics (both devices): ~4h window (±2h) centered on/near NOW; hour ticks
every 1h with day-night icons; **gentle curve occupying a central band** with clear empty space
above/below; NOW dashed line + label; fetch dot + stale age label; value labels (start/min/current/
end). The span (±2h) the desktop already uses is correct — the **center** and the **vertical scale**
are wrong.

---

## Root causes & fixes

### 1. Click-to-zoom ignores the clicked location
**Where:** `TemperatureGraph.kt` tap handler (`:139-152`) calls `onToggleZoom()` with no position;
`Main.kt` `onToggleZoom` only flips `zoomLevel` (`config.copy(zoomLevel = …)`). `centerOffsetHours`
is never updated, so the narrow window frames whatever stale offset was active.

**Fix:**
- Change the graph-body tap to pass the clicked **time** out: convert tap `offset.x` →
  `windowStart + (offset.x/size.width)*windowSpan`, then to an hour offset from now
  (`(clickedMs - now)/3_600_000`). Signature: `onToggleZoom: (centerOffsetHours: Int) -> Unit` (or a
  dedicated `onZoomToClicked(Int)`).
- In `Main.kt`: on zoom-in set **both** `zoomLevel = "NARROW"` and `hourlyOffset = clickedOffset`
  (clamped to `MIN/MAX_HOURLY_OFFSET`). Clicking in NARROW zooms back out to `WIDE` (keep the toggle
  semantics, or recenter — see open question).
- Result: clicking a point frames that time ±2h, like dragging a loupe to where you clicked.

### 2. Vertical curve is over-stretched for the small narrow range
**Where:** `TemperatureGraph.kt:196` `val pad = ((rawMax - rawMin) * 0.06f).coerceAtLeast(1f)`.
The 0.06 ratio (added for the wide view's full-height fill) gives a ~1° pad on a ~3° narrow range,
so tiny variations fill the whole height and the curve looks like a cliff.

**Fix — adopt Android's `GraphLayout.computeScaling` rule** (`GraphLayout.kt:101-113`):
```
topBuffer    = (rawRange * TOP_BUFFER_RATIO).coerceAtLeast(3.0f)    // MIN_TOP_TEMP_BUFFER_DEGREES
bottomBuffer = (rawRange * BOTTOM_BUFFER_RATIO).coerceAtLeast(2.5f) // MIN_BOTTOM_TEMP_BUFFER_DEGREES
minTemp = rawMin - bottomBuffer ;  maxTemp = rawMax + topBuffer
```
Absolute minimum buffers keep small (narrow) ranges gentle in a central band, while large (wide)
ranges still fill most of the height. **One rule fixes both views** and replaces the flat 0.06 pad.
Pull `TOP_BUFFER_RATIO`/`BOTTOM_BUFFER_RATIO` from `GraphLayout` (or mirror their values).

### 3. NOW indicator + forecast side missing in narrow
These are **symptoms of #1** — once the window is centered on/near NOW, the NOW dashed line, the
pink→yellow transition, and the future forecast (yellow dashed) reappear automatically (the draw
code already handles them; the window just excluded `now`). Verify after #1; no separate work
expected.

### 4. Verify narrow-specific details against the device
- **Hour labels:** desktop NARROW already uses `labelInterval = 1` (every hour) with day-night icons
  — matches Android. Confirm spacing/leftmost-label clamp still good at ±2h.
- **Smoothing:** desktop uses `smoothIterations = 1` for NARROW (`:133`). Android narrow looks
  near-raw; confirm 1 matches (don't over-smooth a 4h window).
- **Age label:** Android shows "(1h 25m)"/"(14m)" when stale. Desktop logic exists (`:343-375`,
  `ageMinutes >= 30`). Confirm it renders in narrow and the format matches Android
  (`"1h 25m"` style vs desktop `"1h25m"`).
- **Value labels:** start/min/current/end present in narrow; check collision handling at the tighter
  window.

---

## Click-in-NARROW behavior.
- (a) **Toggle** — click in WIDE zooms to clicked time; click again (in NARROW) returns to WIDE.
  Should match android implementation.

---

## Sequencing / notes
- Do **#1 (center on click)** and **#2 (min-buffer scaling)** together — they're the whole bug; #3/#4
  are verification once those land.
- **#2 changes the wide view too** (slightly less aggressive fill than the current 0.06). This is
  intended parity; flag to user since they explicitly tuned the wide fill — the min-buffer keeps wide
  nearly-full while fixing narrow. If they want wider fill, lower the ratio, not the absolute mins.
- Reuse the already-shared `ActualTemperatureSeriesBuilder`; port scaling logic from
  `GraphLayout.computeScaling` (android module, not shareable as-is — mirror the constants).
- After each compiling change: `scripts/buildStart.sh`, screenshot via
  `import -window <id>`, compare to Samsung (`adb -s RFCT71FR9NT` via on-device-file pull — `exec-out`
  corrupts the PNG with a multi-display warning) and emulator (`-s emulator-5554`).

## Refactor for shared code
- Feel free to refactor android side to increase code sharing.
