# Remove the same-day low freeze (06:00 cutoff)

## Evidence (emulator, 2026-11-06)

1. Daily view, Meteo, today: forecast bar bottom 57°; hourly graph forecast low 46.6° (07:00).
2. `forecasts` newest Open-Meteo row for 2026-11-06 (fetched 08:54): `lowTemp=57.0`,
   `hindcastLowTemp=46.6`. The 57 came from the Nov 4 03:32 fetch (no fetch in between).
3. `app_logs`: `SNAPSHOT_SKIP_HINDCAST date=2026-11-06 source=OPEN_METEO froze=low prior_low=57.0`.

## Root cause

`SameDayExtremeCutoff.filter` (user rule 2026-10-04) froze today's low after 06:00, so
`DailyForecastWrite.plan` kept the prior row's low, however old, while the hourly view shows
the newest fetch.

## Change (user, 2026-11-06: "kill the rule")

1. `SameDayExtremeCutoff.filter`: the low always passes through; only the 16:00 high freeze stays.
   `frozeLow`/`frozeAny` removed.
2. `DailyForecastWrite.plan`: today's low = this fetch's low, else `keptTodayLow` (unchanged);
   `hindcastLowTemp` is no longer written. The column stays for older rows and the past-day fallback.
3. Unchanged: `LOW_CUTOFF` as the D−1 anchor in `PriorDayForecast` (left bar) and the History of
   Forecasts trim in `ForecastEvolutionCutoff` (a post-06:00 low is still a hindcast there).
4. Comments updated in `ForecastEntity`, `DesktopWeatherDatabase`, `ForecastEvolutionCutoff`.

## Tests

- `SameDayExtremeCutoffTest`, `DailyForecastWriteTest` (new emulator case: 57 prior, 46.6 sent at 08:00 → 46.6),
  `ForecastSnapshotHindcastCutoffTest` (Android), `DesktopWeatherDaoHindcastCutoffTest` (desktop).
- Verify on the emulator: after the next Meteo fetch, today's grey bar bottom matches the hourly low.
