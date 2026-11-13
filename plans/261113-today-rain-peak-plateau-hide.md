# Keep today's rain peak hour while its plateau is still running

## Report

"Peak rain chance disappeared" — desktop History of Forecasts, All sources for Fri Nov 13, 12:12 PM.

## Evidence

Screenshot of the live window: Peak column `—` for Silurian and OWM (both Rain 100%); NWS/Meteo
12pm, Google 12pm, Tomorrow/WAPI 1pm.

Desktop DB, newest `forecasts` row per source for 2026-11-13 (fetched 12:10):

| Source | hourlyDayPrecipMax | hourlyDayPrecipPeakAt |
|---|---|---|
| OPEN_WEATHER_MAP | 100 | 10:00 |
| SILURIAN | 100 | 11:00 |
| NWS / GOOGLE / OPEN_METEO | 99 / 99 / 91 | 12:00 |

Newest hourly chances: OWM 100% at 10–13h; Silurian 100% at 11–14h.

## Root cause

`DailyRainLabels.periodMaxima` stores the **first** hour of the max plateau, and
`buildDailyRainPeakLabel` hides today's label once `now >= peakAt + 1h`. OWM's plateau began at 10am,
so its label vanished at 11:00 even though 100% continues to 1pm. Silurian vanished at 12:00 with 100%
through 2pm. Not a data loss and not desktop-only: the same shared rule drives the Android widget's
daily graph and its All sources table.

## Proposed fix (shared, both platforms)

1. `DayNightPrecip` gains `dayPeakEndAt`: the last hour of the contiguous run at `dayMax` that starts
   at `dayPeakAt` (`DailyRainLabels.peakPlateauEnd`). Not stored, so no schema change. Set for **today
   only**, in `DailyHourlySummaries.liveDayNight` from the stitched hours: far days would get a run cut
   short by the 72 h store (`DailySummaryStorageIntegrationTest` caught 7pm vs 11am), and only today's
   label is ever hidden by the clock. Null falls back to the old one-hour window.
2. Thread it through `ResolvedDailyPrecip` → `buildDailyRainPeakLabel`. Hide today's label only when
   `now >= (dayPeakEndAt ?: dayPeakAt) + 1h`.
3. Label text stays the plateau's **first hour** (user, 2026-11-13): OWM reads "10am" until 2pm.
4. Update the KDoc on `buildDailyRainPeakLabel` and the memory note `daily_rain_peak_hour_label.md`.

## Tests

- `:shared` unit: OWM's 10–13h 100% plateau at 12:12 → "10am"; at 14:05 → null; a single-hour peak
  keeps the current behaviour; a non-contiguous second 100% hour does not extend the plateau.
- `DaySourceComparison` test: a plateau source keeps a Peak cell and sorts by its first hour.
- Verify on the running desktop History of Forecasts window and on the emulator widget.

## Outcome (2026-11-13)

- Parity fixtures `google-cloudy-today` and `rainy-days` (noon, flat 8am–7pm chance) now expect
  `peakLabel=8am` for today; Android and desktop agree.
- `DailyColumnTodayRainPlateauTest` fails on the old code (`expected:<10am> but was:<null>`).
- `./scripts/unit-tests.sh` passes. Desktop 12:23: Silurian 11am, OWM 10am. Emulator 12:24: Silurian
  11am, OWM 10am, Google 11am; Tomorrow.io `—` is right (100% only at 11am, 94% at noon).
