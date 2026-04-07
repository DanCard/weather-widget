# Fix Test Failures Plan

## Objective
Fix the failing unit test `CloudCoverGraphRendererTest.left edge label is suppressed when nearby lower valley exists`.

## Background & Motivation
The behavior of `GraphLabelPlacementUtils.shouldSuppressLeftEdgeLabel` was updated in commit `01d5e11` to suppress the left edge label if any nearby candidate has a similar value (within 5), rather than checking for a nearby lower valley. The corresponding unit test was not updated to reflect this new logic, causing it to fail.

## Implementation Steps
1. **Update `CloudCoverGraphRendererTest.kt`**:
   - Rename the test from `left edge label is suppressed when nearby lower valley exists` to `left edge label is suppressed when nearby candidate has a similar value`.
   - Update the `labelSignal` test data so that a nearby candidate (e.g., at index 2) has a value within 5 units of the left edge value. For example, change `listOf(25, 18, 10, 22, ...)` to `listOf(25, 18, 23, 22, ...)` where `23` is within `5` of `25`.
   - Ensure the assertion remains `assertTrue(result)`.

## Verification & Testing
- Run `./gradlew test --tests "com.weatherwidget.widget.CloudCoverGraphRendererTest.*"` to verify the updated test passes.
- Ensure all other unit tests continue to pass.