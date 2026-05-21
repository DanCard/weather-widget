# Inline hourly-graph footer: `<hour><icon><a|p>`

## Context

Two things motivate this change:

1. **Regression to fix.** Yesterday's commit `e7c2999` ("Fix inconsistent hourly graph vertical scaling and label positioning") added `* labelScale` to the temperature footer icon size in `GraphLayout.kt:101`. Because `labelScale` is normally < 1 (graphs render at higher resolution than they display), the previously-fixed 15dp weather icons now render at ~9dp — the "tiny" icons the user noticed. The hour text was already scaled, but the icons were not, so the icons became proportionally small overnight.

2. **New look.** The footer is currently a **two-row stack**: weather icon on top, `"3p"` hour label below it. The user wants a **single inline row** reading `<hour-number><weather icon><a|p>` — e.g. `3 ☁ p` — with the icon **~1.4× the hour-text height** so the weather condition is the visual focus.

This applies to **all three hourly graphs** (temperature, precipitation, cloud-cover), which all share the same footer-rendering seam.

Intended outcome: one compact, legible footer row per labeled hour, with a prominent weather icon that scales proportionally with the text at every widget size (so it can never silently shrink again).

## Design

All three renderers build labels with `formatHourLabel(...)` → `"3p"` and draw them via the shared `GraphRenderUtils.drawHourLabels(...)`, supplying an `onLabelDrawn` callback that draws the icon stacked above. Centralize the new inline layout in that one shared helper so all three graphs change together and stay consistent.

The icon's actual tint logic (`isNight` / `isTwilight` / `isSunny` / rain-vs-mixed) is renderer-specific and must stay in each renderer. So the helper computes the inline geometry and hands each renderer a ready-to-fill icon `RectF`; the renderer's callback only sets bounds + tint + draws.

### Key files & changes

**1. `app/src/main/java/com/weatherwidget/widget/handlers/WidgetFormatUtils.kt`**
- Add `internal fun formatHourLabelParts(time: LocalDateTime): Pair<String, String>` returning `(hourText, meridiem)` — e.g. `("3", "p")`, `("12", "a")`.
- Refactor existing `formatHourLabel` to delegate: `formatHourLabelParts(time).let { it.first + it.second }` — preserves all current callers/strings.

**2. `app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt` — `drawHourLabels(...)` (line 213)**
- This is the core change. Replace the single centered `drawText(fullLabel)` + stacked-icon callback with an inline group layout:
  - Split each label into `(hourText, meridiem)`. Simplest: split the passed label on its last char (`dropLast(1)` / `takeLast(1)`), since every caller's label ends in `a`/`p`. Optionally accept the split as a lambda param defaulting to that, to avoid baking the assumption in.
  - Compute `iconSize` from the text paint, not from a dp constant: `iconSize = (hourLabelTextPaint.fontMetrics height) * FOOTER_ICON_TO_TEXT_RATIO`. This makes the icon track the (already labelScale-scaled) text — fixing the shrink permanently.
  - Measure `hourW = paint.measureText(hourText)`, `meridiemW = paint.measureText(meridiem)`, plus a small inter-element gap (`FOOTER_ICON_GAP_DP`). Group width = `hourW + gap + iconSize + gap + meridiemW`.
  - Center the group at `clampedX` (clamp the group, not just text, to stay on-screen). Because `hourLabelTextPaint` is `Align.CENTER`, draw each text fragment at *its own* center x; lay out fragments left→right across the group.
  - Single baseline near the bottom; vertically center the icon `RectF` on the text's visual midline (icon extends above/below the text since it's taller). Pass that `RectF` to the callback.
  - Change the trailing lambda signature from `(index, clampedX)` to `(index, iconRect: RectF)`.
- Update the horizontal de-dup spacing: `minHourLabelSpacing` comparison should account for the wider inline group so adjacent labeled hours don't collide (compare against group width, or have callers pass a spacing already sized for it).

**3. The three renderer call sites** — update each `onLabelDrawn` lambda to take the supplied `iconRect`, set the drawable bounds to it, apply the existing tint logic, and draw. Delete the now-dead per-renderer `iconX/iconY/iconSize` math.
- `TemperatureGraphRenderer.kt` — `drawHourLabelsAndIcons()` (lines 353–392). It still returns `drawnIconBounds` (add the supplied `iconRect`).
- `PrecipitationGraphRenderer.kt` — lambda at lines 831–853 (keep the `showHourlyIcons` guard and `onHourIconDrawn?.invoke(index)`).
- `CloudCoverGraphRenderer.kt` — lambda at lines 241–267 (keep `drawnIconBounds.add`).

**4. Footer height reservation** — the footer is now one row instead of two, so reserve `iconSize` (the tallest element) plus minor padding instead of `labelHeight + iconSize`. Hand the reclaimed vertical space to the graph curve. Update:
- `GraphLayout.computeLayout()` (`GraphLayout.kt:99–112`) — recompute `footerTop`/`graphBottom` for a single-row footer; drop the now-irrelevant `* labelScale` icon-size line (icon size now derives from text in the renderer).
- `PrecipitationGraphRenderer.calculateLayout()` (`graphBottom` calc, lines 184–191).
- `CloudCoverGraphRenderer` `graphBottom` calc (lines 162–169).

**5. Constants** — in `GraphLayout.kt` `HourlyGraphDefaults`:
- Add `FOOTER_ICON_TO_TEXT_RATIO = 1.4f` and `FOOTER_ICON_GAP_DP` (small, e.g. `1.5f`).
- `WEATHER_ICON_SIZE_DP` (15f) is no longer used for footer sizing; confirm it isn't relied on elsewhere (watermark uses its own `WATERMARK_ICON_SIZE_DP`) before removing, otherwise leave it.

`★ Insight ─────────────────────────────────────`
- Deriving icon size from the *text paint* (not a dp constant) is the durable fix: the icon and label now scale by the same `labelScale`, so the ratio between them is fixed at 1.4 regardless of widget size — the regression class can't recur.
- Centralizing in `drawHourLabels` means one change updates all three graphs; the renderer callbacks shrink to just "fill this rect," which removes three near-identical copies of icon-positioning math.
`─────────────────────────────────────────────────`

## Verification

1. **Build & install:** `./gradlew installDebug`
2. **Visual check (primary — this is a rendering change):** add/resize the widget, then capture each hourly view. Toggle to the precip and cloud-cover views too.
   ```bash
   adb exec-out screencap -p > /tmp/shot.png && convert /tmp/shot.png /tmp/shot.jpg
   ```
   Read `/tmp/shot.jpg`. Confirm:
   - Footer reads `<hour><icon><a|p>` on one line (e.g. `3☁p`), not stacked.
   - Icon is clearly larger than the text and no longer tiny.
   - Hour labels don't overlap at narrow widths; group stays on-screen at edges.
   - Night/twilight/sunny tints and rain/mixed icons still render correctly.
   - The graph curve gained the vertical space freed by collapsing the two rows.
3. **Sizes:** check 1x3/2x3 and a wide (4+ col) widget, plus portrait, to confirm proportional scaling holds.
4. **Tests:** `./gradlew testDebugUnitTest` — update any unit tests asserting on stacked-icon bounds or `drawHourLabels` callback signature. If a `reapply()`-style render test exists for footers, run it.
