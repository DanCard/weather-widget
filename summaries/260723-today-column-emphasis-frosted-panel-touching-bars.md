# Today-column emphasis: frosted-glass panel + touching triple bars (shared)

**Date:** 2026-07-23
**Modules:** `shared`, `app` (Android widget), `desktop` (Compose tray)

## Goal

The daily forecast view's **today** column was hard to pick out at a glance — it differed from its
neighbors only by a bold "Today" label and a small current-temp dot. Make it the obvious focal
column, and share the treatment between Android and desktop.

## What shipped

Two reinforcing changes to the today column, plus a small dot for the current temp already present:

1. **Frosted-glass focal panel** drawn *behind* the today column's three bars — a translucent
   (~12% white) rounded rect over the dark background. Not a real blur (the widget bitmap has no
   wallpaper handle); low-alpha fill reads as glass, matching the Apple-glass aesthetic. **Borderless
   by design** — an initial hairline stroke traced a crisp perimeter that read stronger than the soft
   interior, so it was removed (user feedback).
2. **Touching triple bars** — the today column's three bars (24h-prior snapshot · thermostat+bulb ·
   live forecast) were spread, then pulled to touch edge-to-edge so today reads as one wide unit.

## Shared code (single source of truth)

New `shared/src/main/kotlin/com/weatherwidget/shared/graph/TodayColumnHighlight.kt` — pure math, no
platform graphics:

- `tripleBarSpacing(centerBarWidthPx, flankBarWidthPx, dayWidthPx, spacingFactor=1.0, columnEdgeMarginPx)`
  Two bars touch when centre-to-centre distance = **average of their two widths**. This is
  width-agnostic, which matters because the platforms use different widths:
  - **Android** draws all three bars equal width → offset = one bar width.
  - **Desktop** draws thinner flanking bars (`thinWidth = barWidth * 0.65`) → offset = `(barWidth + thinWidth)/2`.
  `spacingFactor` <1 overlaps, >1 gaps. Result clamped so a flank bar's outer edge never bleeds into
  the neighbouring column on narrow/many-column layouts.
- `panelBounds(...)` → `GraphRect` for the panel, spanning the bars horizontally (clamped to the
  half-column) and from `graphTop` (lifted by a top margin) down to just above the day-label band.
- Constants: `PANEL_FILL_ARGB = 0x1FFFFFFF`, `PANEL_CORNER_RADIUS_DP = 12`,
  `PANEL_HORIZONTAL_PADDING_DP = 9`, `PANEL_TOP_MARGIN_DP = 4`, `DEFAULT_SPACING_FACTOR = 1.0`.

Each platform draws with its own API (Android `Canvas.drawRoundRect`, Compose
`DrawScope.drawRoundRect`); only the numbers live in `:shared`.

## Consumers

- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` — draws the panel before
  `drawDayBars` for the today column; `tripleBarOffset` now comes from `tripleBarSpacing(...)`.
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt` — draws the panel at the
  start of the `day.isToday` branch (before the bars); `tripleOffset` now from `tripleBarSpacing(...)`.

## Tests

- New `shared/src/test/.../graph/TodayColumnHighlightTest.kt` — 7 cases: equal-width touch,
  unequal-width (desktop) touch = average, overlap/gap factors, column clamp, panel span, panel
  half-width cap. Passes.
- Existing Android renderer tests still pass — they assert bar **ordering** (snapshot left of
  thermostat, forecast right), not absolute x, so spacing tweaks don't regress them.
- Desktop compiles; verified visually running (`scripts/buildStart-desktop.sh`, `.show` trigger).

## Tuning knobs (one place, both platforms)

- Pop/subtlety → `PANEL_FILL_ARGB` alpha byte.
- Bar closeness → `spacingFactor` (`1.0` touch, `0.9` overlap, `1.15` gap).
- Panel size → `PANEL_HORIZONTAL_PADDING_DP`, `PANEL_CORNER_RADIUS_DP`, `PANEL_TOP_MARGIN_DP`.

## Key lesson

The "touching" condition (distance = average of the two bar widths) is the one fact that holds whether
bars are equal (Android) or unequal (desktop). Encoding *that* — not the pixel offset — in `:shared`
means future spacing/opacity changes are a one-line edit both platforms pick up, avoiding the
Android/desktop drift documented across the existing desktop-parity notes.

## Status

Changes uncommitted on `main` at time of writing (spanning `shared`, `app`, `desktop`).
Memory: `today_column_highlight_shared.md`.
