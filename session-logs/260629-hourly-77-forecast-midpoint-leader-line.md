# 2026-06-29 — Hourly Temperature Graph: 77° Forecast Midpoint Label with Unexpected Leader Line

## Summary

User reported seeing a temperature label of "77" with a leader line in the middle of the hourly forecast (dashed) curve on the emulator. The label was a synthesized FORECAST_MIDPOINT (TemperatureRole.LOCAL) added for a short bare forecast tail in the hourly view. Investigation using runtime evidence (screenshot + filtered logcat) showed it was being displaced (step=1, leader=true) primarily due to a tiny forecast curve intrusion (~0.4px graze after margin) in the natural placement position, not a significant collision with the NOW/fetchDot. 

User provided clarifying feedback that the placed 77 label did not collide with NOW/fetchDot. We added targeted logging, diagnosed the strict curve avoidance logic in TemperatureLabelEngine, then softened the collision check specifically for LOCAL roles with a 3dp graze tolerance. After user ran `installDebug` and verified, they confirmed "Looks good" and requested to keep the change.

This session followed the project's evidence-first debug protocol and session-log conventions.

## User Prompts (in chronological order, verbatim)

1. "Suggestions for working with this repo?"

2. "Emulator: hourly temperature graph: forecast line, in the middle there is a temperature label of 77 with a leader line.  Why the leader line?  Add logging if this isn't easy to diagnose."

3. "I ran ./gradlew installDebug"

4. "77 label doesn't collide with NOW/fetchDot"

5. "Are you saying it collides with the forecast curve, that is why there is a leader line?  Can we soften that collision check to let me see what it looks like?  I doubt the 77 label really collides with the forecast curve."

6. "Looks good, I'd like to keep the change."

7. "write a session log to session-logs/ dir, include all prompts"

## Session Timeline and Actions (numbered for clarity)

1. Reviewed project guidelines from CLAUDE.md, AGENTS.md (evidence-first debugging, logging rules for VERBOSE vs DEBUG, prefer Robolectric, screenshot conversion rules, etc.).

2. Explored codebase:
   - Located hourly graph rendering in `TemperatureGraphRenderer.kt` (app) and shared logic in `TemperatureLabelEngine.kt`, `TemperatureLabelResolver.kt` (shared module).
   - Found leader line logic: `drawLeaderLine = step > 0` in the displacement loop; `FORECAST_MIDPOINT` added in `addForecastMidpointLabel`.
   - Key files: `GraphLabelPlacementUtils.kt`, `TemperatureGraphStyle.kt`, `TemperatureGraphModels.kt`.

3. Followed "look at emulator" protocol:
   - Confirmed devices: `adb devices` (emulator-5554 active).
   - Captured screenshot: `adb -s emulator-5554 exec-out screencap -p > /tmp/widget_hourly.png && convert ... /tmp/widget_hourly.jpg`.
   - Read the JPG via tools to observe the 77 label with vertical leader near the NOW indicator on the dashed forecast line (around 1p in a short hourly window).
   - Captured logs: `adb logcat -d -s TempGraphResolver:V TempLabelEngine:D TempGraphRenderer:D | grep ...` (and broader).

4. Analyzed runtime evidence from logs (exact render at ~12:50):
   - `LabelAccepted: displayed="77" ... role=LOCAL reason=FORECAST_MIDPOINT ... idx=23 futureStart=22 lastIndex=25`
   - `EngineInput` showed hardBounds around fetchDot/transition (x~243).
   - `PlaceReject` for step=0: hard and/or curve blockers.
   - `PlaceAccept` step=1 above, leader=true, displacementSteps=1.
   - Precheck showed tiny curve intrusion (minY=130.4 vs box bottom~130.8).

5. Diagnosed root cause:
   - The 77 is a midpoint label for a short forecast region (span=3, meets MIN_FORECAST_MIDPOINT_SPAN).
   - Natural position on sloped forecast curve (not an extremum) produced small curve intrusion due to `CURVE_AVOIDANCE_MARGIN_PX`, label width, and 1dp preferred gap (TEMP_PREFERRED_ABOVE_GAP_DP effectively tightened).
   - `curveWithinDip` was only for ACTUAL_LOW; LOCAL had zero tolerance → full step displacement.
   - Hard bounds (fetch dot value/age/ring) only blocked "below"; "above" was curve-driven.
   - User clarification: the final placed label does not visually collide with NOW/fetchDot (the hard collision was directional/algorithmic, not the root for the accepted path).
   - The "leader line" the user saw was the `forecastLeaderLinePaint` vertical connector.

6. Added logging (as requested):
   - In resolver: `ForecastMidpointCloseToTransition` when mid is <=2 steps after futureStart.
   - In engine: `ForecastMidpointLeader` on drawLeader for LOCAL, including distToTransition/distToFetch.
   - Enhanced existing PlaceReject to include `depth=`.

7. User ran `./gradlew installDebug`.

8. User feedback: "77 label doesn't collide with NOW/fetchDot".

9. User query for clarification and experiment: "Are you saying it collides with the forecast curve... Can we soften that collision check to let me see what it looks like? I doubt the 77 label really collides with the forecast curve."

10. Implemented softening (for experimentation / to observe natural placement):
    - Introduced `LOCAL_CURVE_GRAZE_DP = 3f`.
    - Updated `allowedDipPxFor(...)` to return the graze value for LOCAL.
    - Extended `curveWithinDip` condition to include LOCAL.
    - Updated `checkExactFitBlockers` to return `NaturalFits` for tolerant roles when dip <= allowed (preventing unnecessary GAVE_UP / curveFit paths).
    - Extended `CurveOnly` handler to return GAVE_UP (allowing main step-0 path) for LOCAL within tolerance.
    - Improved precheck logging to include `curveDip=... tolerant=...`.
    - Polished comments to document as policy (not just experiment).
    - Minor log tag cleanups in adjustment paths.
    - Verified compiles and relevant tests (`*TemperatureGraphLabel*`, `*LabelPlacement*`).

11. User: "Looks good, I'd like to keep the change."

12. Finalized session log (this file) per request, including all prompts. Tests continued to pass after polish.

## Key Technical Details

- **Why curve intrusion on "its own" point?** Midpoint is not a peak/valley. Slope within the label's x-span (plus margins) causes the sampled `forecastPoints` to poke into the candidate box even at the natural gap. `overlapsCurve` treated any non-empty `CurveIntrusion` as blocker for LOCAL.

- **Files changed:**
  - `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelEngine.kt` (primary: tolerance logic, allowedDip, precheck, CurveOnly, logging, comments).

- **Evidence captured:**
  - Multiple screenshots (`/tmp/widget_hourly.jpg`, follow-ups).
  - Detailed logcat excerpts showing exact decisions, bounds, intrusion values, x distances (~48.7px from transition for idx=23).

- **Logging discipline followed:** New logs at appropriate levels (V for resolver decisions, D for engine placement). No VERBOSE spam.

## Outcome and Current State

- The 77 label now places at natural step-0 (no leader) when the only issue is a small curve graze within the 3dp tolerance.
- Tolerance is scoped narrowly to LOCAL (midpoints/interior forecast labels) to avoid affecting extrema or actuals.
- Diagnostic output remains for future similar issues (e.g., "curveDip=0.40 tolerant=true").
- User accepted and requested to keep.
- No other files (e.g., no plan written unless separately requested; this is pure session log).

## Notes / Conventions Observed

- Followed "Evidence-First Debug Protocol" from AGENTS.md (screenshot + renderer logcat before theorizing; quoted actual emitted lines).
- Included all user prompts (as requested).
- Used numbered lists.
- No `adb shell pm clear`.
- Changes kept minimal and focused on the reported symptom.
- If future data requires adjusting tolerance, change only `LOCAL_CURVE_GRAZE_DP`.

This log captures the full interactive session including the initial repo suggestions context that framed the work.