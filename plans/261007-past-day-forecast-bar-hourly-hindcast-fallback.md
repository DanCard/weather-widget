# Past-day forecast bar: hourly hindcast as the last fallback

Status: **implemented 2026-10-07**. Android + desktop. Follows
`plans/261007-keep-hindcast-extremes-and-dashed-past-forecast-fallback.md`.

## Problem (user, 2026-10-07)

emulator-5556 first fetched Google at 01:50 on Oct 7, so for Oct 6 it holds no daily forecast row
and no hourly row fetched before its hour. The right bar's last fallback (hourly *forecast* range)
found nothing, and yesterday's column had no forecast bar. Yesterday's hourly view still drew a
dashed "forecast" line, from 23 Google history-hour rows (61.7–84.0) fetched at 01:50 on Oct 7.

User's call: "Should use yesterday hourly hindcast as a fallback."

## Change

`PastDayForecastOverlay.resolve` gains one last step per side, after the hourly forecast range:
the day's **hourly hindcast** range — every stored hour for that date and display source, whatever
its fetch time (the same rows the hourly view draws as its forecast line). Still a fallback: dashed.

Chain per side: frozen → newest stored value → earliest daily hindcast → hourly forecast range
(fetched before the hour) → **hourly range, any fetch time**.

- Android: only the bundle/full-sync paint stitched `hourly_forecast_history` into its hourly list.
  The **startup** (`WidgetStartupCoordinator`) and **tap** (`DailyInteractionRenderer`) paints read
  `hourly_forecasts` alone, so on emulator-5556 the bar appeared on a sync paint and vanished on the
  next startup/tap paint. Both now go through `HourlyForecastLoader.withHistory` (same window, same
  shared stitcher as `load`, no HOURLY_LOAD row). Every paint path now sees the same hours.
  `DailyViewLogic` passes the day's rows twice (forecast-only and all).
- Desktop: same, from `DesktopDailyForecastModel`'s `hourly` list.
- Consequence: a day the source never forecast but has history hours for (desktop Google Oct 5) also
  gets a dashed bar. Intended — the bar matches what that day's hourly view draws.

## Tests

- Shared `PastDayForecastOverlayTest`: forecast hours win over hindcast hours; hindcast hours are used
  when no forecast hour exists; still nothing when there are no hours.

## Outcome (2026-10-07)

- emulator-5556: Tue Oct 6 right bar 84.0 / 61.7, dashed (Google history hours).
- Desktop: Mon Oct 5 and Tue Oct 6 both draw dashed bars.
- Shared, desktop and full Android unit suites pass.
