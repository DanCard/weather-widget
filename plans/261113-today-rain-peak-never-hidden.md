# Today's rain peak hour is never hidden by the clock

## Report (user, 2026-11-13)

"Fold-4: NWS missing peak rain start. History of forecasts." In the All sources table for Fri Nov 13,
captured at 1:11 PM, NWS's Peak cell was `—`. Meteo showed 1pm, Silurian 11am, OWM 10am, Google 11am,
Tmrw 2pm and WAPI 1pm.

## Evidence

- Fold 4 backup `backups/20261113_131123_sm-f936u1_RFCT70G352Y`, newest NWS `forecasts` row (fetched
  13:09): `hourlyDayPrecipMax=99`, `hourlyDayPrecipPeakAt=12:00`. So the stored peak is correct.
- NWS newest hourly: 10=71 11=97 **12=99** 13=89 14=62, a single-hour peak.
- `DailyRainLabels.buildDailyRainPeakLabel` hides today's label when
  `now >= (dayPeakEndAt ?: dayPeakAt) + 1h`. Here 13:11 ≥ 13:00, so it returned null.

## Root cause

The rule that hides today's peak once it has passed came from the 2026-10-18 plan
(`plans/261018-daily-rain-chance-peak-hour.md`, Decision 1). The user's request
("show when that peak occurs") never asked for it, and the user does not recall choosing it. The
plateau fix earlier today (`plans/261113-today-rain-peak-plateau-hide.md`) only extended the window.

The past-day exclusion (`isPastDate || date.isBefore(today)` → null) has the same origin. It was in
step 1 of the 2026-10-18 plan ("not past days"), not in the request or its decisions. Past forecast
rows already store `hourlyDayPrecipPeakAt` (32-day retention), and past days resolve through the same
`resolveLiveDayNightChance`, so the hour is available.

## Decisions (user, 2026-11-13)

1. Option 1: today shows its peak hour all day, the same as future days.
2. Past days show it too ("Why past days show none? That is horrible.").

## Proposed fix (shared; Android and desktop both read it)

1. `buildDailyRainPeakLabel`: drop both the past-day check and the `date == today && nowMs >= …`
   check, plus the `date`, `today`, `isPastDate`, `nowMs` and `dayPeakEndAt` parameters. The only
   remaining gates are: a day rain label is shown, its % is > 10, and a peak instant exists. Update
   the KDoc.
   - On a past day the peak is the **forecast's** peak hour, paired with the forecast max (the stored
     pair). When the label shows a measured amount instead of a %, the hour still sits above it, as on
     amount labels today.
   - Check that every past-day path carries the stored peak: Android snapshot `ForecastEntity`, desktop
     past-day `displaySnapshot` / `DailyForecastSnapshot` (memory: that projection was lossy before),
     and `DaySourceComparison` for a past date. Plumb it where it is missing.
   - **Found while implementing:** a past day's % is `daily_history.forecastDayPrecipChance`, frozen
     by `DailyHistoryMaintenance` when the 8am–8pm window closes, and `resolveDailyLabelPrecip`'s past
     branch returns no peak at all. The freeze already computes `resolved.dayPeakAt` beside the chance
     and then discards it. Fix:
     - New `daily_history.forecastDayPrecipPeakAt INTEGER` (Room 88→89 ADD COLUMN, desktop v41), frozen
       as a **pair** with `forecastDayPrecipChance` by every writer: the freeze, `planChanceBackfill`,
       `FrozenRainChanceRepair` and the `DailyHistoryKey` merge. The same pairing rule as
       `forecasts.hourlyDayPrecipPeakAt`.
     - The past branch returns the stored peak with the stored chance. Rows frozen before the column
       existed borrow the peak from the stored hours only when those hours' day max equals the frozen
       chance (never an hour belonging to a different %).
2. Remove the now-unused plateau-end plumbing:
   - `DayNightPrecip.dayPeakEndAt` and `ResolvedDailyPrecip.dayPeakEndAt`
   - `DailyRainLabels.peakPlateauEnd`
   - the end computation in `DailyHourlySummaries.liveDayNight`, keeping the `TODAY_RAIN_STITCHED`
     verbose log without `..end`
   - the argument at `DailyColumnBuilder.kt:410`
3. Fix up callers that pass `nowMs` (compiler-driven). No schema change.
4. Memory note `daily_rain_peak_hour_label.md`: record the rule's removal and that it was never a
   user request.

## Tests

- `DailyRainPeakLabelTest`: replace `today hides the hour once it is over`, `today keeps the first hour
  while the plateau runs` and `a one-hour peak or a missing end…` with a single test where today at
  23:00 still shows "12pm". Delete the three `peakPlateauEnd` tests (190, 207, and the end asserts at
  172/186).
- `DailyColumnTodayRainTest` `after the peak hour…` now expects "10am" at 11:15. It fails on the old
  code (`expected:<10am> but was:<null>`).
- `DailyColumnTodayRainPlateauTest` `the peak drops once the plateau's last hour is over` now expects
  the first hour to stay. Keep `mid-plateau keeps the first hour and its sort instant`.
- Past day: `DailyRainPeakLabelTest` `no label, no peak, or a past day shows none` splits so that a
  past day with a stored 60% @ 2pm shows "2pm". Add a `DailyColumnBuilder` past-day test (built from
  a snapshot row) and a `DaySourceComparison` past-date row test with a Peak cell. All must fail on
  the old code.
- Daily parity fixtures: check for any whose clock is past today's peak (`peakLabel=` for today) and
  update them so Android and desktop still agree.
- `./scripts/unit-tests.sh`, then installDebug on all connected devices. Verify that the Fold 4 History
  of Forecasts NWS Peak shows "12pm" after 1pm, and the desktop window too (restart desktop). Then
  step back to a past rainy date (≤ 32 days) on both platforms and confirm the Peak cells and the daily
  graph's past columns show an hour.

## Outcome (2026-11-13)

- `./scripts/unit-tests.sh`: 6949 passed. `WeatherDatabaseMigrationTest` on the emulator: 38/38,
  including `migrate88To89AddsFrozenDayPeak`.
- New and updated tests: `DailyRainPeakLabelTest` (past frozen pair, pre-column borrow only at the same
  max, past period value has no hour), `DailyColumnRainPeakEveryDayTest` (NWS one-hour noon peak at
  13:11 → "12pm"; past day frozen and pre-column), `DailyColumnTodayRainTest` / `…PlateauTest` (hour
  stays after the peak and after the plateau), `DailyHistoryMaintenanceTest` (freeze writes the pair),
  `DailyHistoryKeyTest` (merge fills the pair), `DaySourceComparisonTest` (past-date Peak cell),
  `DesktopFrozenDayPeakStorageTest` (round-trip, v40→v41).
- Fold 4 after installDebug (all 3 devices):
  - 13:25: the widget shows "12pm" over today's NWS `.4in`.
  - 13:26: History of Forecasts NWS Peak reads `12pm` (was `—` at 13:11).
  - Sun Nov 8 (past): OWM `8am` (20% at 8–9am), WAPI `8am`.
- Desktop restarted: DB `user_version` 41, column present; the daily graph logs `peak="12pm"` for today.
- Not fixed here (pre-existing, separate): on Tue Nov 3 every source's frozen chance sits on the
  37.424/-122.088 `daily_history` fragment, while the table reads the 37.417/-122.089 site, whose
  chance is null. The Rain cell falls back to the provider period value (NWS 24% vs the frozen 85%),
  and a period value has no hour, so its Peak stays `—`.
