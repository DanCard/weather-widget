# Forecast high lifts over the fetch-dot label; quota pill centred

## Problem (Fold 4, hourly view, Google, 2026-10-07 ~14:35)

The forecast high **82.3°** (Google hourly, 15:00) was drawn *below* its peak with a long leader.

- The observed high 81.91° @ 14:20 is the fetch dot; its side label "81.9°" is a reserved hard bound
  at `(269.9,44.1,327.9,74.3)` — directly over the space above the 15:00 peak.
- `PlaceReject role=HIGH step=0 above=true hard=true/7.3` → above blocked by 7.3 px.
- Steps 1–3 move a whole label height (30.2 px); above+1 put the top at −9 px and the on-screen gate
  skipped it. Below+0 hit the curve, so it settled at **below+1 with a leader**.
- Once allowed above+1 (off the top), the leader was still long: it spanned the gap plus 30 px to
  clear a 7 px overlap — and most of those 7 px were the label's empty font-box descent band.

## Changes

1. **High labels may run off the top** (`TemperatureLabelEngine`): a `FORECAST_HIGH_ROLES` label
   placed above passes the on-screen gate while part of it stays inside the graph.
2. **Minimal lift instead of a whole step**: at step 0, a high blocked above *only* by a hard bound
   (no label/icon/curve collision) is lifted by the overlap measured from its ink
   (`hardOverlapPx − labelDescent`, temperature text has no descenders) plus 1 dp. The lifted slot is
   re-tested against obstacles and curves using the ink box; the full box is recorded for later
   labels. Leader drawn only when the lift is at least the descent band, ending at the baseline.
3. **Quota/failure pill centred vertically** (`GraphFailureWatermarkRenderer`): replaced the fixed
   `PILL_TOP_DP = 40` with `(height − pillHeight) / 2`; `error_pill_touch_zone` uses
   `layout_gravity="center_vertical"` and drops `marginTop` (`graph_view` is `match_parent`, so
   canvas centre = widget centre). Desktop shows failures as a text banner, not an in-graph pill —
   nothing to mirror.

## Tests

- `TemperatureGraphLabelPlacementRobolectricTest`: "peak falls back below when above placement would
  leave the screen" rewritten as "peak stays above and may run off the top of the graph" (asserts
  placed, above, baseline > 0 — the old test passed vacuously when no label was placed).
- Full `scripts/staggered-tests.sh`.

## Verification

On the Fold: `PlaceAccept: role=HIGH lifted=18.4 above=true leader=true (cleared hard bound by ink)`;
82.3° sits above the peak with a short tick; pill in the middle of the graph.

## Follow-up

- A focused unit test for "two highs near the top → lift, not flip".
