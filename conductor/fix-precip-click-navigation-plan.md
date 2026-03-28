# Precipitation Click Navigation Fix

## Objective
Fix the click navigation behavior for the precipitation probability indicator (the top-row rain chance metric). When clicked from any view, it should open the Precipitation (rain chance) graph. If clicked while already on the Precipitation graph, it should toggle back to the home screen (Daily view).

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` (contains `ACTION_TOGGLE_PRECIP` handling)

Currently, both `CloudCoverViewHandler.kt` and `PrecipViewHandler.kt` hardcode the precipitation touch zone to dispatch `ACTION_SET_VIEW` pointing to the `DAILY` view. We will change this to dispatch `ACTION_TOGGLE_PRECIP` which uses the standard toggle mechanism that handles entering the precipitation graph and correctly reverting to the daily view when toggled again.

## Implementation Steps

1. **Update `CloudCoverViewHandler.kt`**:
   - Add `ACTION_TOGGLE_PRECIP` intent action constant alongside the others.
     ```kotlin
     private const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
     ```
   - Update the intent creation for the `precip_probability` and `precip_touch_zone` click listener.
   - Replace the `goDailyIntent` creation block with:
     ```kotlin
     val precipIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
         action = ACTION_TOGGLE_PRECIP
         putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
     }
     val precipPendingIntent = PendingIntent.getBroadcast(
         context, WidgetRequestCodes.precipToggle(appWidgetId), precipIntent,
         PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
     )
     views.setOnClickPendingIntent(R.id.precip_probability, precipPendingIntent)
     views.setOnClickPendingIntent(R.id.precip_touch_zone, precipPendingIntent)
     ```

2. **Update `PrecipViewHandler.kt`**:
   - Add `ACTION_TOGGLE_PRECIP` intent action constant alongside the others.
     ```kotlin
     private const val ACTION_TOGGLE_PRECIP = "com.weatherwidget.ACTION_TOGGLE_PRECIP"
     ```
   - Similarly, update the intent creation for the precipitation probability zone to use `ACTION_TOGGLE_PRECIP` with `WidgetRequestCodes.precipToggle(appWidgetId)`. This ensures consistency and enables toggling back to `DAILY` correctly.

## Verification & Testing

### 1. Automated Testing (Regression Prevention)
Create a new Robolectric test file `app/src/test/java/com/weatherwidget/widget/handlers/PrecipProbabilityTouchRoutingRoboTest.kt` (similar to `CurrentTempTouchRoutingRoboTest.kt`).
This test should verify that clicking the `R.id.precip_touch_zone` in all view modes (Daily, Temperature, Precipitation, Cloud Cover) triggers a broadcast with `action = WidgetIntentRouter.ACTION_TOGGLE_PRECIP`.

### 2. Manual Verification
1. Compile and install the app to the emulator/device.
2. Ensure there is a precipitation probability > 0% displaying on the top row.
3. Switch the widget to Cloud Cover view and click the chance of rain indicator. Verify it navigates to the Precipitation graph.
4. While on the Precipitation graph, click the chance of rain indicator again. Verify it returns to the Daily forecast view (the home screen).