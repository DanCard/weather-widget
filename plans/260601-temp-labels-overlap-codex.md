# Plan: Keep warmer low labels above after collision flip

## Context

Samsung runtime logs show the low-label ordering rule is now partially working:

```text
LabelAccepted: role=LOW idx=24 val=54.0
LabelAccepted: role=ACTUAL_LOW idx=47 val=53.210762
LabelPlacementDebug: role=ACTUAL_LOW ... placedAbove=false reason=below
LabelCascade: role=LOW flip-above-warmer current=54 colliding=53
LabelRejected: role=LOW idx=24 above=true ... reason=curve-collision
LabelPlacementDebug: role=LOW ... placedAbove=false reason=below+1
```

The renderer correctly detects that the warmer `54` low should flip above the colder
`53.2` low, but the above attempt is rejected by the main loop's hard curve-collision
gate. Because valley directions still prefer below first, the next displacement step
places `54` below the graph line again.

## Implementation

1. In `TemperatureGraphRenderer.placeSingleLabel`, once `ValleyCascadeOutcome.FlipAbove`
   is returned, keep that candidate on the above-placement path:
   - Skip below retries while `flipDecided` is true.
   - Keep the existing forced fallback for essential labels if no above placement can fit.

2. Relax curve collision only for this explicit flipped-warmer-low path:
   - Above-side placement may accept a shallow curve graze using the existing
     `CURVE_AVOIDANCE_ALLOWED_DIP_DP` tolerance.
   - Label and icon collisions remain strict.
   - Normal low labels, high labels, local labels, endpoints, and non-flipped valleys keep
     the current curve-collision behavior.

3. Keep the existing debug logs:
   - `LabelCascade: ... flip-above-warmer ...` remains the signal that the value-order
     flip was selected.
   - The final `LabelPlacementDebug` should report `placedAbove=true` for the warmer low.

## Verification

Run the focused label-placement tests:

```bash
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureLabelCollisionOrderTest" --tests "com.weatherwidget.widget.TemperatureValleyBelowCascadeTest" --tests "com.weatherwidget.widget.TemperatureGraphLabelPlacementRobolectricTest"
```

Expected behavior:

- The Samsung-style regression places `54` above and keeps `53.2` below.
- Existing valley cascade tests that resolve by horizontal shift or small relaxed overlap
  keep their previous compact below-label behavior.
- Single low labels near icons still remain below when no warmer/colder low collision
  requires a flip.
