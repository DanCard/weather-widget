# Desktop daily view: let high-temp / rain labels bleed up into the header

## Context

In the desktop daily forecast view, the high-temperature label for the hottest
day (e.g. Thursday "89°") is drawn *overlapping* its vertical bar instead of
sitting *above* it like every other day's label.

Root cause (in
`desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt`): the
graph canvas deliberately uses `top = 2f * scale` (line 78) so the hottest bar
runs to the very top of the band. For that hottest day, `yAt(rawMax)` lands only
a few px below the canvas top, so the label position
`highY - textHeight - 3f` (line 125) goes negative — and `.coerceAtLeast(0f)`
pins it back down onto the bar.

The user's intent: rather than reserve space to keep the label off the header,
**allow the temp/rain labels to overlap up into the header row a little** — they
find that playful overlap fun, and it should happen naturally for the tallest
bars.

### Why this is feasible (key finding)

The popup layout is a `Column` (Main.kt:534): `WidgetHeader`, then
`Spacer(4.dp)`, then the daily graph `BoxWithConstraints(weight 1f)` containing
the `DailyForecastGraph` Canvas (Main.kt:629–659).

- Compose does **not** clip draw output to a node's bounds by default. A
  `Canvas`/`drawBehind` may draw outside its layout bounds unless an ancestor
  opts into clipping. No `clipToBounds`/`Modifier.clip` exists on the graph Box
  or Canvas.
- The only clipping ancestor is the `Surface` (Main.kt:527), which clips at the
  **window** edge — not at the graph's top. So a label drawn at a negative Y
  inside the graph overflows up over the 4dp spacer and into the header.
- The graph is the Column's **last** child, so it paints **on top of** the
  header — the overflowing label is visible above the header content.

So the original bug fix and the "fun overlap" feature are the same change: stop
clamping labels at the canvas top and let them ride up as far as they naturally
need to (which, for the hottest bar, is a few px into the header).

## Approach

Keep `top = 2f * scale` (bars still fill the full height). Replace the hard
top-edge clamps on the high-temp and rain labels with a bounded upward "bleed"
allowance so they may extend a little above the canvas top into the header.

**File:** `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt`

1. Define a bleed allowance near the geometry block (after `top`, ~line 78).
   Size it so the hottest day's high label can fully clear its bar (≈ its text
   height + the 3px gap), which is also a pleasant "little" poke into the header:
   ```kotlin
   // Labels may ride a little above the canvas top, overlapping the header row.
   val headerBleed = 12f * scale * 1.4f + 4f * scale   // ~high-label height + gap
   ```

2. High-temp label (line 125): change the lower bound from `0f` to `-headerBleed`:
   ```kotlin
   val highLabelY = (highY - highText.size.height - 3f * scale).coerceAtLeast(-headerBleed)
   ```
   For the hottest day this lands a few px above the canvas top (above the bar,
   into the header); for every other day the value is already positive, so
   nothing changes.

3. Rain label (line 156): it anchors above the high label, so give it a little
   more headroom so it stays above the temp label when both ride up. Change
   `.coerceAtLeast(2f)` to allow bleed past the temp label:
   ```kotlin
   drawText(rainLayout, topLeft = Offset(centerX - rainLayout.size.width / 2f,
       anchorY.coerceAtLeast(-headerBleed - rainLayout.size.height - 2f * scale)))
   ```

4. Update the comments on lines 73–75 and 124 — the labels no longer "overlap
   the bar"; they now ride up over the header by design.

### Notes / accepted trade-offs

- Overlap only occurs for the tallest bar(s); short bars keep their labels well
  inside the canvas. This is the "happens occasionally, a little" behavior the
  user asked for.
- The hottest day could be the center column, whose label may brush the centered
  date text in the daily header. This is accepted as fun/edge-case imperfection
  (consistent with the user's best-effort preference). `headerBleed` is sized
  modestly to keep the poke small.
- Optional: if useful while tuning, add a temporary `Log.d` of `highLabelY` and
  the hottest day's label, then remove after confirming visually.

## Verification

- Build + restart the repo distributable per CLAUDE.md:
  `scripts/restart-desktop-distributable.sh` (or `scripts/build-exe-and-restart.sh`).
- Open the daily view; confirm the hottest day's high label (the reported
  "89°") now sits above its bar and pokes a little into the header band instead
  of covering the bar.
- Check a rainy + hot day: the rain `%`/`mm` label should sit above the temp
  label, also bleeding slightly into the header, still legible.
- Sanity-check a flat day-set (all temps similar) and a wide spread — only the
  tallest bar's label should bleed; others stay inside the graph.
- No unit tests cover this Compose `DrawScope` renderer; verification is visual
  via the running desktop app.
