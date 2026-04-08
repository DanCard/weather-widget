# Background & Motivation
The hourly graph currently displays two identical temperature labels when the latest observation (fetch dot) coincides with a significant point on the actuals line (like a peak or the end of the line). This creates visual clutter.

# Scope & Impact
- Updates `TemperatureGraphRenderer.kt` to suppress positional labels that are redundant with the fetch dot's status label.
- Impact: Cleaner hourly graph UI without losing important temperature information.

# Proposed Solution
Implement "Value-Based Suppression" in the `drawTemperatureLabels` function:
1. Identify the fetch dot's position (`ctx.fetchDotX`) and value (`ctx.lastObservedTemp`).
2. Before placing a positional label, check if it's "too close" to the fetch dot in both value and horizontal position.
3. If it is redundant, skip drawing the label.

**Thresholds:**
- **Horizontal:** within 12dp of `fetchDotX`.
- **Value:** identical when formatted (e.g., both are "51.9°").

# Verification
1. Verify that when the fetch dot is at a peak or the end of the line, only one "51.9°" label is drawn.
2. Ensure that if the fetch dot is far from other labeled points, the positional labels still appear correctly.
3. Run Robolectric tests to ensure no regressions in label placement logic.