# Fix: missing-hourly banner never renders/clears outside Daily view

## Context

User reported all taps dead on a Samsung widget. Investigation combined live-device
diagnostics (adb logcat/DB/screenshot) with a code trace of the 5 most recent commits
(the two-phase missing-hourly day-tap banner feature: `ffc07113`, `dfe22cb3`, `c1a5ac75`,
`5c34bd7f`, `80eef1dd`).

Two distinct things came out of this, and it's important to keep them separate:

1. **Confirmed code bug (in scope for this fix).** `WeatherWidgetProvider.handleDayClickAction`
   calls `stateManager.setTransientMessage(...)` whenever a day is tapped with no hourly data
   **and** the target view mode is `PRECIPITATION`, `TEMPERATURE`, or `CLOUD_COVER`
   (`WeatherWidgetProvider.kt:615-624`) — i.e. the banner is explicitly designed to appear in
   those three graph views. But only `DailyViewHandler.bindTransientMessage()`
   (`DailyViewHandler.kt:578-587`, called at lines 251 and 462) ever reads
   `getActiveTransientMessage()` and sets `R.id.widget_message_banner`'s text/visibility.
   `TemperatureViewHandler`, `PrecipViewHandler`, and `CloudCoverViewHandler` never touch that
   view at all. Net effect: the "data ends …" banner silently never appears in the three view
   modes it was built for, and — per this codebase's established "RemoteViews visibility is
   sticky" pattern (each view-mode binder must set visibility of every shared header/overlay
   view, or state leaks across mode switches) — if the message were ever set while the banner
   view was visible from a prior Daily-view render, nothing in these three handlers would clear
   it either.

2. **Separate observation, likely not a code bug.** Live diagnostics show the `com.weatherwidget`
   process is currently not running on the device, with no crash/ANR in logcat, while
   `app_logs` shows `DAILY_NAV`/`CYCLE_ZOOM`/`CLICK_DAILY` all logged successfully ~1.6h ago and
   only `ACTION_TOGGLE_API` stopped ~1.47 days ago. `widget_message_banner` is a plain
   `wrap_content`, center-gravity `TextView` with no `clickable="true"` and no
   `setOnClickPendingIntent` bound to it anywhere — so even stuck visible, it shouldn't be able
   to swallow touches meant for the arrows/API indicator (non-clickable views don't consume
   touch dispatch in a RemoteViews-hosted widget). The more likely explanation for *all* taps
   going dead is Samsung One UI's "Sleeping apps" battery restriction killing the process and
   blocking the widget's click broadcast from waking it back up — a device setting, not
   something this fix addresses. Recommend checking Settings → Battery → Background usage
   limits, and removing weather-widget from any "sleeping/deep-sleeping apps" list, as a
   parallel step.

This plan fixes item 1, which the user confirmed ("fix all three handlers").

## Fix

Reuse the existing `DailyViewHandler.bindTransientMessage(views, stateManager, appWidgetId)`
function (already `internal`, so callable from the other handler objects in the same module —
no need to move or duplicate it) and call it from the same place each of the other three
handlers already resets other sticky Daily-view state:

- `TemperatureViewHandler.kt` — near line 158 (`setupDeadZoneCatchAll` call) / before line 173's
  `appWidgetManager.updateAppWidget`, alongside `TemperatureViewBinder`'s existing sticky resets.
- `PrecipViewHandler.kt:103` — at the existing `// Reset sticky visibility from DailyViewHandler`
  comment, which currently resets `header_date_center`, `header_date_right`,
  `graph_day_zones`, `graph_night_rain_zones` but not the banner.
- `CloudCoverViewHandler.kt` (~line 154-159) — same pattern as Precip, alongside its sticky resets.

Each call becomes:
```kotlin
DailyViewHandler.bindTransientMessage(views, stateManager, appWidgetId)
```
placed after `stateManager` is available and before `updateAppWidget(...)` is called, matching
where `DailyViewHandler` itself calls it (lines 251, 462).

No changes needed to `WeatherWidgetProvider.kt`, `WidgetStateManager.kt`, or the layout XML —
the setter/getter and the view already exist and are correctly wired; only the three renderers
were missing the read side.

## Verification

1. `./gradlew testDebugUnitTest --tests "*TemperatureViewHandler*" --tests "*PrecipViewHandler*" --tests "*CloudCoverViewHandler*" --tests "*DailyViewHandler*"` — confirm no existing tests assert banner is absent in these modes (none currently do, per the "never touch that view" finding, so this should be a clean addition).
2. `./gradlew installDebug`, add/resize a widget to a graphical size, switch to Temperature (or Precip/Cloud Cover) view, tap a future day with no hourly data for the active source, and confirm the "data ends …" banner now appears and later clears — check via `adb logcat` for `CLICK_DAILY_NO_HOURLY` and via pulling `app_logs`/`weather_database` per CLAUDE.md's debugging workflow.
3. Separately, on the Samsung device: check Settings → Battery → Background usage limits for weather-widget being marked as a sleeping/restricted app, and reopen the app once to restart the killed process, then retest all tap types (API indicator, arrows, day cells) to confirm the earlier "all taps dead" symptom is unrelated to this code fix.
