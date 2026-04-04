# Fix Fetch Dot Alignment and Curve Overshoot

## Background & Motivation
The user observed that the "Current Observation Dot" doesn't visually align with the expected temperature delta (-1.7 degrees below forecast line) and suspected it was due to smoothing. The user clarified they want the dot drawn in its mathematically correct position without disabling the smoothing of the graph lines themselves.

## Proposed Solution
**Yes, that makes perfect sense.** The dot should be placed exactly where the raw math dictates (1.7 degrees below the forecast), and we will keep the smoothing for the last segment of the lines.

**The Root Cause:**
The reason the dot currently doesn't *look* like it's 1.7 degrees away from the forecast line is actually because the smoothing math (the cubic spline) is malfunctioning.
1. The graphing system uses a mathematical curve (a cubic spline) to draw smooth lines instead of jagged straight lines. To do this, it computes an "angle" (tangent) for how the line should pass through each point.
2. This math assumes all data points are evenly spaced (e.g., exactly 1 hour apart). However, the current observation (the fetch dot) is injected at a very precise, uneven minute (like 10:37). 
3. Because 10:37 is very close to 11:00, the tangent math gets confused by the narrow time gap and calculates an angle that is way too steep. This causes the smoothed line to "swoop", bulge, or even loop backwards right before the dot.
4. The dot itself *is* actually being drawn in the correct raw position, but the line swooping wildly away from it creates the optical illusion that the dot is misaligned.

**The Fix:**
We will update the smoothing math (`computeTangents` in `GraphRenderUtils.kt`) so that it understands uneven time intervals. By clamping the steepness of the angle when points are close together, we stop the wild swoops while keeping the lines beautifully smooth. The dot will remain exactly where it belongs, and the line will flow into it naturally, perfectly matching the 1.7 text delta.

## Implementation Plan

### 1. Fix Tangent Computation in `GraphRenderUtils.kt`
- Modify `computeTangents` to properly handle non-uniform X spacing.
- We will clamp the computed X tangents (`dx`) so they cannot exceed the actual distance between the points (`dxPrev` and `dxNext`).
- We will scale the Y tangents (`dy`) proportionally to ensure the curve remains smooth but mathematically cannot overshoot or loop backwards.

### 2. Add Test for Fetch Dot Alignment
- Create or update a test in `TemperatureGraphRendererFetchDotTest.kt` (or similar).
- The test will explicitly inject a sub-hourly observation (e.g., at 10:37) and verify that the resulting `FetchDotDebug`'s Y-coordinate exactly matches the mathematical linear representation of the actual observation, and is not pulled away by the spline's smoothing algorithm.

## Verification & Testing
- Run Robolectric tests to ensure graph generation logic is unbroken.
- The visual delta on the emulator should exactly match the numeric delta text, with a smooth curve that doesn't spike or dip unnaturally before the dot.