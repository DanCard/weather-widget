# Desktop now-dot stations overlay — session digest

*2026-08-31. Plan: [`plans/260831-desktop-hover-now-dot-stations-popup.md`](../plans/260831-desktop-hover-now-dot-stations-popup.md)*

## What was asked

"When mouse is over the now dot on temperature graph: an overlay with current stations pops up —
how easy or hard?" Then: build it. Then, on seeing it: add the raw reading, and close the gap between
the first two columns.

## The measurement that decided the design

The blend runs inside the Canvas draw lambda and is **not** memoised (`BlendSeriesCache` is wired to
`ActualsAggregator`, not the graph). Hover fires per mouse move, so the question was whether that is
affordable. Project memory carried ~350 ms for a blend, which would have vetoed the feature.

**That figure belongs to a different call.** Measured against the live desktop DB (67,791
observations), timing `ActualTemperatureSeriesBuilder.build` directly:

| Window | Observations | Median |
|---|---|---|
| 18 h back (default zoom) | 706 | **1.6 ms** |
| 72 h back | 2,817 | 4.7 ms |
| 144 h back (max zoom-out) | 9,651 | 13.0 ms |

1.6 ms against a 16 ms frame budget. The remembered constant would have talked us out of a cheap
feature — the kind of number worth re-measuring before letting it decide anything.

## The design rule that keeps it cheap

**The Canvas draw lambda must never read hover state.** The popup takes the `MutableState` itself and
reads `.value` internally, so a mouse move recomposes only the popup. Had the graph's own body read
it, every mouse move would invalidate the graph and re-run the blend.

Two supporting decisions: the blend was hoisted out of the draw lambda into composition scope (so the
overlay can reach the breakdown at all), and the dot's pixel centre is published through a plain
holder rather than Compose state, since writing state during draw that composition reads risks an
invalidation loop.

## What changed

- `ActualTemperatureSeriesBuilder`: `build()` gains `captureBreakdowns`; result gains
  `blendBreakdowns`. Off by default, so no other render path pays.
- **New** `NowDotStationsPopup.kt`: `NowDotTarget`, the pure `nowDotHitTest`, `nowDotStationsTable`,
  `Modifier.nowDotHoverInput`, and the overlay.
- `TemperatureGraph.kt`: blend hoisted, `Box` wrapper, dot target published during draw.

Numbers are never derived in the overlay — rows come from `BlendTableFormatter`, the same pure
formatter behind the Stations window's Blend tab, so the two surfaces cannot disagree about what the
blend did.

## Verification

9 popup tests + 2 builder tests; all three module suites green; mutation probe confirms the tests can
fail; no new compiler warnings. Built and launched the real app. **User confirmed it working.**

## Not done

Nothing committed — the commit gate closed with the previous commit and has not been reopened.
