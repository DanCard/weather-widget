# Daily view: show the hour of peak rain chance above the day's rain %

## Request (user, 2026-10-18)
Daily forecast view: when the daytime chance of rain is > 30 % and < 100 %, show when that peak
occurs — written on top of the rain-chance label, e.g. "1pm".

## Current state
- The daytime rain label ("45%") is drawn above each day's high label. Its value is the 8am–8pm
  hourly max: `DailyRainLabels.resolveLiveDayNightChance` → `DailyHourlySummaries.liveDayNight`,
  which reads the `forecasts` row's stored `hourlyDayPrecipMax` first, then stored hourly rows.
- Hourly is stored to 72 h (8 days after a LONG fetch). Days past that have **only** the stored max —
  no hour to read. So the peak hour has to be stored beside the max, or far days could never show it,
  and a time read from hourly could disagree with a % read from the row.
- Placement: Android `DailyForecastRainLabelRenderer.resolveRainAboveHighPlacement` (already has a
  "forced despite top margin" path — headroom above the label is tight); desktop
  `DailyForecastGraph` (~line 584).

## Plan
1. **Shared rule** (`:shared`):
   - `DailyRainLabels.periodMaxima` also returns `dayPeakHour` (local 0–23): the earliest hour holding
     the max. Ties (a plateau, or OWM's 3-hourly steps) → the plateau's first hour, i.e. when the
     peak chance begins.
   - `DailyHourlySummaries.Summary` gains `dayPrecipPeakHour`, set with `dayPrecipMax` and carried
     forward **together** with it (never a new max with an old hour).
   - `DailyRainLabels.buildDailyRainPeakLabel(...)`: returns "1pm" only when the day label shows a
     percentage (so not ≥ 99 %/95 % amount labels, not past days) and the % is > 30; else null.
     Format `RainAnalyzer.formatHour` ("1pm"); 24-hour locales get "13h"? → see question 3.
2. **Storage**: `forecasts.hourlyDayPrecipPeakHour INTEGER` — Room 80→81, desktop 33→34, migrations
   on both, written wherever `hourlyDayPrecipMax` is.
3. **Android**: `DayData.rainData.dailyRainPeakLabelText`; renderer draws it centred above the rain
   label, smaller (≈ 0.75× the rain font), in **the same blue as the rain % label, full opacity**
   (user, 2026-10-18) — the same paint colour, not a separate token. If it would cross the top
   margin it is dropped (logged `rainPeakLabel skipped reason=top_margin`), never pushing the % down.
4. **Desktop**: same text from `DesktopDailyForecastModel.buildDay`, drawn in `DailyForecastGraph`
   with the same geometry constants (shared).
5. **Tests**: `DailyRainLabelsTest` (threshold 30/31, 100/99 amount case, plateau → first hour,
   3-hourly OWM, past day null); `DailyHourlySummariesTest` (carry-forward keeps max+hour paired);
   Room migration test 80→81; desktop DAO round-trip; Android renderer placement test (label above %,
   dropped at top margin); desktop model test. Then on-device screenshot (Fold 4 + desktop).

## Decisions (user, 2026-10-18)
1. **Today hides the time once the peak hour is over** (now ≥ peak hour + 1 h); the % stays.
2. **Plateau → first hour** of the max, never a range.
3. **Locale default clock**: 12-hour locales "1pm"; 24-hour locales "13:00". Android follows the
   device's 12/24 setting (`DateFormat.is24HourFormat`, which defaults to the locale); desktop reads
   the JVM locale's CLDR short-time pattern.
4. Colour: the rain % label's blue (its paint), full opacity.
