# Today Column: Triple-Line Logic and Update Lifecycle

This document explains the behavior, update frequency, and temperature calculation logic for the "Today" column in the daily forecast graph.

## 1. Update Lifecycle
The widget's update frequency is designed to balance data freshness with battery efficiency:

*   **Periodic Update (Every 1 Hour):** Managed by `WorkManager`, this background task runs every hour to fetch the latest weather data and refresh the UI.
*   **On-Unlock Refresh (`ScreenOnReceiver`):** Triggered by the `USER_PRESENT` intent whenever the device is unlocked.
    *   **Charging:** Performs a full background data fetch.
    *   **On Battery:** Performs a "UI-only" update. It re-renders the "Today" column using the existing hourly data in the local database, updating the "so far" cutoff to the current minute without hitting the network.

## 2. Temperature Calculation (Triple-Line)
The Today column features three distinct lines: **Yellow** (History), **Orange** (Today/Progress), and **Blue** (Forecast).

### Observed Range (Yellow & Orange Lines)
These lines represent the **Observed Range So Far** (from midnight to the current hour).
*   **The Low (Bottom):** Set to the lowest temperature recorded in the hourly data between midnight and the current time. Once the morning low is reached (e.g., 40° at 5:00 AM), the bottom of these lines will stay at that value for the remainder of the day.
*   **The High (Top):** Set to the highest temperature recorded in the hourly data between midnight and the current time.
    *   In the morning, the top typically follows the current temperature as it warms up.
    *   In the afternoon, once the peak is reached (e.g., 70° at 3:00 PM), the top "locks" at that peak. Even as it cools in the evening, the bar remains at the day's high to show the full range experienced.

### Predicted Range (Blue Line)
*   **Full-Day Prediction:** The top and bottom of the blue line are set to the **Highest and Lowest predicted temperatures** for the entire 24-hour period (midnight to midnight).

## 3. Visual Progression Throughout the Day
*   **Morning:** Yellow/Orange bars are short (representing the morning low to current temp). The Blue bar is taller, showing the expected peak heat.
*   **Afternoon (Peak):** As the actual temperature hits the forecast high, the Yellow/Orange bars "grow" to match the height of the Blue bar.
*   **Evening:** As it cools down, the Yellow/Orange bars remain tall, representing the total range experienced across the day, allowing for a direct comparison against the original forecast.

## 4. Battery Awareness
The implementation is battery-aware by prioritizing local data for UI-only refreshes when the device is not charging. The `DailyActualsEstimator` utility reuses the `hourlyForecasts` already in memory, avoiding redundant database queries or network calls.
