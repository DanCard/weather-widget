# Center label yields to a nearby extremum — summary

Plan: `plans/261001-center-label-yields-to-nearby-extremum.md`

## Change

- `LabelCandidateCollector.addCenterLabel` now skips the graph-center label when a HIGH/LOW-family
  extremum label is drawn within `REDUNDANT_PAIR_PX` (64 px, via `pixelGapByTime`) of the midpoint.
  Logs `LabelSuppressed: role=CENTER idx=… reason=NEAR_EXTREMUM extremum=<role>@<idx>`.
- Geometry-less callers (`widthPx <= 0`) are unaffected: the gap is `Float.MAX_VALUE`.
- Shared code, so Android and desktop both pick it up.

## Verification

- `TemperatureCenterLabelTest`: new skip case (the 2026-10-01 geometry) and a far-peak control. With
  the guard disabled, only the skip case fails.
- `:shared` graph tests, `:app` label/graph Robolectric tests, `:desktop:compileKotlin`: pass.
- Pixel 7 Pro widget 88: logcat shows `reason=NEAR_EXTREMUM extremum=HIGH@113` and
  `PlaceAccept: role=HIGH idx=113 above=true`. The screenshot shows 83° above the peak with no 82°.
- Desktop rebuilt and restarted via `scripts/buildStart-desktop.sh`.
