---
name: night-rain-chance-window-gating
description: daily_history now snapshots the as-displayed forecast rain chance; any archival snapshot writer reading a REPLACE-overwritten live table needs a window-close gate to avoid hindcast drift
metadata: 
  node_type: memory
  type: project
  originSessionId: db7467c7-ecb3-4dad-9438-596406d0e0f5
---

`daily_extremes` was renamed to `daily_history` (v50→v51 Android migration, desktop schema v5→v6)
and gained `forecastDayPrecipChance`/`forecastNightPrecipChance` columns. Root cause: NWS's raw
12-hour period fields use 6am/6pm boundaries, but the app's day/night windows are 8am/8pm — so
6-8am rain (e.g. 14% at 7am) was falling out of "tonight" and showing as NWS's stale 9% period
value instead. Fix: `DailyRainLabels.resolveLiveDayNightChance` (the hourly 8am-8pm/8pm-8am window
max) is now snapshotted into `daily_history` every fetch cycle while a day is still live
(`ForecastRepository.snapshotDisplayedRainChance` / `DesktopWeatherRepository.snapshotDisplayedRainChance`),
and the past-day label path (`resolveDailyLabelPrecip`'s `isPast` branch) replays that stored value
instead of recomputing from period fields.

**Why this matters beyond the specific bug:** any writer that snapshots "what was live" into an
archival table, by re-deriving from a table that gets REPLACE'd on every fetch (`hourly_forecasts`
on both platforms), will silently corrupt its own archive if it keeps recomputing after the
window it's snapshotting has closed — a past hour's row reflects the LATEST re-forecast for that
hour, not what was actually shown when it was live (same root cause as
[[hourly_forecast_line_is_hindcast]], which is why the hourly graph reads
`hourly_forecast_history` for its past segment instead of `hourly_forecasts`).

**How to apply:** any new "snapshot the live value for later replay" writer needs an explicit
window-close guard: `if (nowMs >= windowCloseMs) don't touch the stored value`, not just "recompute
every time and see if it changed." This session's `snapshotDisplayedRainChance` gates day (8pm
cutoff) and night (8am next-day cutoff) independently per date — a day's day-window and night-
window close at different times, so a date can need one field frozen while the other still
updates. A day/night pair reused a full-row `INSERT OR REPLACE` write path shared with an
unrelated actuals writer (`ObservationRepository.recomputeDailyExtremesForDay` /
`DesktopWeatherRepository.recomputeDailyExtremes`) — that writer had to be patched to carry over
the new chance columns from the existing row before its REPLACE, or it would silently clobber them
on every actuals recompute (a live regression test caught this: 70→"999" high-temp diff forced a
REPLACE and the test asserted the chance columns survived).

The one-time 30-day backfill (`backfillForecastChanceSnapshotsIfNeeded`, gated by a
SharedPreferences flag on Android / an `app_logs` tag marker on desktop) deliberately reads
`hourly_forecast_history` (the as-predicted archive), never `hourly_forecasts`, for exactly the
same hindcast-drift reason — and reuses `HourlyForecastStitcher.stitch`/`getHourlyHistory`
(desktop) rather than inventing new freshest-per-hour logic.
