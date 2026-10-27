# One top limit for every hourly-graph label; header buttons as obstacles

User, 2026-10-27: the desktop hourly graph labels the ghost-line peak (67.4° at 3 PM); the Fold 4
does not. "Should there be an aboveLimitY for everything. I would hate to have to give an exception
to a dozen things." → "do it".

## Evidence

- Fold 4 `TempGraphRenderer` trace: `candidates=[… 426@67.4°* …] -> placed=1: [312=65.4°]`;
  `Scaling: rawMax=65.0 maxTemp=68.0`; forecast HIGH `65°` placed above at y=59 under the peak.
- The 3 PM box could not go above (box top < `graphTop + pad`) and below was the HIGH label.
- Experiment (plot top → bitmap edge for the ghost label only) placed `426=67.4°`; screenshot showed
  it drawn beside the RemoteViews **NWS** label, degree sign nearly touching — the bitmap's top
  padding sits under the header row.

## Root cause

Top limits are per placer:

| Placers | Top limit |
|---|---|
| `ValueLabelEngine`, `CurveFitPlacer`, `ActualExtremePlacers`, `ValleyCascade` | bitmap edge `0f` |
| `GhostLineLabel`, `GraphEmptySpaceFinder` (delta, dominant station, actuals source) | `plot.top` = `graphTop` (Android 16dp; desktop 2px) |

Nothing knows the Android header overlays the top strip.

## Change

1. `:shared` `HeaderFit.occupiedSpans(m, plan)`: horizontal spans of everything the header draws
   (left cluster, CENTER buttons, date, right cluster) from the same arithmetic as `buttonsSpan` /
   `dateLeft`.
2. `:shared` `GraphLabelArea`: the one rect every label may use — canvas top to the footer — plus
   `headerObstacles(...)` mapping header spans into graph coordinates. Both platforms build their
   free-label `plot` from it; desktop's header sits above the canvas, so it has no obstacles.
3. Android: resolve the hourly header fit once (`TemperatureHeaderFit`) for both the resolver (before
   rendering) and the binder; pass the header obstacle rects into `renderGraph`; register them as
   `HEADER` obstacles before any label is placed and hand them to `ValueLabelEngine` as hard bounds.
4. Remove the one-line ghost experiment.

## Tests

- `HeaderFitTest`: occupied spans for CENTER / INLINE / no-button plans.
- `GraphLabelAreaTest`: header span → graph rect mapping (offset, scale, band).
- `GhostLineLabelTest`: the Fold case — peak at plot top, HIGH label below → placed above into the
  label area; with a header obstacle over that x → not placed. Fails on the old `plot.top` rule.
- Device: Fold 4 trace shows `426=67.4°`, screenshot clear of NWS/gear; temporary obstacle outline
  checked once then removed.
