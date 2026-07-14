# onUpdate loading-placeholder flicker (Samsung)

## Symptom

Widget display visibly flicks on the Samsung (SM-F936U1 / HoneySpace) when
`APPWIDGET_UPDATE` fires while cached data is already on screen.

## Diagnosis

From device logcat, 5 widgets, 11:38:06:

```
11:38:06.230  WIDGET_LIFECYCLE phase=onUpdate_entry hasData=true count=5
11:38:06.230  WIDGET_PAINT widget=346 caller=loading state=loading      <- placeholder over good data
11:38:07.0xx  WIDGET_PAINT widget=346 state=data push=full path=onUpdate <- real data ~900ms later
```

`WeatherWidgetProvider.onUpdate` painted the "Loading..." placeholder in BOTH branches of the
`latestWeather == null` check. In the data-exists branch that replaces good cached content with
`--°` / "Loading..." for ~900ms, then paints the real data back. Content -> placeholder -> content
is the flicker.

`updateWidgetLoading` pushes via `updateAppWidget` (full push). Samsung's launcher re-inflates the
whole RemoteViews tree on a full push rather than diffing, so the swap is a hard visual cut.

The "instant feedback" rationale is sound for a cold start (nothing on screen) but was never
re-gated after cached rendering landed, so it also fired on the warm path.

## Change

`WeatherWidgetProvider.kt`:

1. Paint the loading placeholder ONLY when `latestWeather == null`. When cached data exists, leave
   the existing content up until `renderStartupWidgets` replaces it.
2. Gate the `catch (Exception)` error fallback on the same condition. That handler existed to rescue
   widgets stuck on the placeholder painted above; with no placeholder painted, repainting
   "Tap to refresh" over good cached content would be a downgrade. Widgets with cached content now
   keep it when startup render throws.

## Not changed (deliberately)

Residual second repaint: `refresh_action_cache_first` full-pushes all widgets again ~1-4.5s later
(screen unlock -> `ScreenOnReceiver` -> `ACTION_REFRESH` uiOnly=true -> `renderAllWidgetsFromCache`).
`partialPush=true` exists only at `WeatherWidgetWorker.kt:495`; every other repaint path full-pushes.

Deferred because `partiallyUpdateAppWidget` no-ops if the widget has had no full update since boot
and cannot restructure layout — that path is shared with the blank-widget self-heal and view-mode
switches (DAILY<->TEMPERATURE), where a blanket partial push risks resurrecting the blank-widget bug.
Revisit only if the residual repaint is still visible after this fix.
