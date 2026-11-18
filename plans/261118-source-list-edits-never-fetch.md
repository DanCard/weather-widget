# Source-list edits never fetch (desktop parity with Android)

## Evidence (desktop `app_logs`, 2026-11-18)

1. 12:36:41 popup switch SILURIAN → GOOGLE_WEATHER: `LAUNCH_REFRESH_CHECK reason=source_change
   forecastStaleAfterMs=7200000 action=NONE` (correct, 2 h toggle rule).
2. 12:44:16 Settings drag only reordered `visibleSources` (`CONFIG_SAVE visibleSources: [...]`).
3. Same second: `LAUNCH_REFRESH_CHECK reason=source_or_location_change forecastAgeMs=2253534
   forecastStaleAfterMs=900000 action=FULL_FORECAST` → Google currentConditions, forecast/days and
   8 `forecast/hours` pages. The refresh fetched the *displayed* source, not any edited one.

## Root cause

`daemonFetchRestartReason` (desktop `DaemonProcess.kt`) folded `visibleSources != previous` (a
`List` comparison, so order-sensitive) into the location-change reason, which uses the 15-minute
user-present threshold. Android's Settings add/remove/move (`SettingsActivity.persistSourceOrder`)
only repaints and never fetches.

## Fix (user direction: "match Android — no fetch on add either. maximize shared code")

1. `:shared` `SourceListEdit`: classifies an edit (added / removed / reordered), owns the rule
   (an edit fetches only when the shown source has nothing to draw) and the shared
   `SOURCE_LIST_EDIT` log line. Android Settings and the desktop daemon both log through it.
2. Desktop: a list-only change restarts the loops with reason `source_list_edit` (so loops pick up
   the new list), the launch check consults the shared rule (`NONE` with a cache), and a launch
   refresh already running survives the restart.
3. Location / displayed-source (`settings.weatherSource`) / actuals changes keep their reasons; a
   removal that moves the displayed source is still a list edit, as on Android.

## Tests

- `:shared` unit test for classification, rule, and log line.
- Desktop: `daemonFetchRestartReason` cases (reorder, add, remove, remove-displayed, location) and
  launch-action cases for `source_list_edit`.
- Android Robolectric: a Settings add/remove/move writes `SOURCE_LIST_EDIT ... fetch=none`.

## Docs

`arch/forecast-and-observation-cadence.md` (source switch / edits bullet).
