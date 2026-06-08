# Plan: Android Settings Coordinates Manual Entry

## Objectives
Enhance the Android Settings screen (`SettingsActivity`) to allow users to manually view and update location coordinates. This brings settings feature parity with the desktop app, which allows changing the location from its Settings screen.

---

## 1. Design & Architecture

### 1.1 Global vs. Per-Widget Location
* In Android, coordinates are persisted per-widget ID (e.g., `widget_lat_<widgetId>`).
* In the Settings screen, there is no active widget ID context. We will:
  1. Read the location of the first active widget as the current location. If no active widgets exist, fall back to the last entry in `weather_prefs`'s `historical_pois`.
  2. When the user saves coordinates from the Settings screen, we will update the location for **all active widgets** and also save it to `historical_pois` as the new default location.
  3. Automatically trigger a background update (`WeatherWidgetWorker`) so all active widgets display the new location's forecast immediately.

### 1.2 Reusing Shared Location Resolver
We will inject `SharedLocationResolver` into `SettingsActivity` to resolve manual coordinates (using the shared geocoding code previously extracted).

---

## 2. Implementation Steps

### 2.1 Settings Layout Changes (`activity_settings.xml`)
Add a new "Location Settings" card layout section:
* Title: "Default Location"
* TextView showing the resolved address/coordinates label.
* Latitude and Longitude EditText inputs (`inputType="numberDecimal|numberSigned"`).
* "Save Location" Button.

### 2.2 String Resource Addions (`strings.xml`)
Add coordinate settings string resources:
* `default_location_title` -> "Default Location"
* `location_saved_success` -> "Location updated for all widgets"
* `invalid_coordinates_range` -> "Please enter valid coordinates (-90 to 90 lat, -180 to 180 lon)"

### 2.3 Settings Activity Code Changes (`SettingsActivity.kt`)
* Inject `sharedLocationResolver`.
* Implement `setupLocationSettings()` inside `setupViews()`:
  * Find the list of active widget IDs from `AppWidgetManager`.
  * Retrieve the coordinates of the first active widget (or default to Mountain View if none exist).
  * Pre-fill the Latitude and Longitude EditText inputs.
  * When the "Save Location" button is clicked:
    1. Parse and validate the entered coordinates.
    2. Resolve the pretty location label using `sharedLocationResolver.fromCoordinates`.
    3. Update `widget_lat_` and `widget_lon_` preferences for all active widgets.
    4. Save the location to the `historical_pois` default preferences.
    5. Trigger `WeatherWidgetWorker` to fetch data for the new location.
    6. Show a Toast with the resolved location address.

---

## 3. Verification Strategy

### 3.1 Unit Testing
Create a Robolectric test `SettingsActivityRobolectricTest.kt`:
* Mock `SharedLocationResolver` and configure active widgets via `ShadowAppWidgetManager`.
* Enter mock coordinates, click save, and verify:
  * Preference values are updated for all active widgets.
  * `WeatherWidgetWorker` is enqueued.
  * A success Toast is shown with the resolved address.

### 3.2 Build Verification
Ensure all builds compile and tests pass:
* `./gradlew assembleDebug`
* `./gradlew test`
