# Optimize 1-Row Daily Widget View

## Objective
Optimize the layout of the 1-row (Text Mode) Daily Widget to prevent overlapping by hiding the top-left current temperature/weather icon and reducing the number of visible columns by 1 to make room for the top-right settings and API icons. Additionally, prevent the widget from being resized to a 1x1 grid size.

## Background & Motivation
In the 1-row text mode, the widget displays the days of the week in columns that span the entire width. The "current weather" (icon, current temp, precipitation probability) overlays the top-left, and the "API/Settings" icons overlay the top-right. Because vertical space is so constrained (1 row tall), these overlays end up colliding with the text columns, making the widget look cluttered. Furthermore, a 1x1 widget is too small to display any meaningful data.

## Scope & Impact
1.  **Hide Left Header in 1-Row Mode:** Hide the `current_weather_container` (containing the current temp, delta, precip, and header icon) when the widget is rendering in Text Mode (`!useGraph`).
2.  **Reduce Text Mode Columns:** Subtract 1 from `numColumns` when rendering Text Mode to leave empty space on the left and right edges, preventing overlap with the API and settings icons.
3.  **Disable 1x1 Resizing:** Update `weather_widget_info.xml` to set `minWidth` and `minResizeWidth` to `110dp`, ensuring the widget takes up at least 2 horizontal grid cells.

## Proposed Solution
1.  **AndroidManifest / Widget Info:** Update `app/src/main/res/xml/weather_widget_info.xml` and change `minWidth="40dp"` to `110dp` and `minResizeWidth="40dp"` to `110dp`.
2.  **DailyViewHandler.kt:**
    *   Move the `useGraph` calculation to the top of the function.
    *   When deciding header visibility, if `!useGraph`, force the `current_weather_container` (or its child elements) to `View.GONE`.
    *   When passing `numColumns` to `updateTextMode`, pass `(numColumns - 1).coerceAtLeast(1)`.

## Implementation Steps
1.  **Modify `weather_widget_info.xml`**:
    *   Change `android:minWidth` to `110dp`.
    *   Change `android:minResizeWidth` to `110dp`.
2.  **Modify `DailyViewHandler.kt`**:
    *   Move the calculation for `useGraph` (using `rawRows` and `GRAPH_ROW_THRESHOLD`) higher up in `updateWidget` before the header elements are rendered.
    *   Wrap the visibility logic for `current_temp`, `weather_icon`, `current_temp_delta`, and `precip_probability` with a condition `if (useGraph) { ... } else { ... hide them ... }`.
    *   In the `else` block (for `setTextModeViews`), change the `numColumns` argument passed to `updateTextMode` to `maxOf(1, numColumns - 1)`.

## Verification & Testing
*   Deploy to emulator.
*   Resize the widget to 1 row tall (e.g., 4x1).
*   Verify the header temperature and icon are hidden.
*   Verify there is exactly 1 fewer column than a 4x2 widget, and that the API/Settings icons do not overlap any text.
*   Attempt to shrink the widget horizontally to 1 column wide; verify the launcher prevents it.