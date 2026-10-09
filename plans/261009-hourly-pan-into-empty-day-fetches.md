# Panning the hourly view into a day with no hourly fetches it

Status: **approved 2026-10-09** (user chose option 1). Follows
`plans/261009-google-hourly-on-demand-past-72h.md`.

## Problem

Only a daily-view **day tap** starts the on-demand fetch and banner. Reaching a day with no hourly
another way shows a blank graph with no message and no fetch:
- the hourly view's ‹ › arrows (Android, desktop);
- a drag (desktop);
- reopening onto that day (desktop popup).

## Rule (`:shared` `HourlyOnDemand.panAction`)

For the day the hourly view has settled on, i.e. the window centre's local date:
- **Fetch** — the day is not covered by fresh stored hours and the source extends (Google past
  72 h). Same "Fetching hourly forecast for {day}…" banner and fetch as a tap.
- **Message** — a future day with no hourly at all, and no fetch can help (other sources' horizon,
  or past the 240 h reach): "No hourly forecast for {day} — data ends {end}".
- **Nothing** — covered days, and past days (history).

## Platforms

- **Desktop:** `LaunchedEffect` keyed on (settled date, source), started after a 1 s settle (a drag or
  rapid ‹ › ends in one fetch to where the view rests). Skipped while a fetch is already running. Not
  keyed on the hourly data, so a failed fetch is not retried on every cache reload.
- **Android:** after a hourly-view ‹ › navigation (`WidgetIntentActionHandler.navigate`), the same
  decision. A fetch goes through the tap's path (banner + forced follow-up sync). The result of an
  older day's fetch never overwrites a newer day's banner (`handleRefreshComplete` acts only when the
  active message is its own day's banner, or none).

## Tests

- `:shared`: `panAction` unit tests.
- Desktop compose: pan (offset change) to an uncovered Google day → banner + `onNeedHourlyRefresh`
  after the settle; an NWS day with no data → message; a covered day → nothing.
- Android Robolectric: nav right into an uncovered Google day → banner + follow-up queued; stale
  result for an older date does not replace a newer banner.
