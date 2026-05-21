# Objective
Make the hourly graph forecast low label render above the graph consistently across all devices.

# Background & Motivation
Currently, on Pixel 7 Pro and emulators, the forecast low label (e.g., 54 temp) draws above the graph, while on a Samsung device, it draws below.
This discrepancy occurs because:
1. **Valley Determination:** The renderer classifies labels by their "role". Because the 54 temp is a `FORECAST_LOW`, it is explicitly classified as a "valley" (`isValley = true`).
2. **Preferred Direction:** For extrema (highs/lows), the default preferred direction is determined by whether the point is a valley (`preferAbove = !isValley`). Since it *is* a valley, the renderer's default preference is to draw the label **below** the graph line.
3. **Collision & Fallback:** The renderer attempts to place the label in the preferred direction (below). However, if placing it below causes the label to go off the bottom of the widget or collide with the hourly weather icons, the placement is rejected, and it falls back to placing the label **above** the graph.

On Samsung devices, there is enough vertical space to succeed in placing it below. On Pixel 7 Pro, the tighter geometry causes a collision below, triggering the fallback to place it above.

# Proposed Solution
1. **Change the Default Preference:** Update the logic in `TemperatureGraphRenderer.kt` so that valley placements (specifically forecast lows, and potentially other low roles) prefer to be drawn above the graph rather than below it.
2. **Enable Curve Dodging:** Because placing the label above pushes it inside the valley, we need to add `TemperatureRole.FORECAST_LOW` (and other related forecast roles) to the `CURVE_AVOIDANCE_ROLES` list. This ensures the label actively dodges the dashed forecast curve so it doesn't overlap the line.

# Implementation Steps
1. Write the explanation note to `session-logs/260521-hourly-label-placement-rules.md` as requested. Ensure this log includes explicit instructions for the AI on how to resume this session (i.e., read this log, review the proposed changes, and implement the plan).
2. Update `CURVE_AVOIDANCE_ROLES` in `TemperatureGraphRenderer.kt` to include `FORECAST_LOW`, `FORECAST_HIGH`, `PAST_FORECAST_LOW`, and `PAST_FORECAST_HIGH`.
3. Modify the `preferAbove` logic in `TemperatureGraphRenderer.placeSingleLabel` to ensure that valley labels default to `true` according to the new preference.
4. Run the automated emulator tests (`./scripts/emulator-tests.sh`) to verify that no overlapping regressions occur.

# Verification
- Run `./scripts/emulator-tests.sh` to ensure test stability.
- Visually confirm label placement on the emulator.