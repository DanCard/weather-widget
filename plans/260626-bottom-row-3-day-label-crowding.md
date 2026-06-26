# Drop clipped daily footer labels in the 3-day hourly temperature graph

## Context

In `THREE_DAY` zoom the hourly temperature graph switches its footer from time-of-day
labels (`12p`, `3p`) to **one date label per visible day** — `formatDateLabel` →
`"Tue 23"` — centered near each day's local noon (see
`TemperatureHourDataBuilder.kt:243-289`, `dateMode`).

On narrow widgets (reported on Samsung) the partial first/last day's noon sits near the
canvas edge. The current footer code (`GraphRenderUtils.drawHourLabels`,
`app/.../widget/GraphRenderUtils.kt:248-280`) never drops a label — it only
`coerceIn`-clamps the text's center to keep it on-canvas. That clamp shoves `"Tue 23"`
inward where it collides with / jams against the neighbor day, so it visually "doesn't
fit."

**Desired outcome (per user):** drop *only* the individual daily label(s) that would be
clipped by the canvas edge (the "edge clip only" criterion), leaving the labels that fit
untouched. The dropped label's weather icon goes with it. No slanting, no shrinking.

## Approach

Make `drawHourLabels` skip a label entirely when, drawn at its true (un-clamped) center,
its text would extend past either canvas edge — but only for labels opted in via a
predicate, so existing short hour labels (which rely on the edge clamp to stay visible)
are unaffected.

### 1. Add a per-row date-label flag — `shared/.../graph/HourData.kt`
Add `val isDateLabel: Boolean = false` to `HourData`. Default `false` keeps desktop and
all non-date paths unchanged (desktop has its own footer logic and won't read it).

### 2. Set the flag for date-mode rows — `app/.../widget/handlers/TemperatureHourDataBuilder.kt`
Where the date label is assigned (`label = if (dateMode) formatDateLabel(...) else ...`,
~line 289), also set `isDateLabel = dateMode` on the same `base.copy(...)`.

### 3. Pure, testable clip predicate — `app/.../widget/GraphRenderUtils.kt`
Add a small pure helper next to the other utils:
```kotlin
/** True when a label of [textWidth], centered at [centerX], would clip either edge of a
 *  [widthPx]-wide canvas (i.e. it can only be shown by clamping it inward). */
fun labelClipsEdge(centerX: Float, textWidth: Float, widthPx: Int): Boolean =
    centerX - textWidth / 2f < 0f || centerX + textWidth / 2f > widthPx
```

### 4. Use it in `drawHourLabels` — `app/.../widget/GraphRenderUtils.kt:201-281`
- Add a parameter `dropIfClipped: (T) -> Boolean = { false }` (default preserves precip /
  cloud / hour-mode behavior).
- Inside the per-item loop, right after `val fullLabel = labelText(item)` (line 252) and
  before the inline/plain branch, add:
  ```kotlin
  if (dropIfClipped(item) &&
      labelClipsEdge(centerX, hourLabelTextPaint.measureText(fullLabel), widthPx)) {
      return@forEachIndexed   // not enough space — drop the whole daily label (text + icon)
  }
  ```
  Returning before `lastHourLabelX = centerX` means a dropped label neither draws its text,
  invokes `drawIcon`, nor consumes spacing. `measureText(fullLabel)` is the same width the
  inline branch already uses (it recombines `hourText + meridiem` into the original string),
  so the test matches what would actually be drawn.

### 5. Opt the temperature renderer in — `app/.../widget/TemperatureGraphRenderer.kt:369-383`
In `drawHourLabelsAndIcons`, pass `dropIfClipped = { it.isDateLabel }` to
`GraphRenderUtils.drawHourLabels`.

### Notes / scope
- **Stacked-graph consistency (optional):** the precip and cloud graphs draw the same
  date footer at `THREE_DAY` (`PrecipitationGraphRenderer.kt:684`,
  `CloudCoverGraphRenderer.kt:252`). If their footer item type also exposes the date-label
  flag, pass the same `dropIfClipped` predicate so a label dropped on the temp graph is
  also dropped on the graphs stacked beneath it. Confirm their item type during
  implementation; if it's not `HourData`, leave them as-is (out of the user's stated scope).
- **Not extracted to `:shared`:** desktop intentionally *slants* on overlap
  (`footerLabelsWouldOverlap` in `DesktopGraphUtils.kt`) rather than dropping, so the
  behavior differs by platform; the one-line geometry isn't worth a shared abstraction.
- **`lastLabeledIndex` icon-skip** (GraphRenderUtils.kt:237-245) is computed before drops
  and isn't recomputed; if the last date label is dropped, the new last-drawn label keeps
  its icon. Harmless and acceptable.

## Files
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/HourData.kt` — add `isDateLabel`
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureHourDataBuilder.kt` — set it
- `app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt` — `labelClipsEdge` + `dropIfClipped` param
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — pass the predicate

## Verification

**Unit test** (pure, no Paint stub issues):
- Add a test for `labelClipsEdge` covering: centered label that fits (false), label whose
  half-width pushes past the left edge (true), past the right edge (true), exact-fit
  boundary (false). Run with
  `./gradlew testDebugUnitTest --tests "*GraphRenderUtils*"` (or the chosen test class).
- Note: a renderer-level test can't exercise the drop because plain-JUnit `measureText`
  returns 0 (`labelClipsEdge` then never trips) — keep coverage on the pure helper.

**On-device (the actual reported case):**
1. `./gradlew installDebug`
2. Add the widget and size it narrow (the Samsung-style width where `"Tue 23"` was
   clipped); switch to the 3-day hourly view.
3. Capture and inspect a screenshot per CLAUDE.md (PNG → JPG):
   ```bash
   adb exec-out screencap -p > /tmp/screenshot.png && convert /tmp/screenshot.png /tmp/shot.jpg
   ```
   Confirm: the partial edge day's `"Tue 23"` is now omitted instead of jammed/clipped,
   while the fully-visible interior day label(s) still render. Widen the widget and confirm
   all three day labels return once there's room.
