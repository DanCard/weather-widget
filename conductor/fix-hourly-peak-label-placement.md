# Fix Hourly Graph Peak Label Placement

## Objective
Fix the placement of secondary/local extrema peak labels (like the 81° label) which are currently being drawn artificially high with long leader lines when there is plenty of open space directly below the curve.

## Background & Motivation
In the current implementation of `TemperatureGraphRenderer.kt`, the label placement algorithm tries to avoid collisions by increasing its vertical distance (`step`) from the graph point. 
Currently, the algorithm iterates through `MAX_LEADER_DISPLACEMENT_STEPS` in the primary direction (e.g., above the peak) *before* it tries the secondary direction (e.g., below the peak). As a result, if the space directly above the peak is occupied, the algorithm will keep pushing the label higher and higher into open sky (producing an awkwardly long leader line) rather than simply drawing the label directly below the peak where there is ample open space.

## Proposed Solution
We need to swap the loop order in `TemperatureGraphRenderer.kt`. Instead of fully exhausting the primary direction before trying the secondary direction, the algorithm should increment the `step` displacement only after trying *both* directions at the current step. 
This ensures we always find the absolute closest available non-colliding spot to the target point, whether above or below the line.

## Implementation Plan
1.  **Modify `TemperatureGraphRenderer.kt`**:
    -   Locate the nested loops controlling label displacement:
        ```kotlin
        outer@ for ((_, drawBelow) in directions.withIndex()) {
            for (step in 0..MAX_LEADER_DISPLACEMENT_STEPS) {
        ```
    -   Swap the order of the loops so that distance (`step`) is the outer loop:
        ```kotlin
        outer@ for (step in 0..MAX_LEADER_DISPLACEMENT_STEPS) {
            for ((_, drawBelow) in directions.withIndex()) {
        ```
    -   Verify that any `break@outer` or similar control flows still function correctly.
    -   Update the fallback forced placement logic (`isEssential`) to ensure `forceBaselineY`, `forceBounds`, `forceDrawBelow`, and `forceStep` correctly track the closest available fallback (or simply the last checked position). Since we're evaluating both sides per step, `forceBounds` will naturally prefer the highest step secondary direction as a fallback if everything fails, which is fine since failure implies the screen is full anyway.

## Verification
-   Run the unit tests and any instrumentation tests for the hourly graph rendering.
-   Run the app on the emulator and verify that the 81° label is placed beneath the curve with no leader line, rather than pushed high above the curve.
-   Verify that essential peak labels still draw normally above the line when space is available.