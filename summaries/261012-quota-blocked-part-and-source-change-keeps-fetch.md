# Quota-blocked part is not due · source change keeps the fetch in flight

2026-10-12. Plans: `performance/261012-forecast-loop-quota-blocked-part-is-not-due.md`,
`plans/261012-desktop-source-change-keeps-in-flight-fetch.md`.

## What happened

- The question was why today's NWS left bar was dashed. Its low anchor (Oct 11 06:00) found only
  the Oct 9 22:19 fetch, 31.7 h old.
  - On Oct 10, three NWS source-change fetches were each cancelled 1–2 s in by clicking on to the
    next source.
  - The new forecast loop started at Oct 11 08:17, after that anchor. Three of its four NWS fetches
    on Oct 11 were hourly-only, which writes no `forecasts` rows.
- Looking into that turned up a second problem. From 21:35 to 22:59 on Oct 11 the desktop forecast
  loop woke every minute: `forecast/hours` was quota-blocked, hourly stayed due, the FULL window
  made the plan BOTH, and each wake made billed `currentConditions` + `forecast/days` requests.
  Noon to midnight: 41 + 41 requests, three of them 429 on `forecast/days`.

## What changed

- `:shared`
  - `ForecastParts.forRoutine(…, blocked)`: `blocked` is required. A blocked product's part is not
    due and never pulls the other part along.
  - `SourceQuotaBlocks.blockedProducts`.
  - `ForecastSchedule.heldPast` and `Part.product`.
  - `HourlyCadence.logFragment` adds `quotaHeldMin`.
- Desktop `DesktopForecastPlanner`: passes the blocks, and holds each part's due time past its
  block's end.
- Android `ForecastFetchCoordinator.routineParts` (and therefore `requiresNetworkFetch`) passes the
  blocks; the `FETCH_PLAN` fragment shows `quotaHeldMin`.
- Desktop: the new `LaunchRefreshJobs`.
  - The launch refresh runs beside `fetchJob`. A source change leaves it running; other restarts
    cancel it.
  - A detached refresh stores its data but does not paint (`LAUNCH_REFRESH_DETACHED`).
  - One in-flight fetch per source+site (`LAUNCH_REFRESH_SKIP skip=in_flight`).
  - The old client closes only after the detached fetch finishes.
- No Android change for the source switch: its forced sync already uses `KEEP`.

## Verification

- New and updated tests: `ForecastPartsTest`, `ForecastScheduleTest`, `DesktopForecastPlannerTest`,
  `HourlyCadencePlanRoboTest`, `LaunchRefreshJobsTest`.
- Full `:shared` (2131) and `:desktop` (575) suites green; `:app` unit tests compile.
- Desktop rebuilt and restarted.
- Live checks pending: the next Google hourly 429, and a manual source cycle.
