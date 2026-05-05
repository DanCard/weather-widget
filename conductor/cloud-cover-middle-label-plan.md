# Add Middle Cloud Cover Label on Wide Widgets

## Objective
On wide widgets (5 or more columns), if the hourly cloud cover graph only displays the beginning and end percentage labels, inject a third label in the middle of the graph to improve readability.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`: Responsible for computing which cloud cover points should receive labels.
- `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`: Invokes the renderer and has access to the widget's column count.

## Implementation Steps

1. **Update `CloudCoverGraphRenderer.renderGraph` Signature:**
   - Add a new parameter `numColumns: Int = 0` to the `renderGraph` function signature. Using a default value ensures we do not break existing test calls.

2. **Inject Middle Candidate Logic:**
   - Inside `CloudCoverGraphRenderer.renderGraph`, locate the line where `suppressLeftEdgeLabel` is computed (around line 430).
   - Below this, introduce a `finalCandidates` list that injects a midpoint if the conditions are met:
     ```kotlin
     val finalCandidates = if (numColumns >= 5 && filteredCandidates.size == 2 && filteredCandidates.containsAll(listOf(0, hours.lastIndex))) {
         val midIndex = hours.lastIndex / 2
         if (midIndex != 0 && midIndex != hours.lastIndex) {
             (filteredCandidates + midIndex).sorted()
         } else {
             filteredCandidates
         }
     } else {
         filteredCandidates
     }
     ```
   - Update the loop `for (index in filteredCandidates) {` to iterate over `finalCandidates` instead.

3. **Pass `numColumns` from the Handler:**
   - In `CloudCoverViewHandler.kt`, locate the call to `CloudCoverGraphRenderer.renderGraph` (around line 311).
   - Add `numColumns = numColumns,` to the arguments passed to the renderer.

## Verification & Testing
- Use `CloudCoverTouchRoutingInstrumentedTest.kt` or `CloudCoverGraphRendererTest.kt` to ensure rendering does not crash.
- Set up the widget on an emulator to span 5 columns. Provide flat/monotone cloud cover data (e.g. 100% all day) and verify that 3 labels appear (beginning, middle, end).
- Resize the widget to 4 columns and verify that only 2 labels appear (beginning, end) for the same data.
