# Fix Thermometer Icon Click
## Objective
Fix the issue where clicking the thermometer icon in the widget fails to launch the `WeatherObservationsActivity`. The current implementation routes the click through a background broadcast receiver which can be blocked by Android's background activity start restrictions, especially on API 34+ (like the emulator).

## Cause
The `PendingIntent` associated with the thermometer icon (`current_stations_icon`) is constructed using `PendingIntent.getBroadcast` and fires `ACTION_SHOW_OBSERVATIONS` to `WeatherWidgetProvider`. In the `onReceive` method of the provider, `context.startActivity()` is called. 
On newer Android versions (Android 12+ / 14+), starting an activity from a `BroadcastReceiver` in the background is generally blocked unless explicitly allowed via `ActivityOptions`. Instead of adding complex option flags, the standard best practice for widgets is to use `PendingIntent.getActivity()` directly.

## Implementation Steps

1. **Update `TemperatureViewHandler.kt`:**
   - In `setupCurrentStationsShortcut`, modify the `Intent` to point directly to `WeatherObservationsActivity::class.java` instead of `WeatherWidgetProvider::class.java`.
   - Change `PendingIntent.getBroadcast(...)` to `PendingIntent.getActivity(...)`.
   - Remove the `ACTION_SHOW_OBSERVATIONS` extra assignment.
   - Remove the private `ACTION_SHOW_OBSERVATIONS` constant.

2. **Update `PrecipViewHandler.kt`:**
   - Apply the exact same changes to `setupCurrentStationsShortcut` as above.
   - Remove the private `ACTION_SHOW_OBSERVATIONS` constant.

3. **Cleanup `WeatherWidgetProvider.kt`:**
   - Remove `ACTION_SHOW_OBSERVATIONS` from the companion object constants.
   - Remove the `ACTION_SHOW_OBSERVATIONS -> handleShowObservationsAction(context, intent)` branch from `onReceive`.
   - Delete the `handleShowObservationsAction` method.

4. **Cleanup `CloudCoverViewHandler.kt`:**
   - Remove the unused `ACTION_SHOW_OBSERVATIONS` constant.

## Verification & Testing
### 1. Manual Verification
- Deploy the widget to the Android emulator.
- Click the thermometer icon and verify that `WeatherObservationsActivity` opens successfully and immediately.

### 2. Automated Testing
- Create a new Robolectric test class `WeatherObservationsShortcutTest.kt` in `app/src/test/java/com/weatherwidget/widget/handlers/`.
- **Test Case 1:** Render the widget using `TemperatureViewHandler.updateWidget`.
  - Apply the generated `RemoteViews` to a `FrameLayout`.
  - Find `R.id.current_stations_icon` and `performClick()`.
  - Verify that `shadowOf(application).nextStartedActivity` resolves to `WeatherObservationsActivity` instead of generating a broadcast.
- **Test Case 2:** Render the widget using `PrecipViewHandler.updateWidget`.
  - Apply the `RemoteViews`, `performClick()` on `R.id.current_stations_icon`.
  - Verify that `shadowOf(application).nextStartedActivity` resolves to `WeatherObservationsActivity`.