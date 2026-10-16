# Refresh notice only when the user asks for a refresh

User, 2026-10-16 (Samsung bug report, 02:45): "why did I get a message that date of refresh was
queued?" Then: "I don't want the message that was coded. I never asked for that. I asked for when
refresh data is requested that a message pop up."

## What happened

02:43:56, widget #345 showed NWS (240 min old). A right-arrow tap counted the data as stale, queued a
forced sync and showed the banner "Forecast and observations refresh queued"
(`REFRESH_TOAST origin=widget_nav queued=BOTH shown banner=1`).

`plans/261015-refresh-queued-toast-all-surfaces.md` had browsing taps announce any refresh they
queued (`WidgetIntentActionHandler.announceIfQueued`). That was my addition, not the user's request.
The 2026-10-15 follow-up silenced source changes only.

## Rule

A notice appears only for an action whose purpose is to refresh data:
- Android: Settings → Refresh data, the widget's "Tap to refresh", Observations Refresh, Forecast
  History Refresh.
- Desktop: the Refresh buttons (popup/Settings, Observations, Forecast History).

Browsing (arrows, zoom, view switches) and source changes stay silent and log only the
`REFRESH_TOAST … silent` row. The user chose (2026-10-16, "1") to keep the existing notices for the
Observations actuals-provider pick and for enabling a source that becomes primary in Settings.

## Changes (Android; desktop already complies)

1. `WidgetIntentActionHandler`: `announceIfQueued` (arrows, zoom, view switches via
   `renderAfterTransition`) becomes `RefreshToast.logSilent`. Delete `announceIfQueued`.
2. Rewrite the `RefreshToast` KDoc and the comment above the old `announceIfQueued` to match the rule.

The refreshes themselves do not change. Only the notice does.

## Tests

- `WidgetRefreshToastIntegrationRoboTest`: replace the zoom/nav banner tests with "nav, zoom and view
  switch onto stale data show no banner and write a `silent` row". Keep "Tap to refresh" banner tests.
- `./gradlew testDebugUnitTest` for the touched suites; install on the Fold 4 and tap an arrow on a
  stale source to confirm no banner and a `silent` row.
