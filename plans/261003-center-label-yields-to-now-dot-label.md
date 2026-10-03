# Centre label yields to the NOW-dot temperature label

## Symptom (desktop, 2026-10-03 15:41)

Hourly view, window 05:00–23:00 (widthPx=1035, ≈57.5 px/hour). A pink **86.2°** sat immediately
left of the NOW dot's bold **87.7°**: two actual-series numbers about 57 px apart and 1.5° apart.

## Root cause

`86.2°` is not an extremum. The desktop log shows:

```
LabelAccepted: displayed="86.2" t=14:00 role=CENTER reason=GRAPH_CENTER provenance=OBSERVED idx=124
EngineInput: ... fetchDotX=575.0 ... hardBounds=[(598.5,120.4,699.5,176.4)]   ← the 87.7° NOW label
```

`LabelCandidateCollector.addCenterLabel` puts one label at the window's time midpoint (14:00).
Since `plans/261001-center-label-yields-to-nearby-extremum.md` it skips itself when a high or low is
within `REDUNDANT_PAIR_PX` (64 px), but it doesn't know about the NOW dot's value label. That label is
drawn separately and reaches the engine only as a hard bound, so the 14:00 centre (≈518 px) next to
the 15:00 dot (575 px) gets through.

Whenever NOW is near the middle of the window (the default view in the afternoon), the middle of the
graph is already labelled by the bold current temperature. A second actual-line value one hour
earlier is redundant and reads as clutter.

## Fix (`:shared`, so Android and desktop both get it)

- `collect` / `collectLabelCandidates` take `fetchDotX: Float?`. The engine already has it, and it
  defaults to null so unit-test callers are unchanged.
- In `addCenterLabel`, compute the centre's x the same way the existing gap check does (time
  fraction × widthPx). Skip the centre when it is within `REDUNDANT_PAIR_PX` of `fetchDotX`, and log
  `LabelSuppressed: role=CENTER reason=NEAR_NOW_LABEL`. This is the same budget as the
  extremum rule, applied to the same question.
- No substitute label: as with `NEAR_EXTREMUM`, the middle is already labelled.

## Tests (`:shared`)

- A/B in the centre-label tests: the 05:00–23:00 window at widthPx=1035 with the dot at 15:00 →
  no CENTER. The same window with the dot at 08:00 (≈173 px away) → CENTER kept. Confirm the first
  test fails against the current code.
- Existing `NEAR_EXTREMUM` tests unchanged.

## Verification

Restart the desktop app and confirm the 86.2° label is gone with 87.7° left alone. Check Android's
hourly view the same way with an emulator at a similar NOW-near-centre window.

## Revision: "near" is the middle half of the graph (user's rule, 2026-10-03)

The first build used the 64 px anchor budget. It removed the 15:41 case (57 px), but at 15:50 the
anchors were 91 px apart and "86.2" was still drawn beside "88.1": the label text extends about
45 px past its anchor. A pixel budget has a knife edge as NOW drifts through the afternoon. The user
decided: if NOW is near the centre, don't draw the centre label; near = within 25% of the graph
width (`NOW_NEAR_CENTER_WIDTH_FRACTION = 0.25`).

On the desktop default view (05:00–23:00, 1035 px) that is ±259 px ≈ ±4.5 h around 14:00, so there
is no centre label from 09:30 to 18:30.

Verified on the desktop at 15:55: `LabelSuppressed: role=CENTER reason=NEAR_NOW_LABEL
centerX=517.5 fetchDotX=608.5`, and the 86.2° label is gone.

Tests in `TemperatureCenterLabelTest`:
- the 15:00 case (fails without the rule);
- 15:50, and ±258 px suppressed;
- +260 px kept;
- 08:00 (33%) kept.
