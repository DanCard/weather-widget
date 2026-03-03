# Disable Graph View for 2-Row Widgets

## Problem
On 2-row widgets (~136dp height), the graph view is too cramped. Packing the header (current temp), icons (at the top), the temperature graph/path, and bottom labels into this small vertical space results in a cluttered UI where elements overlap or are too small to read.

## Goal
Switch 2-row widgets to use the existing **Text Mode** (column-based layout), which is much more legible at this height. The graph view will now require at least **3 rows** to be displayed.

## Implementation Plan

### 1. `WeatherWidgetProvider.kt`
*   Locate the logic that determines whether to use the graph or text mode.
*   **Modify `updateWidgetWithData` (Daily View):**
    *   Change `val useGraph = numRows >= 2` to `val useGraph = numRows >= 3`.
*   **Modify `updateWidgetWithHourlyData` (Hourly View):**
    *   Change `val useGraph = numRows >= 2` to `val useGraph = numRows >= 3`.

### 2. Layout & Visibility
*   The `Text Mode` (layout `text_container`) already handles various column counts and shows Day/Icon/Temps or Time/Temp.
*   By increasing the threshold to 3 rows, 2-row widgets will automatically fall back to this cleaner layout.

## Verification
*   Verify code compiles.
*   User will verify that 2-row widgets now show the text/column layout and 3+ row widgets still show the graph.
