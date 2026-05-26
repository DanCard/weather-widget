# Session Log: Removing Dual-Mode Remnants

**Date:** Monday, May 25, 2026
**Topic:** Cleanup of partially deleted "dual-source" / "dual-mode" daily forecast view.

## Objective
Remove all remaining code, resources, and tests associated with the "dual-mode" feature, which displayed two vertical bars (one per API source) in the daily forecast view.

## Initial Prompts & Instructions
*   **User:** "I had a dual source view mode on daily view forecast. I no longer want it. Has been partially deleted. Sometimes called-dual-mode. Can delete the rest of the dual-source code where two vertical bars where shown one for each source?"
*   **Context:** The feature was known to be partially removed, leaving behind dead logic, touch targets, and configuration options.

## Research & Identification
I conducted a series of grep searches to identify all "dual" and "two bars" related code:
1.  **State:** Identified `KEY_SHOW_TWO_BARS` and associated methods in `WidgetStateManager.kt`.
2.  **UI:** Found the toggle in `SettingsActivity.kt` and `activity_settings.xml`.
3.  **Actions:** Found `ACTION_TOGGLE_DUAL_BARS` in `WidgetActions.kt`.
4.  **Layout:** Identified `dual_touch_zone` in `widget_weather.xml`.
5.  **Logic:** Found extensive parameter passing of `showTwoBars` and `nextSource` in `DailyViewHandler.kt` and `DailyViewLogic.kt`.
6.  **Rendering:** Identified dual-source paints and layout offsets in `DailyForecastGraphRenderer.kt` and `DailyForecastHeaderRenderer.kt`.
7.  **Tests:** Found specific Robolectric tests for dual-mode behavior.

## Implementation Details

### 1. State & Settings UI
*   **File:** `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`
    *   Removed `KEY_SHOW_TWO_BARS`.
    *   Deleted `isShowTwoBarsEnabled()` and `setShowTwoBarsEnabled()`.
*   **File:** `app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt`
    *   Removed `twoBarsCheckbox` initialization and listener.
*   **File:** `app/src/main/res/layout/activity_settings.xml`
    *   Deleted the `show_two_bars_checkbox` view.
*   **File:** `app/src/main/res/values/strings.xml`
    *   Deleted `show_two_bars_on_daily` string.

### 2. Intents & Routing
*   **File:** `app/src/main/java/com/weatherwidget/widget/WidgetActions.kt`
    *   Deleted `ACTION_TOGGLE_DUAL_BARS`.
*   **File:** `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
    *   Removed `handleToggleDualBarsAction` and its routing in `onReceive`.
    *   Cleaned up `activeSources` calculation to no longer include the "next" source.
*   **File:** `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
    *   Deleted `handleToggleDualBars` and `handleToggleDualBarsInternal`.

### 3. Layouts & Touch Targets
*   **File:** `app/src/main/res/layout/widget_weather.xml`
    *   Removed `dual_touch_zone` FrameLayout.
*   **File:** `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureTouchTargets.kt`
    *   Deleted `setupDualToggle` helper.
*   **Visibility Resets:** Removed `R.id.dual_touch_zone` visibility resets in:
    *   `CloudCoverViewHandler.kt`
    *   `PrecipViewHandler.kt`
    *   `TemperatureViewBinder.kt`
    *   `DailyVisibilityManager.kt`
    *   `HeaderRemoteViewsBinder.kt`

### 4. Daily View Logic (`DailyViewHandler.kt` & `DailyViewLogic.kt`)
*   **Refactor:** Stripped all `nextSource`, `showTwoBars`, and `nextSourceWeatherByDate` variables.
*   **Signatures:** Updated `resolveAndBindHeader`, `resolveHeaderState`, and `renderGraphMode` to remove dual-mode parameters.
*   **Logic:**
    *   Simplified API label resolution to only use the current source.
    *   Updated `DailyViewLogic.prepareGraphDays` to remove the parallel logic for the second API source.
    *   Cleaned up `DayData` to remove `nextSource*` fields.

### 5. Renderers
*   **File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastHeaderRenderer.kt`
    *   Removed `DUAL_GLYPH`, `DUAL_BUTTON_MARGIN_END_DP`, and all pill-related constants.
    *   Deleted `drawDualButton` and `resolveDualButtonLeft`.
    *   Simplified header layout calculations.
*   **File:** `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
    *   Removed `PRIMARY_BAR_DUAL_SOURCE_WIDTH_SCALE` and `NEXT_SOURCE_BAR_*` offsets.
    *   Deleted `primaryDualSourceBarPaint` and `nextSourceBarPaint`.
    *   Deleted `drawNextSourceBar`.
    *   Restored `drawTodayTripleBar` to its single-source layout (Observed + Live Forecast + Yesterday's Snapshot).

### 6. Test Cleanup
*   **Deleted Files:**
    *   `app/src/test/java/com/weatherwidget/widget/DualToggleTouchZoneRoboTest.kt`
    *   `app/src/test/java/com/weatherwidget/widget/handlers/DualTouchZoneStickyVisibilityRoboTest.kt`
*   **Modified Files:**
    *   `DailyForecastGraphRendererRoboTest.kt`: Removed dual-mode layout and color tests.
    *   `DailyViewLogicTest.kt`: Removed tests for next-source cloud cover and icon resolution.

## Verification
*   **Build:** Successful.
*   **Unit Tests:** All 1262 tests passed.
*   **UI Audit:** Settings screen no longer shows the "two bars" toggle. Daily forecast correctly displays the single-source triple-bar layout for today.

## Technical Rationale
The "dual-mode" feature was an experimental comparison tool that added significant complexity to the rendering pipeline (35+ parameters in some methods) and the UI. Removing it simplifies the layout logic, improves performance by reducing bitmap draw calls, and makes the codebase more maintainable. The restoration of the symmetric "triple-bar" layout for Today ensures the most relevant data (observed, current forecast, and yesterday's prediction) remains clear and centered.
