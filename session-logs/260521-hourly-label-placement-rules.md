# Hourly Graph Label Placement Rules & Fix

## Problem
The 54 temp forecast low label draws above the graph on Pixel 7 Pro/Emulator but below the graph on Samsung devices.

## Rules Identified
1. **Valley Determination:** `FORECAST_LOW` is a "valley" (`isValley = true`).
2. **Preferred Direction:** Extrema valleys default to `preferAbove = false` (below the line).
3. **Collision Fallback:** If "below" causes an off-screen or icon collision, it falls back to "above".
   - On Samsung, there's enough room below, so it stays below.
   - On Pixel/Emulator, it hits a collision and falls back to above.

## Fix Strategy
1. **Force Preference Above:** Modify `TemperatureGraphRenderer.placeSingleLabel` to prefer "above" for forecast low roles.
2. **Enable Curve Dodging:** Add forecast low/high roles to `CURVE_AVOIDANCE_ROLES` so they dodge the dashed forecast line.

## Instructions for Resuming Session

**How to Start the Next Session:**
Run the following command in your terminal to kick off the next session:
```bash
gemini "Resume the hourly graph label placement fix. Please read session-logs/260521-hourly-label-placement-rules.md for full context and instructions."
```

**Agent Instructions:**
To resume this task, the agent should follow these steps:

1. **Contextualize:** Read this log and the corresponding plan in `plans/hourly-label-placement-fix.md`.
2. **Modify Code:** Open `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` and perform the following surgical edits:
   - **Update `CURVE_AVOIDANCE_ROLES` (approx. line 37):** Add `TemperatureRole.FORECAST_LOW`, `TemperatureRole.FORECAST_HIGH`, `TemperatureRole.PAST_FORECAST_LOW`, and `TemperatureRole.PAST_FORECAST_HIGH` to the set.
   - **Update `placeSingleLabel` (approx. line 439):** Modify the `preferAbove` calculation. Specifically, for `FORECAST_LOW`, `PAST_FORECAST_LOW`, and potentially `LOW` / `ACTUAL_LOW`, change the logic to prefer `above = true`. 
     - *Current logic:* `val preferAbove = if (valueBasedRoles) prefersAbovePlacement(candidate) else !placement.isValley`
     - *Target logic:* Explicitly include the forecast low roles in the `preferAbove = true` path.
3. **Verify:** Run the instrumented test suite using `./scripts/emulator-tests.sh`.
4. **Visual Audit:** Capture a screenshot using `adb shell screencap` and inspect the hourly graph to ensure the 54 (or other forecast low) label is tucked neatly above the dashed curve without overlapping it.