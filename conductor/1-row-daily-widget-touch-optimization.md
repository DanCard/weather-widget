# 1-Row Daily Widget Touch Optimization

## Objective
Refine the interaction model for the 1-row (Text Mode) Daily Widget by removing individual day click handlers, hiding the right navigation arrow, and reducing the gap on the left navigation arrow to save space.

## Background & Motivation
In the highly constrained 1-row view, the primary actions are scrolling history, toggling the API source, and opening settings. The user has requested to remove the touch zones for individual days to prevent accidental taps, hide the right navigation arrow (since there's no room/need to scroll forward in 1-row mode), and reduce the gap on the left side of the left arrow to regain visual space.

## Scope & Impact
1.  **Remove Day Click Handlers:** Do not call `setupTextDayClickHandlers` when `!useGraph`.
2.  **Hide Right Navigation:** Hide `nav_right` and `nav_right_zone` when `!useGraph`.
3.  **Adjust Left Navigation:** Update `setupNavigationButtons` to respect `useGraph` for right arrow visibility.
4.  **Reduce Left Arrow Gap:** Modify the padding/margin of `nav_left` or `text_container` via `RemoteViews` to shift the text content closer to the left arrow, reducing the gap.

## Proposed Solution
1.  In `DailyViewHandler.updateWidget`:
    *   Comment out or conditionally wrap the call to `setupTextDayClickHandlers` so it is not executed for Text Mode.
2.  In `DailyViewHandler.setupNavigationButtons`:
    *   Add a `useGraph` boolean parameter.
    *   If `!useGraph`, force `nav_right` and `nav_right_zone` to `View.GONE`.
3.  In `DailyViewHandler.setTextModeViews` (or the layout adjustments section):
    *   Adjust the left padding of the `text_container` to be `0dp` (or negative margin if possible in RemoteViews) to close the gap with the left arrow.
    *   Currently, the `text_container` has `layout_marginStart="4dp"` in XML. We can set the left padding of `text_container` to 0 in code: `views.setViewPadding(R.id.text_container, 0, 0, paddingEndPx, 0)`.
    *   If needed, we can also adjust `nav_left` padding directly in `DailyViewHandler`. Since `nav_left` has `paddingStart="10dp"` and `paddingEnd="10dp"` in XML, we can reduce its padding using `setViewPadding(R.id.nav_left, 0, 0, 0, 0)` via RemoteViews.

## Implementation Steps
1.  **Modify `DailyViewHandler.kt`:**
    *   Update `setupNavigationButtons` signature: `fun setupNavigationButtons(..., useGraph: Boolean)`
    *   Inside `setupNavigationButtons`:
        ```kotlin
        if (useGraph) {
            views.setViewVisibility(R.id.nav_right, View.VISIBLE)
            views.setViewVisibility(R.id.nav_right_zone, View.VISIBLE)
            // ... right arrow logic ...
        } else {
            views.setViewVisibility(R.id.nav_right, View.GONE)
            views.setViewVisibility(R.id.nav_right_zone, View.GONE)
        }
        ```
        ```kotlin
        if (!useGraph) {
            // Reduce left arrow padding to close the gap
            views.setViewPadding(R.id.nav_left, 0, 0, 0, 0)
        } else {
            // Restore original padding for graph mode
            val paddingPx = WidgetSizeCalculator.dpToPx(context, 10)
            views.setViewPadding(R.id.nav_left, paddingPx, 0, paddingPx, 0)
        }
        ```
    *   In `updateWidget`, pass `useGraph` to `setupNavigationButtons`.
    *   In the `else` block (Text Mode), remove the call to `setupTextDayClickHandlers(context, views, appWidgetId, now, visibleDaysInfo, lat, lon, displaySource)`.
    *   In the `else` block (Text Mode), update the `text_container` padding:
        ```kotlin
        views.setViewPadding(R.id.text_container, 0, 0, paddingEndPx, 0)
        ```

## Verification & Testing
*   Deploy to emulator.
*   Verify the right navigation arrow is hidden.
*   Verify clicking on the day columns (e.g., "Mon", "Tue") does nothing.
*   Verify the left navigation arrow is closer to the edge and the text columns have shifted left to fill the gap.
*   Verify clicking the left arrow, API text, and settings gear still works.