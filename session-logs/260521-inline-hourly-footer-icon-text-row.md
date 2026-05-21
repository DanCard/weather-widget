# Inline hourly-graph footer: `<hour><icon><a|p>` row

**Date:** 2026-05-21
**Area:** Hourly graph footer rendering (temperature, precipitation, cloud-cover)
**Status:** Implemented, unit tests green (0 failures), visually confirmed on Pixel 7 Pro. One debug `println` still pending removal.

---

## Problem / motivation

Two intertwined issues with the weather-condition icons at the bottom of the hourly graphs:

1. **Regression — icons went tiny.** Yesterday's commit `e7c2999` ("Fix inconsistent hourly graph
   vertical scaling and label positioning") multiplied the temperature footer icon size by
   `labelScale` in `GraphLayout.kt`:
   ```kotlin
   val iconSize = dpToPx(context, HourlyGraphDefaults.WEATHER_ICON_SIZE_DP * labelScale).toInt()
   ```
   Because `labelScale` is normally < 1 (graphs render at higher resolution than they display), a
   previously-fixed 15dp icon now rendered at ~9dp. The hour text was already scaled, so the icon
   shrank *relative to its old self* overnight.

2. **New look requested.** The footer was a two-row stack: weather icon *above* the hour label
   (`"3p"`). The user wanted a single inline row reading `<hour-number><weather icon><a|p>`
   (e.g. `3 ☀ p`), with a prominent icon.

Applies to **all three hourly graphs** (temperature, precipitation, cloud-cover), which share the
footer-rendering seam.

---

## Design

All three renderers build labels via `formatHourLabel(...)` → `"3p"` and draw them through the
shared `GraphRenderUtils.drawHourLabels(...)`, which previously drew the full text centered and used
an `onLabelDrawn` callback to stack the icon above. The whole change was centralized in that one
shared helper so the three graphs change together.

- **Icon size derives from the hour-label text**, not a dp constant
  (`GraphRenderUtils.footerIconSize(paint) = textHeight * FOOTER_ICON_TO_TEXT_RATIO`). This is the
  durable fix for the regression: icon and text now scale by the same `labelScale`, so their ratio
  is fixed regardless of widget size — the shrink class can't recur.
- **Single-row footer.** Layout reserves one icon-tall row instead of stacked icon+label rows;
  reclaimed vertical space goes to the graph curve.
- **Icon centered on the marker tick.** The icon box is pinned to the data-point x, with the hour
  digits hanging off the left and the meridiem off the right. (Earlier iteration centered the whole
  *group*, which let a wide hour like "11"/"12" shove the icon off-center.)
- **Density / overlap handled by marker cadence**, not a runtime overlap guard (see iteration log).

---

## Files changed

Production:
- `handlers/WidgetFormatUtils.kt` — added `formatHourLabelParts(time): Pair<String,String>`
  (`"3" to "p"`); `formatHourLabel` now delegates to it.
- `GraphRenderUtils.kt` — rewrote `drawHourLabels` for the inline group; added
  `footerIconSize(paint)`, `isNarrowWidget(numColumns)`, `footerIconGapDp(numColumns)`. Callback
  signature changed from `(index, clampedX)` to `(index, iconRect: RectF)`; added `iconSize`,
  `iconTextGapDp`, `hasIcon` params.
- `GraphLayout.kt` — `computeLayout` takes `footerIconSize`, reserves a single-row footer; added the
  tuning constants (below). Removed the now-unused `FOOTER_LABEL_HEIGHT_DP` / `ICON_TOP_PAD_DP`.
- `TemperatureGraphRenderer.kt` — `drawHourLabelsAndIcons` fills the supplied `iconRect`, takes
  `numColumns`; computes `footerIconSize` from the paint and passes it to `computeLayout`.
- `CloudCoverGraphRenderer.kt` — moved `ensurePaints` ahead of layout so the hour-label paint is
  available for `footerIconSize`; single-row footer; inline callback. **Contains a temporary
  `println("DBG_CLOUD …")` at ~line 451 — REMOVE before landing.**
- `PrecipitationGraphRenderer.kt` — single-row footer in `calculateLayout` (+ `footerIconSize`
  param), inline callback, `renderGraph` gained a `numColumns` param. Pre-computed icon collision
  bounds updated to the new bottom band.
- `handlers/PrecipViewHandler.kt` — passes `numColumns` to `renderGraph`; WIDE-zoom cadence is
  width-aware.
- `handlers/TemperatureHourDataBuilder.kt` — WIDE-zoom cadence is width-aware.

Tests:
- `TemperatureGraphLabelPlacementRobolectricTest.kt` — `computeLayout` call passes a real
  `footerIconSize` derived from the hour-label paint.
- `PrecipitationGraphRendererTest.kt` — the one icon-enabled `calculateLayout` test passes an
  explicit `footerIconSize`.
- `CloudCoverGraphLabelPlacementRobolectricTest.kt` — the right-edge test was relaxed (see below).

---

## Current tuned constants (`GraphLayout.kt` → `HourlyGraphDefaults`)

```kotlin
FOOTER_ICON_TO_TEXT_RATIO = 1.0f   // icon height == hour-text height
FOOTER_ICON_GAP_NARROW_DP = -2f    // tight overlap on narrow widgets
FOOTER_ICON_GAP_WIDE_DP   = -1f    // slight overlap on wide widgets
FOOTER_BOTTOM_INSET_DP    = 1f
NARROW_WIDGET_MAX_COLUMNS = 6      // <=6 cols = narrow (phone); 7+ = wide (tablet)
NARROW_WIDE_LABEL_INTERVAL = 6     // narrow WIDE-zoom: marker every 6h (wide keeps 4h)
```

Width class is keyed off `numColumns` (already plumbed into builders and renderers).
`cols = round((widthDp + 15) / 70)`, so a full-width Pixel 7 Pro widget ≈ 6 cols.

| | Narrow (≤6 cols) | Wide (≥7 cols) |
|---|---|---|
| WIDE-zoom marker cadence | every 6h | every 4h |
| icon↔text gap | −2dp | −1dp |
| icon size | = text height | = text height |

---

## Iteration history (what we tried, in order)

1. **Initial inline build** with icon ratio 1.4 + group-aware overlap guard. On the Pixel 7 Pro the
   bottom row was heavy mush — the inline groups are ~2–3× wider than the old footprint, and the
   group-aware guard plus a too-dense cadence still collided.
2. **Tuning rounds** (user-driven): negative padding −8 → −4 → −2; icon ratio 1.4 → 1.0.
3. **Dropped the runtime group-aware overlap guard** in favor of controlling density at the
   data-building stage via marker cadence (`labelInterval`). Cleaner, even spacing, decided before
   render.
4. **Made cadence + padding width-conditional** (narrow vs wide) on the user's request to keep 4h on
   wide displays and only negative-pad on narrow.
5. **Fixed the threshold:** Pixel 7 Pro reports **6 columns**, so the original `≤5` narrow threshold
   misclassified it as wide (no 6h cadence). Raised to `≤6`.
6. **Centered the icon on the marker tick** (was centering the whole group).
7. **Wide gap −1dp** so wide displays also get a touch of overlap.

---

## Gotchas discovered

- **`paint.fontMetrics` is null/zeroed in tests.** Plain-JUnit stubs return null; Robolectric's
  ShadowPaint returns zeroed metrics. `footerIconSize` must fall back to `paint.textSize` when the
  metric-derived height isn't positive, or the icon collapses to 0 (and the inline path silently
  never runs — that produced an empty-icon-list test failure).
- **Cloud-cover right-edge label test** encoded the old stacked geometry (icon hugged the widget
  edge → low-cloud label forced above). With the inline footer the icon is inset from the edge, so
  the label fits below. The above/below outcome is now tuning-sensitive, so the test was relaxed to
  assert only that the right-edge label *is placed* (collision logic found a clean slot) — the
  stable invariant.
- **`WidgetStateManagerTest`** pins `ZoomLevel.WIDE.labelInterval`; we briefly bumped the enum to 6
  then reverted (cadence is now width-conditional in the builders, the enum stays 4).

---

## Outstanding / follow-ups

- [ ] **Remove the `DBG_CLOUD` `println`** in `CloudCoverGraphRenderer.kt` (~line 451) — kept in at
      the user's request during tuning.
- [ ] **Cloud-cover cadence:** it still uses its own pre-existing `numColumns`-based label-index
      lists; only the inline padding + icon-centering apply there, not the 6h-on-narrow cadence.
      Decide whether cloud-cover markers should also follow the 6h-on-narrow rule.
- [ ] Build/install was always run by the user; if a moon-style icon still looks left-shifted after
      centering, suspect the **drawable's own internal glyph offset** (not layout).

## Verification

- `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL, 0 failures.
- Visual: Pixel 7 Pro (`adb -s 2A191FDH300PPW`), full-width widget. User confirmed "looks good."
- Screenshot workflow (per CLAUDE.md): `adb exec-out screencap -p > /tmp/s.png && convert … s.jpg`.
