# Hourly Weather Graph

## Overview

The Hourly Weather Graph is an interactive view mode for the weather widget that displays a 24-hour temperature trend using a smooth Bezier curve. It allows users to visualize detailed temperature changes and navigate through time in hourly increments, complementing the default Daily Forecast view.

## User Experience

### Access
*   **Toggle:** Users tap the **current temperature** text on the widget to switch between "Daily View" and "Hourly View".
*   **Indicator:** The widget state persists, so if a user leaves it in Hourly View, it remains there until toggled back.

### Visual Representation
The display adapts based on the widget's vertical size (row count):

*   **Graph Mode (2+ Rows):**
    *   **Curve:** A smooth Bezier curve connecting 24 hourly data points.
    *   **Current Time:** A vertical dashed line labeled "NOW" indicates the current time.
    *   **Labels:** Hour labels (e.g., "12a", "3p") along the bottom.
    *   **Data Points:** Temperature values displayed at peaks, valleys, and the current hour.
    *   **Scaling:** Vertical scale adjusts dynamically based on the min/max temperatures in the visible window.

*   **Text Mode (1 Row):**
    *   **Format:** A text-based list showing key time intervals (e.g., "Now: 72° | +3h: 68° | +6h: 65°").
    *   **Responsiveness:** The number of time slots displayed adapts to the widget's width.

### Navigation
*   **Buttons:** Uses the standard widget left/right navigation arrows.
*   **Behavior:**
    *   **Left Arrow:** Scrolls 6 hours into the past.
    *   **Right Arrow:** Scrolls 6 hours into the future.
    *   **Bounds:** Navigation is limited to a specific range (default: 6 hours past to 18 hours future relative to "now").

## Architecture & Implementation

### 1. State Management
Managed by `WidgetStateManager`, adding support for per-widget view modes.

*   **`ViewMode` Enum:**
    *   `DAILY`: The standard day-by-day forecast bars.
    *   `HOURLY`: The new hourly temperature curve.
*   **State Persistence:**
    *   `widget_view_mode_{id}`: Stores the current mode.
    *   `widget_hourly_offset_{id}`: Stores the current time offset (in hours) for navigation.

### 2. Rendering
A dedicated `HourlyGraphRenderer` handles the drawing logic, distinct from the `TemperatureGraphRenderer` used for daily views.

*   **Bezier Curve:** Uses `android.graphics.Path` with `quadTo` for smooth line rendering.
*   **Dynamic Scaling:**
    *   Calculates `minTemp` and `maxTemp` of the visible 24-hour window to maximize vertical usage.
    *   Scales stroke widths and text sizes based on widget height density (`heightScaleFactor`).

### 3. Data Source
*   **Primary Source:** **Open-Meteo** (Hourly API).
*   **NWS Limitation:** The National Weather Service (NWS) API does not provide the consistent hourly granularity required for this view.
*   **Data Fetching:**
    *   `WeatherWidgetWorker` fetches an extended range of hourly data (**±24 hours**) to support offline navigation.
    *   Data is stored in the `hourly_forecasts` table in Room.

### 4. Integration
*   **`WeatherWidgetProvider`:**
    *   Intercepts `ACTION_TOGGLE_VIEW` broadcasts.
    *   Routes rendering to either `updateWidgetWithData` (Daily) or `updateWidgetWithHourlyData` (Hourly).
    *   Handles `ACTION_NAV_LEFT` and `ACTION_NAV_RIGHT` differently depending on the active `ViewMode`.

## Technical constraints

*   **RemoteViews:** Implementation uses `ImageView` with a dynamically generated `Bitmap`. `RemoteViews` does not support custom `View` drawing directly, so the graph is drawn to a Canvas/Bitmap and set on the ImageView.
*   **Update Frequency:** The "NOW" indicator is static on the generated bitmap. It refreshes whenever the widget updates (typically every 15-30 minutes via opportunistic updates or user interaction).
