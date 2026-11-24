# Partial-high future days: climate-normal high, green top half

## Problem

Desktop, OWM, Sunday 2026-11-29 showed a high of 57 (stored 57.5) at 33.645,-111.911; every other
source has 72–75°. The same shape appears at 37.417,-122.089 (OWM 56.2 vs 60–65°).

Evidence (desktop DB, OWM fetches 2026-11-24 08:35, both sites):
- OWM hourly rows for Sunday stop at 07:00 local (55.8 … 54.2 … 55.5). The free `/2.5/forecast` is
  40 × 3 h slots from fetch time, so the last date is cut off at a different hour on every fetch.
- `OpenWeatherMapApi.buildDailyForecasts` takes max/min over whatever slots fall on the date →
  high 56.2 (pre-dawn), low 54.2, rain 5% (rain arrives later; NWS 81%, Open-Meteo 68%).
- Monday 11/30 has no OWM row at all.

Current partial-day rule (`PartialForecastDays.fillPartialFutureDays`, both platforms): a future row
missing its high *or* its low takes **both** values from the climate normal and becomes a whole
climate row (green only in the bar's clear-sky share). NWS's terminal low-only day is exempt and
drawn as-is.

## User decision (2026-11-24)

A future day without a real high keeps its real low, takes the **climate-normal high**, and its bar's
**top half is green**. Applies to every partial-high day (OWM cut-off day, NWS terminal low-only day,
any other source), replacing the current both-from-normal swap for that shape.

## Fix

1. **OWM parser (`:shared`)**: the last date in the batch whose final slot is before 15:00 local gets
   `highTemp = null` (low kept). Logged `OWM_TRUNCATED_LAST_DAY date=… lastSlotHour=…`.
   *Claude proposal:* also null that day's `precipProbability` (its 5% has the same truncation flaw).
2. **`PartialForecastDays.fillPartialFutureDays`**: high null + low present (future) → high = normal
   high, low kept, new read-time flag `highIsClimateNormal = true` (not persisted; no schema change).
   `isTerminalLowOnlyNwsFutureDay` exemption removed (that day now takes this path). Low-missing
   rows keep today's behaviour (unchanged, out of scope).
3. **`DailyColumnBuilder` → `DailyColumn.highIsClimateNormal`**, carried by both platforms.
4. **`AdaptiveBarStrokes.plan(…, climateTopHalf)`**: one extra stroke in `COLOR_GAP_FALLBACK` green,
   clipped to the upper half of the bar, drawn last. Android `DailyBarRenderer` and desktop
   `DailyForecastGraph` already draw the stroke list in order → minimal glue. Green constant moves to
   `:shared` `WeatherColors` so both use one value.
5. Labels: the high prints the normal with its tenth (climate values keep the tenth); the low keeps
   the provider rounding.

Stale data: daily reads take the newest row per day, so the next OWM fetch's high-null row replaces
the stored 56.2 row; no cleanup step needed.

## Tests

- `OpenWeatherMapApi` parser: slots ending 07:00 on the last date → high null, low 54.2 (fails on old code);
  last slot ≥ 15:00 → unchanged.
- `PartialForecastDaysTest`: high-null row → normal high, real low, flag set; NWS terminal low-only
  now filled; low-null row unchanged.
- `AdaptiveBarStrokesTest`: `climateTopHalf` adds a green stroke clipped at the midpoint.
- Integration: desktop real SQLite DAO → repository fill → `DailyColumnBuilder` column with
  normal high + flag; Android `DailyViewLogic` (Robolectric) same row → `DayData` flag.
  `DailyParity` fixture extended.

## Docs

`arch/forecast-and-observation-cadence.md` § Climate normals (future-day fallback): partial-high rule
and the OWM truncated-day cutoff.

## Verification

End-of-task gate; desktop redeploy; screenshot desktop Sunday (OWM) after next OWM fetch: green top
half, normal high label, real low. Emulator widget screenshot for the NWS terminal day.
