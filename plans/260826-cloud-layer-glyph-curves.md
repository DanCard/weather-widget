# Draw Open-Meteo mid and high cloud layers as h/m glyph curves

Follows `260826-store-open-meteo-cloud-layers.md`, which stored all four bands and produced the
evidence below.

## Goal

Plot the mid and high cloud layers as curves whose line is made of tiny repeated glyphs — `m` for
mid, `h` for high — spaced like a dash pattern, drawing nothing where a layer is below 5%.

## Why this shape

1. The low-only curve has a measured blind spot: 58 of 364 stored hours (16%) pair `low < 20` with
   `max(mid,high) >= 70`, including a continuous ~24h stretch on 2026-08-27 where low reads 0-4
   while mid and high sit at 100. The graph currently paints that whole day as a clear sky.
2. `cloudCover` is a union, not a sum (at-or-above `max` in 149 of 152 cloudy rows, at-or-below the
   clamped sum in 127), so a stacked area would be wrong at both ends.
3. Glyphs self-label. A separate ribbon or lane encodes layer identity by position, which has to be
   learned; `h` and `m` say what they are at every point on the curve.
4. 73% of hours have both mid and high below 20, so the 5% floor keeps the graph empty on most days.

## Proposed implementation

1. **Shared** `CloudLayerGlyphPlacer` — pure placement math, no platform types: walk a layer's hour
   vertices, emit one glyph every `stepPx` of arc length, linearly interpolating coverage between
   vertices and suppressing any glyph under `MIN_COVER`. Constants (glyph size, step, floor,
   coincidence delta, phase) live beside the palette so both renderers read one source.
2. Give `h` a half-step **phase offset** so the two layers never land on the same x, and nudge the
   pair apart vertically where their values are within `COINCIDENT_DELTA` — without it, two layers
   both pinned at 100% overprint each other (observed in the prototype).
3. **Android**: extend `CloudCoverGraphRenderer.CloudHourData` with `midCover`/`highCover`, populate
   them in `CloudCoverViewHandler` from the hourly entity, add a glyph paint to
   `CloudCoverGraphStyle`, and draw the glyphs beneath the low forecast curve.
4. **Desktop**: draw the same glyphs in `CloudCoverGraph` from the same shared placer and the same
   `points` rows.
5. Mid/high are **forecast-only**. They have no frozen day-ago counterpart and no actual, so they
   make no accuracy claim and must not be styled like the pink actual curve; they stay in the
   forecast grey.

## Verification

1. Shared unit tests for the placer: spacing along a known polyline, the 5% floor suppressing
   glyphs, null coverage leaving gaps, phase offset separating the two layers, and the coincidence
   nudge firing only when values are close.
2. Android unit/Robolectric coverage that `CloudHourData` carries the layers through the handler.
3. Build, install on the emulator, and screenshot the cloud view on a day with mid/high cloud.
4. Desktop build plus a visual check against the same window.
