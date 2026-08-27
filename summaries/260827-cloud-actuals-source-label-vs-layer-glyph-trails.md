# Cloud actuals-source label drawn through the mid/high glyph trails (2026-08-27)

## Problem

Desktop popup, source Meteo, ~18h window: the free-floating annotation
`Actual cloud cover data from Synoptic` was drawn straight across a dense bank of `h` and `m`
glyphs in the upper-right of the cloud graph — text on top of data, both illegible.

Reported as: *"label 'Actual cloud cover data from …' collides with cloud cover graph. Should be
trying to find open space, if none, then don't draw it."*

Screenshot of the running app confirmed it before any code was read: the annotation sat at roughly
x 948–1320, y 205–228, inside the high-layer trail, while the low forecast curve it *was* aware of
lay flat along the bottom of the plot at 0–3%.

## Root cause

`GraphEmptySpaceFinder` knows exactly two things about a canvas:

1. the ys returned by the caller's `curveYsAt`, and
2. the rects in the caller's `drawnBounds` / `vetoBounds`.

The mid/high cloud layer glyph trails (`4142c4eb`, *Draw Open-Meteo mid and high cloud layers as
h/m glyph curves*) were in **neither**. `CloudCoverGraph.kt`'s `curveYsAt` carried the low forecast
curve and the observed curve; `drawnBounds` carried the `%` value labels. So a plot whose whole
upper half was ink scored as wide-open air, the ladder's top rung succeeded immediately, and the
annotation was placed with high confidence in the emptiest-looking region — which was the trail.

This is the same omission the finder's own class doc already documents twice from other angles
(the forecast dashes it once shipped on top of; the `Tue` day label it once sat shoulder-to-shoulder
with). The failure mode is structural: **adding ink to a graph is not the same as adding an
obstacle, and nothing enforces the pairing.**

The identical gap existed in Android's `CloudCoverGraphRenderer` for its dominant-station label —
same glyph pass, same two inputs, same omission.

## Fix

`CloudLayerGlyphPlacer.glyphBounds(glyphs, glyphWidthPx, glyphHeightPx)` turns already-placed
glyphs into ink boxes, centred on each glyph the way both renderers draw them (Android
`Paint.Align.CENTER`, desktop a half-size top-left shift). Both renderers hoist a `layerGlyphBounds`
list out of the glyph block and append it to the annotation's `drawnBounds`.

Two design choices worth keeping:

**Boxes, not a curve in `curveYsAt`.** Ink is what collides, and the ink is not the polyline:

- the coincident-layer nudge (`nudgePx`, applied within `COINCIDENT_DELTA`) deliberately moves a
  glyph *off* its own polyline, so a curve model under-blocks there;
- the `MIN_COVER` floor and null covers mean long stretches of that polyline carry no ink at all,
  so a curve model over-blocks there.

**Per-glyph, not merged runs.** Merging a trail into one bounding rect is cheaper but fences off
the large empty triangle beside any steep trail — which on this graph is exactly where the room is.
~250 tiny rects against a few hundred candidate boxes is not a measurable cost in a render that
already interpolates every series per sample.

The *"if none, then don't draw it"* half needed no new code: `DominantStationLabel.place` already
returns null when the finder exhausts its clearance ladder, and the annotation is simply skipped.
That path was previously unreachable for this collision because the glyphs never registered as
obstacles in the first place.

### Files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/CloudLayerGlyphPlacer.kt` — new
  `glyphBounds`, plus the `GLYPH_BOX_*_RATIO` constants that size it from dp.
- `desktop/src/main/kotlin/com/weatherwidget/desktop/CloudCoverGraph.kt` — collect bounds, feed the
  actuals-source annotation.
- `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt` — same, for the
  dominant-station label. Fixed alongside desktop rather than left divergent.

Both platforms size the box from the glyph's type size times the shared ratios, so they fence off
an identical footprint without either consulting a font. See *The font-engine problem this exposed*
below — that started as a testability workaround and turned out to be fixing a live parity bug.

## Tests

Two layers, because they cover different failure modes.

### Geometry — `shared/.../CloudLayerGlyphLabelCollisionTest.kt`

Five cases spanning `CloudLayerGlyphPlacer`, `GraphEmptySpaceFinder` and `DominantStationLabel`.
Pure geometry with metrics passed in, so no font engine is involved.

1. **`without the trails as obstacles the annotation lands on the glyphs`** — the reproduction, and
   a guard from below: if the scene ever stops colliding, the test opposite it proves nothing.
2. **`with the trails as obstacles the annotation clears every glyph`** — the fix.
3. **`a plot the trails fill leaves the annotation undrawn`** — the second half of the requirement,
   on a short plot with layers sawtoothing its full height every hour.
4. **`glyph boxes are centred on the glyph and sized from its type size`** — the helper's contract.
5. **`a glyph with no type size contributes no obstacle`** — the degenerate-input guard.

The NOW veto band is load-bearing in the scene, not scenery: a 370px label spanning the hairline is
vetoed, which is what rules out the left-hand anchors (`X_FRACTIONS` 0.08 and 0.22) and sends the
annotation into the right of the plot where the trails are. The first attempt at this test omitted
it, placed the label harmlessly in the empty top-left, and failed.

### Wiring — `app/.../CloudCoverGraphGlyphObstacleRobolectricTest.kt`

The geometry test builds its own obstacle list, so it **cannot notice a renderer failing to pass
one** — which is precisely the defect that shipped: the glyph pass and the placement search were
each correct in isolation and simply never introduced. This test drives the real
`CloudCoverGraphRenderer.renderGraph` and asserts the placement it produces clears the boxes the
renderer itself fed to the search, via a new `onLayerGlyphsPlaced` debug callback (the fifth in an
established family alongside `onLabelPlaced`, `onDayLabelPlaced`, `onWatermarkPlaced`,
`onDominantStationPlaced`).

Split in two so the halves fail for distinguishable reasons:

- `the renderer hands the glyph trails to the placement search` — publication, and an
  anti-vacuity guard: with no obstacles reaching the search, "clears every glyph" is true of
  anything.
- `the actuals source annotation is placed clear of every glyph` — consumption.

**Verified it can fail.** With `+ layerGlyphBounds` removed from the renderer's `drawnBounds`, the
second test reports `annotation overlaps 4 glyph box(es)` while the first still passes — exactly
the split intended.

### The font-engine problem this exposed

The first cut of `glyphBounds` took a measured width and height (`Paint.measureText` on Android, a
Compose `TextMeasurer` on desktop). Probing Robolectric showed that route is untestable **and**
already broken:

```
glyph paint:  textSize=6.5  measureText("m")=1.0  ascent=0.0  descent=0.0
```

`measureText` is a flat 1px per character and the vertical metrics are zero, so a measured glyph box
has **zero area** — `glyphBounds` returns an empty list and every assertion built on it passes
vacuously. Worse, on real devices the two platforms were asking two different font stacks for the
width of a 6.5dp bold `m` and had no reason to get the same answer, so Android and desktop fenced
off different footprints.

Both problems have one fix: size the box from dp. `GLYPH_BOX_WIDTH_RATIO` (0.9) and
`GLYPH_BOX_HEIGHT_RATIO` (1.2) multiply the glyph's type size — the number both renderers already
derive from `GLYPH_SIZE_DP` for `nudgePx`. Being a pixel out on a 6.5dp glyph costs nothing, and
erring large errs toward keeping text off the trail. Recorded in
[[robolectric-no-font-engine]].

### Wiring — `desktop/.../CloudCoverGraphGlyphObstacleTest.kt`

The same two-part test against the real composable, through a new `onPlacementDebug` hook carrying
`CloudGraphPlacementDebug(layerGlyphBounds, actualsSourcePlacement)`. The hook is emitted *outside*
the annotation's own gate, so a test can tell "searched and found nowhere" from "never got as far as
searching", and it is null in production so the work is never done.

This is the first debug hook on a desktop graph composable — the cloud graph draws to a Canvas and
publishes nothing to the semantics tree, so a placement defect here is otherwise invisible to a
Compose UI test.

Two differences from the Android twin, both in this test's favour:

- **A real font engine.** Desktop Compose measures through Skia, so the annotation is its true
  width and the scene is to scale — where the Robolectric version runs a 1px-per-character stub.
- **Real production geometry.** It renders through `rememberHourlyGraphSetup` and the actual
  window/zoom maths rather than a hand-built hour list.

**Verified it can fail.** Dropping `+ layerGlyphBounds` from the composable reports
`annotation overlaps 9 glyph box(es)` while the publication half stays green.

The scene uses `displaySourceId = "SILURIAN"` — a forecast-only source, so its actuals are borrowed
and the annotation naming the borrowed provider is drawn. No preference is installed, so
`ActualsProviderResolver` falls back to its default and the test leaks no global state into its
neighbours.

Also run green: full `:shared:test`, full `:desktop:test`, and the existing
`CloudCoverGraphLabelPlacementRobolectricTest`.

## Verification

User verified the fix in the running desktop app. My own "after" screenshot was not captured — the
visual evidence on my side is the "before" capture only.

## Commits

- `3e7e8934` *Let the cloud actuals-source label see the layer glyph trails* (on `main`, not
  pushed) — the fix and the geometry test.
- `78676996` *Size layer glyph obstacle boxes from dp, and test the wiring* — the dp-derived box,
  the Android `onLayerGlyphsPlaced` hook and the Robolectric wiring test.
- The desktop `onPlacementDebug` hook and its wiring test are uncommitted in the working tree.

Opus 5
claude --resume 92f07940-d85c-42ed-afba-3f7d743446de

