# Fix Daily Forecast View for 1-Row Height

## Objective
Ensure the Daily Forecast view defaults to Text Mode (list format) when the widget is one icon high, as there is insufficient vertical space to render the temperature bar in Graph Mode.

## Background & Motivation
Currently, on some launchers (like Pixel), a 1-row widget returns a slightly taller `heightDp` (e.g., ~110dp). The `DailyViewHandler` calculates `rawRows = (heightDp + 25) / 90`, which yields `~1.5`. Since the `GRAPH_ROW_THRESHOLD` is `1.4f`, this causes the widget to incorrectly assume it has 2+ rows and render the Graph Mode. However, in Graph Mode, the available height is too small (`graphHeight` becomes negative or nearly zero), causing the temperature labels and weather icon to completely overlap. 

## Scope & Impact
We will increase the `GRAPH_ROW_THRESHOLD` in `DailyViewHandler.kt` to prevent 1-row widgets from rendering the graph view.

## Proposed Solution
Increase `GRAPH_ROW_THRESHOLD` in `DailyViewHandler.kt` from `1.4f` to `1.8f`.
This means `heightDp` must be at least `137dp` to trigger Graph Mode. 1-row widgets are typically under `120dp`, while 2-row widgets are typically over `200dp`. This adjustment effectively separates the two.

## Implementation Steps
1. Open `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`.
2. Update the `GRAPH_ROW_THRESHOLD` constant:
   - Old: `private const val GRAPH_ROW_THRESHOLD = 1.4f`
   - New: `private const val GRAPH_ROW_THRESHOLD = 1.8f`

## Verification & Testing
- Deploy the widget to an emulator.
- Resize the widget to 1 row tall (e.g., 4x1). Ensure it switches to Text Mode.
- Resize the widget to 2 rows tall (e.g., 4x2). Ensure it switches to Graph Mode.
- Verify tests (if any) relying on `GRAPH_ROW_THRESHOLD` are updated.