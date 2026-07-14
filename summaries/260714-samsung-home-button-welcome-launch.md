# Samsung: widget home button opened the Welcome screen (MainActivity)

Reported 2026-07-14 on SM-F936U1. Tapping the widget's home icon (return-to-daily) showed the
"Welcome to Weather Widget" screen instead of switching the widget to DAILY.

## What the device evidence shows

Pulled `weather_database` + logcat from the device (backup `20260714_124838_sm-f936u1_RFCT71FR9NT`).

- `MAIN_LAUNCH` @ 12:46:57 —
  `referrer=android-app://com.sec.android.app.launcher action=android.intent.action.MAIN
   categories={android.intent.category.LAUNCHER} flags=0x10200000 freshCreate=true`.
  **Samsung One UI Home launched the LAUNCHER activity itself.** No code of ours starts MainActivity.
- **The home broadcast never fired.** A working home tap writes `SET_VIEW_TIMING`; the last one is
  11:58:15. Nothing at 12:46:57. So `ACTION_SET_VIEW` was never delivered.
- **Not the two known bugs.** The process was alive and painting at 12:45:26 (so not a dead-process /
  stale-RemoteViews case); `setupDeadZoneCatchAll` is still bound to `widget_root` in both
  `DailyViewHandler` and `TemperatureViewHandler`; and `WidgetRequestCodes` has no collision (every
  code is `widgetId * 10000 + base`, and the largest base + index offset never crosses bands).

This is the same *family* as `samsung_dead_zone_launches_mainactivity` (launcher fallback to
LAUNCHER), but the existing remedy should have absorbed it and apparently did not.

## The diagnostic gap

`ACTION_SHOW_TOAST` has **never written a single row to `app_logs`** — zero rows, ever.
`handleShowToastAction` only shows a `Toast`, which is ephemeral. So the dead-zone backstop is
invisible in the database: we cannot distinguish "backstop absorbed the tap" from "backstop was
never bound". That is what makes this un-diagnosable from existing logs.

## Changes (diagnostic only — no behavior change)

1. `WeatherWidgetProvider.handleShowToastAction` — also write a DB row (tag `WIDGET_TOAST`, INFO)
   with the widget id and message. Makes the dead-zone backstop observable, and covers the other
   toast paths (fetch failure, API warning) for free. INFO so it also reaches Crashlytics.
2. `MainActivity.logLaunchProvenance` — append a per-widget stored-`ViewMode` snapshot to the
   existing `MAIN_LAUNCH` row. Fires only on MainActivity launches (rare), so no `app_logs` swamp,
   and records which touch zone was under the finger when the launcher hijacked the tap.

## How to read the next reproduction

- `MAIN_LAUNCH` **preceded by `WIDGET_TOAST "Dead zone tapped"`** → the backstop fired; the tap was
  absorbed and something else opened Welcome. Different root cause.
- `MAIN_LAUNCH` **with no `WIDGET_TOAST`** → the launcher intercepted the touch before RemoteViews
  click dispatch ever happened. The root-backstop strategy cannot fix that class of bug; the next
  suspect is the touch never reaching the RemoteViews hierarchy at all.
- The `views=` snapshot names the mode each widget was in, which tells us whether `home_touch_zone`
  was VISIBLE (TEMPERATURE/graph modes) or GONE (`DailyVisibilityManager.hideUnusedDailyViews`).
