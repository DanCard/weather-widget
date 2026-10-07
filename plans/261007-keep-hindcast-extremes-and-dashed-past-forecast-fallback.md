# Keep post-cutoff extremes; dashed fallback for past-day forecast bars

Status: **implemented 2026-10-07**. Android + desktop.

## Problem (user, 2026-10-07)

With Google Weather selected, the daily view showed no forecast bars on past days (desktop and
emulator). Google was first fetched at 11:16 (desktop) / 11:39 (emulator) on 2026-10-06:

- Tue Oct 6: every fetch was after 06:00, so `SameDayExtremeCutoff` nulled the same-day low before
  it was written. Settled overlay = high 83.8, low null → the right bar was not drawn (a half pair is
  never drawn). The left bar needs a fetch before 06:00/16:00 on Oct 5 — none.
- Mon Oct 5 and earlier: no Google rows.
- The day before, Oct 6's live column drew a forecast low anyway: `todayForecastRange` falls back to
  the min of the day's hourly forecasts when the daily row has no low.

## Decisions (user)

1. **Don't throw the post-cutoff value away; ignore it.** The raw value the source sent after the
   cutoff is kept, and every existing reader keeps ignoring it.
2. **Past days get a marked fallback** when the real rule finds nothing: drawn **dashed**
   (`StandInBarStyle`), never hidden.
3. "For the current case, if no low, then fall back to the hourly forecasted low" — the last step of
   the right bar's chain is the day's hourly forecast min (max for the high), which is what the live
   column showed.

## Design

### 1. `forecasts.hindcastHighTemp` / `hindcastLowTemp`

- New nullable REAL columns (Room v73 → v74, desktop v26 → v27, shared column list
  `DesktopWeatherDatabase.FORECAST_HINDCAST_COLUMNS`).
- Both writers (`ForecastSnapshotStore`, `DesktopWeatherDao.upsertForecasts`) still put the prior
  pre-cutoff value (or null) in `highTemp`/`lowTemp`, exactly as before. The raw value the source
  sent goes into `hindcast*` **only for a field that was frozen**. Every existing reader reads
  `highTemp`/`lowTemp`, so no reader can mistake a hindcast for a forecast — that is what "ignore"
  means here, and it needs no reader audit.
- Android's unchanged-row dedup compares the hindcast fields too; otherwise the first post-cutoff
  batch would match the prior row and the value would never be stored.
- Not frozen into `daily_history`: accuracy stats and the 18-month record stay forecast-only. The
  fallback is display-only and lives as long as the `forecasts` rows (30 days), which matches the
  widget's 30-day navigation.

### 2. Right bar (settled forecast) — `PastDayForecastOverlay.resolve`

Per past day, first match wins:

1. frozen `daily_history.forecastHigh/LowTemp`, both present → real;
2. newest stored row with both high and low (the existing pick) → real;
3. per side: frozen value, else newest stored row with that side → real;
   else **earliest** `hindcast*` value (closest to being a forecast) → fallback;
   else the **day's hourly forecast min/max** for the display source → fallback. Only hours
   fetched before they happened count (`PastDayForecastOverlay.forecastHourlyTemps`): Google's
   history hours are observations, and on desktop they first drew a Google bar for Oct 5, a day
   Google never forecast.

Drawn only when both sides resolve; **dashed** when either side is a fallback.

### 3. Left bar ("yesterday's forecast") — `PriorDayForecast.resolvePast`

Frozen → pre-cutoff pick (unchanged) → **earliest** row with that side (the same fallback today's
column already uses), then the earliest hindcast. Dashed when either side is a fallback. No hourly
step: an hourly min is not "yesterday's forecast".

### Rendering

- Android: `DayData.forecastIsFallback` dashes the past overlay bar; the past left bar reuses
  `snapshotIsStale`. Desktop: `DesktopDailyDay.forecastIsFallback` / `snapshotIsStale` on the past
  branch.
- Android loads past-day rows through `getPriorForecastCandidates`; it now also returns rows that carry a
  hindcast value.

## What this recovers

- Oct 6 (Google): right bar = high 83.8 (frozen) + low from that day's hourly min, dashed. The raw
  daily low Google sent that day was never stored and cannot be recovered.
- Oct 7 (Google) once past: left bar high 84 (real), low 59 from the earliest row (fallback) → dashed.
- From Oct 8 on: full real triple bars.
- Any new source: its first day's post-cutoff values are kept and drawn dashed.

## Tests

- Shared: `PastDayForecastOverlay.resolve` chain order and fallback flag; `resolvePast` fallback;
  `SameDayExtremeCutoff`-driven writer stores hindcast (desktop DAO test).
- Android: Room migration 73→74; `ForecastSnapshotStore` keeps hindcast and does not dedup it away.
- Renderer: past overlay dashed when `forecastIsFallback`.

## Outcome (2026-10-07)

- Emulator: Tue Oct 6 right bar 81.2 / 64.8, dashed (64.8 = the lowest Oct 6 hourly forecast,
  11:00–23:00). Mon Oct 5: none.
- Desktop: Tue Oct 6 right bar 83.8 / 63.5, dashed. Mon Oct 5: none.
- Tests: shared `PastDayForecastOverlayTest` and `PriorDayForecastTest`; desktop DAO hindcast write and
  v26→v27 upgrade; Android `ForecastSnapshotHindcastCutoffTest` and `DailyPastDayResolverOverlayTest`;
  Room 73→74 migration on the emulator. Full Android unit suite (2377), shared and desktop suites pass.
