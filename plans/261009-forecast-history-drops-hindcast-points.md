# Forecast history: drop points fetched after the extreme (hindcasts)

## Problem

"History of Forecasts" (Android `ForecastHistoryActivity`, desktop `ForecastHistoryWindow`) plotted
every forecast row for the target day, including same-day rows fetched after the high/low had
already happened. Fold 4, Oct 8, Open-Meteo: high reached 14:15, low 06:55, yet the graphs drew
15:50 (82.4 / 57.8) and 21:04 (82.4 / 57.8).

Two kinds of post-extreme row:

1. **Hindcasts** — fetched after the extreme but before the fixed `SameDayExtremeCutoff`
   (16:00 high / 06:00 low): the source has already seen the extreme.
2. **Carried copies** — after the fixed cutoff the writer stores the last pre-cutoff value in
   `highTemp`/`lowTemp` (the real value goes to `hindcast*`), so the newest row stays complete.
   The screen drew that copy again at the later fetch time.

The past-day overlay already follows the user's rule (`ForecastOverlaySettle`, 2026-10-04: a
forecast counts only if fetched at or before the time the extreme was reached); this screen did not.

## Fix (user: "remove them entirely")

`:shared` `ForecastEvolutionCutoff`, per side:

- keep a high if `fetchedAt <= computedHighAt` (when known) **and** `fetchedAt` is before 16:00 on
  the target date; low likewise with `computedLowAt` and 06:00;
- the fixed cutoff is always applied, so carried copies go even when the extreme came later than the
  cutoff (low at 06:55);
- a row with neither side left is dropped (not counted in "N snapshots").

Both platforms build their `EvolutionPoint`s through it, using the source's `daily_history` row for
the extreme times. Writer, `hindcast*` columns and the overlay are unchanged.

## Tests

`ForecastEvolutionCutoffTest` (`:shared`) with the Oct 8 rows: 02:29 kept both sides; 15:50 and
21:04 dropped; extreme after the fixed cutoff → fixed cutoff wins; unknown extreme → fixed cutoff;
future day → untouched; extreme exactly at fetch time → kept.
