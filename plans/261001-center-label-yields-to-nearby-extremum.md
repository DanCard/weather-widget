# Center label yields to a nearby extremum

## Evidence

Pixel 7 Pro, widget 88, NWS hourly WIDE view (7a → 1a), 2026-10-01 15:50. An "82" was drawn above
an "83" at the day's peak. Logcat:

```
LabelAccepted: displayed="83" t=15:00 role=HIGH reason=EXTREMA val=83.0 idx=113
LabelAccepted: displayed="82" t=16:00 role=CENTER reason=GRAPH_CENTER val=82.0 idx=123
PlaceAccept:   role=CENTER idx=123 above=true
ExactFitPreCheck: role=HIGH idx=113 placeAbove=true labelBlocker=true
ExactFitOutcome:  role=HIGH idx=113 placeAbove=true outcome=LABEL_OR_ICON_BLOCKED
```

The 16:00 temporal midpoint fell one hour (~29 px) from the forecast high. `sortCandidates` places
`isCenter` first (by design, `plans/260908-hourly-center-temperature-label.md` step 3), so the center
label took the slot above the peak and the HIGH flipped below the curve.

## Root cause

`addCenterLabel` exists to put a value in an otherwise unlabelled middle of the graph. It does not
check whether an extremum label already sits there, so it duplicates nearby information and, with
first placement priority, displaces the more important label.

## Change

1. In `LabelCandidateCollector.addCenterLabel`, skip the center label when an extremum candidate
   (forecast/actual/past-forecast HIGH or LOW) is drawn within `REDUNDANT_PAIR_PX` (64 px) of the
   midpoint, measured with `LabelGeometryResolver.pixelGapByTime` (where labels are DRAWN, including
   run-centering). Log `LabelSuppressed: role=CENTER reason=NEAR_EXTREMUM`.
2. Center priority is unchanged when it survives: it only survives when no extremum is close.
3. Shared code, so Android and desktop both get it.

## Tests

- `TemperatureCenterLabelTest`: a peak one hour from the midpoint suppresses the center label; a peak
  well away from the midpoint keeps it (proves the test can fail both ways).
- Run the shared graph tests and the Android label-placement Robolectric test.
- Install on the Pixel 7 Pro, screenshot, and confirm the "82" is gone and the 83 sits above the peak.
- Rebuild/restart the desktop app.
