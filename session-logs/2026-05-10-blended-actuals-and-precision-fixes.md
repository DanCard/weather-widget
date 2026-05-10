# Session Log: Blended Actuals and Temperature Precision Synchronization
**Date:** Sunday, May 10, 2026
**Topic:** Added "Location actual" to history graphs, resolved 0.1° discrepancy, and unified UI labels.

## 1. Objective
Enhance the "History of Forecasts" visualization by adding the app's high-precision blended measurements ("Location actual") alongside the provider's data, resolving subtle rounding discrepancies between different app views, and improving UI responsiveness on high-density displays.

## 2. Key Accomplishments

### A. Forecast Accuracy Filtering
- **Dynamic Summaries**: Updated `ForecastHistoryActivity` and `StatisticsActivity` to respect user settings. Accuracy statistics are now only shown for APIs that are currently enabled.
- **Extended Source Support**: Added **Tomorrow.io** and **Silurian.ai** to the `AccuracyCalculator` data pipeline, ensuring all 7 supported sources are tracked.
- **Code Refactor**: Replaced hardcoded source logic with a generic list-based approach for easier future maintenance.

### B. High-Precision History Graphs
- **Dual Actuals**: Modified `ForecastEvolutionRenderer` to draw two horizontal "ground truth" lines:
    *   **API actual** (Orange, dashed): The extreme value reported by the official weather service.
    *   **Location actual** (Red, solid): The high-precision blend measured locally by the app.
- **Integer to Float Transition**: Upgraded the entire visualization pipeline (data classes, bucketization, and labels) from `Int` to `Float` precision. This ensures that a value like `52.9°` is correctly graphed and labeled instead of being rounded to `53°` or `52°`.
- **Enhanced Error Mode**: Added the "API actual" bias line to the error graph. This allows the user to see how much the weather provider's own "truth" differs from the high-precision station blend for that location.

### C. Resolution of 0.1° Temperature Discrepancy
- **Unified Formatting**: Created `TempUtils.formatTemp` to centralize all temperature string generation. Previously, 4 different components were using inconsistent rounding thresholds (0.01 vs 0.05).
- **Location-Aware Data Selection**: Discovered that when multiple "stray" records from nearby coordinates exist (e.g., from a recent move), different views were picking different rows. Updated `ObservationResolver` and `ForecastHistoryActivity` to always select the row **mathematically closest** to the current location.
- **Strict Integrity**: Removed all fallback logic in the history view. It now strictly shows source-matched data, ensuring what you see on the widget always aligns with what you see in history.

### D. UI & Layout Enhancements
- **Flexbox Legend**: Refactored the graph legend in `activity_forecast_history.xml` to use `FlexboxLayout`. This fixes vertical padding issues on high-density devices like the Pixel 7 Pro and allows the legend text to wrap naturally.
- **Relocated Numerical Actuals**: Moved the "API actual" and "Location actual" text from the activity header to a new, balanced legend card below the graphs.
- **Unified Naming**: Standardized all UI labels, strings, and list items to use the descriptive terms "API actual" and "Location actual" instead of the ambiguous "Actual".

## 3. Files Modified

### Core Logic
- `app/src/main/java/com/weatherwidget/util/TempUtils.kt` (NEW): Central utility for consistent temperature formatting and distance calculations.
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`: Implemented distance-based selection for daily actuals.
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`: Passed location data to resolvers and added update logging.
- `app/src/main/java/com/weatherwidget/stats/AccuracyCalculator.kt`: Added support for all weather sources.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`: Migrated to unified temperature formatting.

### User Interface
- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`: Refactored to handle dual actuals, removed fallbacks, and added diagnostic logging.
- `app/src/main/java/com/weatherwidget/ui/StatisticsActivity.kt`: Added source filtering based on enabled APIs.
- `app/src/main/java/com/weatherwidget/widget/ForecastEvolutionRenderer.kt`: Upgraded to high-precision Float rendering and dual-actual support.
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Integrated unified formatting.
- `app/src/main/res/layout/activity_forecast_history.xml`: Refactored legend with Flexbox and added the footer actuals card.
- `app/src/main/res/layout/item_daily_accuracy.xml`: Updated labels.
- `app/src/main/res/values/strings.xml`: Updated legend strings.

### Infrastructure & Diagnostics
- `app/src/main/java/com/weatherwidget/stats/AccuracyStatistics.kt`: Updated data structures for new sources.
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`: Added widget-side actual temperature logging.
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`: Fixed location-aware selection in the intent path.

## 4. Verification Results
- **Build**: Successful (`./gradlew assembleDebug`).
- **Data Integrity**: Logs (`WIDGET_ACTUAL` and `HISTORY_LOAD`) confirmed that raw Float values now match perfectly between views.
- **Visual Audit**: Screenshots on Pixel 7 Pro confirmed that the legend now wraps correctly and the vertical spacing issue is resolved.
- **Functionality**: Verified that disabling an API in settings correctly removes it from all accuracy summaries.
