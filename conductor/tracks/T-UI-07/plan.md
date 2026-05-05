# T-UI-07: Fix Rain Chance Label Positioning

The 1% labels on the rain chance graph were floating too high off the graph line on some devices. This was due to an intentional 8dp gap inflation for low-probability labels to avoid weather icons.

## Strategy
- Remove the artificial gap inflation (8dp -> 2dp) for low-probability labels.
- Clean up unused constants and logic related to "icon lift".

## Implementation
- Modified \`PrecipitationGraphRenderer.kt\`:
    - Removed \`LOW_LABEL_ICON_CLEARANCE_DP\` and \`LOW_LABEL_ICON_LIFT_TRIGGER_DP\`.
    - Simplified \`gapPx\` calculation to always use standard gaps.
    - Set \`iconClearancePx\` to 0f.

## Verification
- Ran \`PrecipitationGraphRendererTest\` unit tests.
- Verified that \`renderGraph low right edge label avoids weather icon by falling back above\` still passes with the reduced gap.
