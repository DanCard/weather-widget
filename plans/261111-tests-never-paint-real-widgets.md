# Plan: instrumented tests never paint the user's real widgets

User (2026-11-11): "emulator messed up, after running emulator tests. Can we do something to stop this from
happening in the future?" Then: "I want to know what caused the issue", and approved fixes 1–3.

## Evidence
- After `staggered-tests.sh` (07:41 run, 123 tests), the emulator's real widget (id 69, NWS) showed an empty
  chart: header, today panel and day labels, but no bars.
- The test process (pid 6983) painted widget 69 during `LocaleSwitchIntegrationTest` (07:41:17–19):
  `WIDGET_PUSH widget=69 caller=DAILY origin=ACTION_REFRESH requestedPush=partial push=full pid=6983
  promoted=placeholder_tree`, with `HEADER_CONTENT widget=69 … icon=ic_weather_unknown temp=null` and
  `configuredLoc=37.77490,-122.41940` (the test fixture location).
- The test sends `ACTION_REFRESH` with no `EXTRA_APPWIDGET_ID`. `WidgetActionReceiver` treats that as
  "every widget", so the app repainted the real launcher widget from the test process with test data.
- The app's database and preferences were intact (an earlier "deleted" reading was a `head`-truncated
  listing). After a launcher restart the widget showed the app's latest real paint.

## Fix
1. `LocaleSwitchIntegrationTest`: target its own widget (`EXTRA_APPWIDGET_ID = widgetId`).
2. `TestModeWidgetGuard` (main code): `WeatherWidgetTestRunner` records the provider's widget ids bound
   before any test runs, which are the real launcher widgets. While `WeatherDatabase.isTestingMode()`, every
   push seam (`WidgetPushDispatcher`, `FetchBanner`, `DailyViewHandler`'s direct warning push) skips those
   ids and logs `TEST_MODE_REAL_WIDGET_BLOCKED widget= caller= origin=` (logcat WARN). Outside test mode
   the guard never blocks.
3. Instrumented `TestModeWidgetGuardIntegrationTest`: two bound test widgets, one marked as pre-existing;
   an untargeted refresh paints the other and the guard blocks the marked one. Plus a pure unit test of
   the blocking rule.

## Verification
`./scripts/unit-tests.sh`; `./scripts/emulator-tests.sh -c` for the new test and `LocaleSwitchIntegrationTest`;
then a full emulator run with the real widget placed, checking it is untouched and logcat shows no
`TEST_MODE_REAL_WIDGET_BLOCKED` from the fixed test.

## Result (2026-11-11)
- `TestModeWidgetGuardIntegrationTest` and `LocaleSwitchIntegrationTest` pass on the emulator; unit tests pass.
- The guard blocked widget 69 four times during the fixed `LocaleSwitchIntegrationTest`
  (`TEST_MODE_REAL_WIDGET_BLOCKED widget=69 caller=DAILY origin=ACTION_REFRESH`), so a path other than
  the test's targeted broadcast still repaints every widget.

## Follow-up: the locale repaint (2026-11-11, user: "track down the locale repaint follow-up")
- Each `LocaleManager.applicationLocales` switch makes Android send `LOCALE_CHANGED`;
  `WeatherWidgetProvider.isCacheRepaintBroadcast` then repaints **every** widget from cache. That is right in
  normal use (the new language must show) and is not a test bug; under tests the guard blocks it.
- It looked like the test's own refresh because `renderAllWidgetsFromCache` always logged
  `origin=ACTION_REFRESH`. The provider now passes `LOCALE_CHANGE` / new `TIMEZONE_CHANGE`. Label only:
  the origin-dependent branches check `USER_INTERACTION` and the background set, which include neither.
- Verified: the rerun shows `TEST_MODE_REAL_WIDGET_BLOCKED widget=69 caller=DAILY origin=LOCALE_CHANGE`
  right after each `LOCALE_CHANGED`. The package-update `APPWIDGET_UPDATE` at run start is blocked the same
  way (`caller=LOADING origin=PROVIDER_ON_UPDATE`).
