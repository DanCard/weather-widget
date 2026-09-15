# Plan: Share Daily Rain Label Placement & Draw On Top on Desktop

**Date**: 2026-09-15
**Goal**: Resolve rain chance label collision with prior-day forecast bars on the daily forecast view (specifically in the Today column), ensure the label is drawn clearly on top with proper outline/contrast, and unify/share the rain label placement logic between Android and Desktop in `:shared`.

---

## 1. Problem Statement & User Requests

### User Feedback
1. *"80% rain chance lable on daily forecast view is hard to read because prior day forecast overlaps it. Rain chance label should be drawn on top, the two shouldn't collide."*
2. *"daily forecast view, today column."*
3. *"It draws correctly on top on android, but not desktop. Would be nice if this code was shared."*

---

## 2. Empirical Evidence & Root Cause Analysis

### A. Desktop Runtime Capture (`/tmp/daily_crop.png` & `/tmp/rain_crop.png`)
1. **Pixel-level inspection**:
   - In Today's column, Open-Meteo's headline forecast high is `65.5°` (`singleHigh`), with rain probability `80%`.
   - The prior-day forecast snapshot for today (`snapshotHigh`) predicted `69.0°`.
   - In `DailyForecastGraph.kt`, the prior-day snapshot bar is drawn on the left flank of Today's column (`centerX - compactTodayTripleOffset`), extending vertically up to `yAt(69.0) - capRadius` (screen Y ≈ 18..44px).
   - The headline high label `65.5°` is placed at `yAt(65.5)` (screen Y ≈ 47..61px).
   - The daytime rain label `80%` is anchored to `highLabelTopAtCenter` with `gapPx = -3dp * scale`:
     `anchorY = highLabelTopAtCenter - gapPx - rainLayout.size.height` (screen Y ≈ 17..27px).
   - **Collision**: `anchorY` places the `80%` text directly inside the top cap of the 69° prior-day forecast bar.
   - **Styling deficit**: Desktop calls `drawText(rainLayout, topLeft = rainTopLeft)` with plain text and NO outline or shadow (`TextStyle(color = COLOR_FORECAST_RAINY)`).
   - Pixel sampling reveals the light blue text `(90, 143, 191)` is drawn directly over the grey bar `(142, 153, 164)`. Because of the lack of outline or shadow, the bar shows through the hollows of the `8` and `%`, washing out contrast and appearing as though the bar overlapped or swallowed the label.

### B. Android Runtime Capture (`/tmp/android_screen.png` & `/tmp/android_rain_zoom.png`)
1. On Android (`emulator-5554`), the same forecast (`65.6°`, `80%` rain chance) is displayed.
2. In `DailyGraphPaintCache.kt`, Android configures `rainTextPaint` with:
   `setShadowLayer(shadowRadius, 0f, shadowDy, 0xFF000000.toInt())`
3. When `DailyForecastRainLabelRenderer.kt` executes `canvas.drawText(rainText, ...)`, it draws with this dark shadow/outline.
4. Because the text has a prominent dark outline/shadow and is drawn after the bars, it is clearly rendered **ON TOP** of the underlying bar ink, maintaining full legibility.

### C. Architecture Duplication
1. Currently, `:shared` (`DailyRainLabels.kt`) only defines string formatting, precipitation probability lookup, and a few constants (`RAIN_HIGH_TEMP_GAP_DP = -3f`).
2. Placement geometry is duplicated:
   - Android has `DailyForecastRainLabelRenderer.resolveRainAboveHighPlacement` and night label tucking (`resolveNightAnchorBaseline`, `resolveNightHorizontalFit`, `resolveNightCollision`).
   - Desktop manually re-implemented ~130 lines of this exact placement math in `DailyForecastGraph.kt` (lines 550–680), using divergent metrics and omitting outline/shadow rendering.
3. Furthermore, neither platform currently accounts for `snapshotHigh` in Today's column when `snapshotHigh > effectiveHigh`. When the prior-day forecast bar is taller than the headline high label, the bar punches up past the headline high and directly into the rain label's space.

---

## 3. Proposed Fix & Refactoring Plan

### Phase 1: Share Rain Label Placement Logic in `:shared`
Move the pure placement math into `:shared` (e.g. in `com.weatherwidget.shared.graph.DailyRainLabelPlanner` or `DailyRainLabels`):
1. **Daytime Rain Label Anchor & Clearance**:
   - `resolveDayRainAnchorTop(highLabelTop: Float?, snapshotBarTop: Float?): Float?`:
     `listOfNotNull(highLabelTop, snapshotBarTop).minOrNull()` (in screen coordinates, `min` is topmost/closest to top edge).
   - When Today has a prior-day snapshot bar that reaches higher than the headline high label (`snapshotBarTop < highLabelTop`), the rain label anchors above `snapshotBarTop` so the two do not collide!
   - Provide a pure placement function:
     `resolveRainAboveHighPlacement(anchorTop: Float, rainHeight: Float, gapPx: Float, floorY: Float): Float`
2. **Nighttime Rain Label Tucking & Collision**:
   - Extract the shared geometry calculations:
     - `calculateNightTuckParams(leftLowY, rightLowY, leftLowHeight, availableHeight, scale)`
     - `calculateNightShift(centerX, columnWidth, hNudgePx, roomyRightPx, halfWidth, canvasWidth, edgeMargin)`
     - `resolveNightCollision(nightCenterX, nightBaseline, nightHalfWidth, ascent, descent, ownBox)`
   - Pure functions with zero Android/AWT framework dependencies.

### Phase 2: Update Desktop (`:desktop`) to Draw On Top with Outline
1. In `DailyForecastGraph.kt`:
   - Replace inline placement calculation with the shared planner in `:shared`.
   - Include `snapshotBarTop` when computing Today's daytime rain label anchor so the label sits above both the headline high and the prior-day forecast bar.
   - For daytime rain labels: replace plain `drawText(...)` with `drawOutlinedText(textMeasurer, rainLayout, rainTopLeft)` (matching the `drawOutlinedText` pattern already used for `highText` on Today and past days).
   - For nighttime rain labels: replace plain `drawText(...)` with `drawOutlinedText(textMeasurer, finalLayout, Offset(finalX, resolvedTop))`.
2. This ensures the rain labels are always rendered with high-contrast outlines and draw cleanly on top of any overlapping graphics.

### Phase 3: Update Android (`:app`) to Consume Shared Placement
1. In `DailyForecastRainLabelRenderer.kt`:
   - Wire `resolveRainAboveHighPlacement` and night tucking to delegate to the shared `:shared` functions.
   - Pass `snapshotBarTop` for Today so Android also guarantees clearance when `snapshotHigh > effectiveHigh`.

### Phase 4: Unit Tests & Verification
1. **Unit tests in `:shared`**:
   - Test daytime rain label placement: normal high label anchor, snapshot bar clearance when `snapshotBarTop < highLabelTop`, ceiling/floor clamping.
   - Test nighttime tuck parameters and collision resolution.
2. **Unit tests in `:desktop` and `:app`**:
   - Verify existing duration buckets (`testShortShared`, `testShortDesktop`, `testShortDebugUnitTest`).
3. **Live Desktop Verification**:
   - Restart desktop app via `scripts/buildStart-desktop.sh` or `fast-desktop-restart.sh`.
   - Pop up the widget on the 720p monitor and capture a screenshot of Today's column.
   - Verify visually that the `80%` label sits cleanly above the prior-day forecast bar and is drawn with a crisp outline on top.
4. **Android Emulator Verification**:
   - Verify on `emulator-5554` that the widget still renders cleanly and passes all instrumented/unit tests.
