# Rename History Touch Zone to Forecast History Activity Touch Zone

## Objective
Rename the `history_touch_zone` layout view ID to `forecast_history_activity_touch_zone` across the entire codebase to more accurately describe the activity it launches.

## Key Files & Context
- `app/src/main/res/layout/widget_weather.xml`: Contains the View ID definitions.
- `app/src/main/java/com/weatherwidget/widget/handlers/ApiSourceWarningHelper.kt`: Hides the touch zone.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`: Hides the touch zone.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyVisibilityManager.kt`: Hides the touch zone.
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureTouchTargets.kt`: Binds the `PendingIntent` and manages visibility.
- `app/src/test/java/com/weatherwidget/widget/handlers/HistoryIconVisibilityRoboTest.kt`: Unit tests verifying the visibility state of the touch zone.

## Implementation Steps

1.  **Update XML Layouts**
    -   In `app/src/main/res/layout/widget_weather.xml`:
        -   Change `@+id/history_touch_zone` to `@+id/forecast_history_activity_touch_zone`.
        -   Change `@+id/history_touch_zone_inline` to `@+id/forecast_history_activity_touch_zone_inline`.

2.  **Update View Handlers and Managers**
    -   In `TemperatureTouchTargets.kt`:
        -   Update references from `R.id.history_touch_zone` to `R.id.forecast_history_activity_touch_zone`.
        -   Update references from `R.id.history_touch_zone_inline` to `R.id.forecast_history_activity_touch_zone_inline`.
    -   In `ApiSourceWarningHelper.kt`, `DailyViewHandler.kt`, and `DailyVisibilityManager.kt`:
        -   Update references from `R.id.history_touch_zone` to `R.id.forecast_history_activity_touch_zone`.
        -   Update references from `R.id.history_touch_zone_inline` to `R.id.forecast_history_activity_touch_zone_inline` (where applicable).

3.  **Update Unit Tests**
    -   In `HistoryIconVisibilityRoboTest.kt`:
        -   Update references from `R.id.history_touch_zone` to `R.id.forecast_history_activity_touch_zone`.
        -   Rename the local variable `historyTouchZone` to `forecastHistoryActivityTouchZone` for clarity.

## Verification & Testing
-   Compile the project to ensure no `R.id` resolution errors exist.
-   Run the unit tests, particularly `HistoryIconVisibilityRoboTest`, to ensure they pass with the new view IDs.
-   Run the emulator tests to verify that the app widget renders successfully and the touch target still launches the Forecast History Activity.
