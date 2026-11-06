# Store forecast temperatures at full precision; round at display

## Request

User (2026-11-05): "commit, then stop rounding before storing temps. Rounding should occur during
display." Then: "Why does accuracy round to whole degrees? It shouldn't do that."

This reverses the 2026-07-20 decision to keep `ForecastTempRounding` (memory
`android_future_day_integer_rounding_deliberate`). That decision kept rounding to avoid write churn and
on-screen jitter. Both costs are addressed below.

## Evidence: what rounds today

`shared/.../util/ForecastTempRounding.kt`:

```kotlin
return if (isToday) t else t.roundToInt().toFloat()
```

It is called from one place, the shared `DailyForecastWrite.plan` (both platforms): for `highTemp`,
`lowTemp`, `hindcastHighTemp` and `hindcastLowTemp`. So every day after the fetch date is stored as a
whole number, and today keeps its decimals. Tests that pin it: `ForecastTempRoundingTest` (5 cases).

Because the rounding happens before the dedup check (`existing.values == new`), a forecast that moves
72.4 → 72.3 compares equal and is only re-stamped.

Hourly forecasts are never rounded.

### Accuracy also rounds (user: "It shouldn't do that")

`AccuracyPure.buildDailyAccuracy` rounds forecast **and actual** to whole degrees, then subtracts.
`DailyAccuracy` holds `Int`s (`computedHighTemp`, `forecastHigh`, `highError`, ...), and so does
`AccuracyStatistics.maxError`.

Origin: commit `99e6321a` (2026-02-25, "Store daily temps with tenth-degree precision") moved stored
temps from `Int` to `Float` and kept the `Int`-based accuracy code compiling by rounding at the
boundary ("preserving existing chart/stat behavior"). `2ae07728` (2026-06-08) carried it into the
shared `AccuracyPure` unchanged. It was a type shim, never an accuracy decision.

Effect: errors are quantized. 72.4 vs 72.6 scores 1° (72 vs 73); 72.6 vs 73.4 scores 0°.

## Proposed change: accuracy at full precision

- `DailyAccuracy` temps and errors become `Float`/`Double`; `buildDailyAccuracy` subtracts raw values
  (actual − forecast, sign unchanged).
- `AccuracyStatistics.maxError` becomes `Double`.
- "Within 3°" compares the raw error (`abs(err) <= 3.0`). Visible effect: a 3.4° miss used to round to
  3 and count as a hit; now it is a miss, so "% within 3.0°" can drop slightly. Same at the Celsius
  limit (1.7°), which already compared against rounded °F errors.
- Display rounds: per-day rows (`DailyAccuracyAdapter`, desktop `StatisticsWindow`) print errors and
  temperatures with `formatTemp` (one decimal when not whole); averages and bias already print one
  decimal.
- Consumers to update: `AccuracyBreakdown`, `AccuracyCalculator`, `DesktopAccuracyCalculator`,
  `DailyAccuracyAdapter`, `StatisticsWindow`, both Forecast History screens.
- Stats change for existing history too (the actuals were always stored with decimals), so the
  numbers on the accuracy cards shift once, from the first run of the new build.

## Proposed change

### 1. Storage (shared, one place)

- `DailyForecastWrite.plan` stores the sent value unchanged (non-finite → null stays).
- `ForecastTempRounding.forStorage` is deleted. Its NaN guard moves into `DailyForecastWrite` (already
  partly there: `takeIf { it.isFinite() }`).
- No schema change and no migration: the columns are already `REAL`. Rows stored before this change
  stay whole numbers. New fetches write decimals.

### 2. Display rounding (shared rule)

New `TempUtils.formatForecastTemp(value, date, today, useCelsius)`:

- **Day after today** → whole degrees (what the widget shows now).
- **Today and past** → `formatTemp` as now (one decimal unless within 0.01 of a whole number).

It is applied where a daily forecast for a given day is drawn as text:

| Platform | Call site |
|---|---|
| Android | `DailyTemperatureLabelRenderer` (graph labels), `DailyViewLogic` text mode |
| Desktop | `DailyForecastGraph.formatTemp`, `DesktopWidgetPopup` text mode |
| Both | `DaySourceComparison.cells` (see decision 3) |

Bar *heights* use the raw value. A future bar can move by up to half a degree, which is more accurate
and not visible as a different number.

### 3. Dedup (decision 2)

Recommended: **compare at full precision.** Any change in the provider's value writes a new row.

- Cost: more rows. Estimate: 7 sources × ~16 days × 3 background fetches/day ≈ 340 rows/day, under
  the existing 32-day retention ≈ 11k rows. Current tables hold a few thousand per source; this is
  small for SQLite.
- The History of Forecasts graph gets more points (every fetch that moved the value), which shows the
  forecast's real movement.

Alternative: compare at display precision (whole degrees for future days) and re-stamp when equal. This
keeps today's row counts, but a stored future row keeps the decimals of the fetch that first produced
that whole number. Newer tenths are dropped until the whole number changes.

## Visible changes

1. Future days on the widget: none (still whole degrees).
2. **Today's "yesterday's forecast" bar and past days' forecast bars:** these values were future-day
   forecasts when fetched. With the rule keyed by column date, they show tenths for data stored after
   this change (for example `72.4°` beside today's actual). See decision 1.
3. History of Forecasts evolution labels: tenths for new snapshots (they are labels of a stored value,
   formatted by `formatTempLabel`).
4. All-sources table: depends on decision 3.

## Decisions (user, 2026-11-05: all three recommended options)

Today/past columns show tenths; dedup compares at full precision; the all-sources table shows tenths
for future days.

1. **Past/today columns:** show tenths for forecasts that were made a day or more ahead (simple rule
   by column date; recommended), or keep them whole (needs each value's prediction date at display).
2. **Dedup:** full precision (recommended) or display precision.
3. **All-sources table for future days:** tenths (an analysis view; recommended) or whole degrees like
   the widget.

## Tests

0. `AccuracyPureTest`: 72.4 forecast vs 72.6 actual → highError 0.2 (old code: 1); 3.4° miss is not
   "within 3" (old code: within); maxError keeps decimals. Both fail on the old code.
1. `DailyForecastWriteTest`: a future-day 72.4 is stored as 72.4; 72.4 → 72.3 writes a new row under
   full-precision dedup (fails on the old code, which stored 72 twice and re-stamped).
2. Replace `ForecastTempRoundingTest` with `formatForecastTemp` cases: future 72.4 → `72°`, future 72.5
   → `73°`, today 72.4 → `72.4°`, past 72.4 → `72.4°`, Celsius.
3. Daily label parity: Android `DailyForecastGraphRendererTest` / desktop `DailyForecastGraph` tests
   draw `72°` for a future 72.4 and `72.4°` for today.
4. Host contracts for forecast persistence (`ForecastPersistHostContract`) on Room and desktop SQLite:
   72.4 round-trips through both stores.
5. Update existing tests that seed decimals and expect whole numbers back (`DesktopWeatherDaoTest`,
   `GoogleWeatherFetchIntegrationTest`, `TomorrowIoApiTest`, and others found by the full suite).

## Runtime verification

- Emulator: after one fetch, `forecasts` rows for future days hold decimals (DB backup query). The
  widget's future labels are unchanged.
- Desktop: the same, from the desktop DB. The popup's future labels are unchanged.
- History of Forecasts for tomorrow shows the new decimal snapshots.

## Docs

- `arch/hourly-forecast-update-cadence.md` / `arch/ARCHITECTURE.md`: storage keeps provider precision;
  display rounds future days.
- Update memory `android_future_day_integer_rounding_deliberate` to the new decision.

## Implementation notes (2026-11-05)

1. Correction to "Visible changes" item 1: since `1dda062b` (2026-06-14) the display printed tenths
   for every day; future forecasts looked whole only because storage rounded them, and future climate
   normals already showed tenths (64.9° on the emulator). User chose: whole degrees for future
   provider forecasts, tenths for climate normals. The rule is `DailyColumn.printsWholeDegrees`
   (future, not climate normal, not GENERIC_GAP), used by `TempUtils.formatDayTemp` on both platforms.
2. User exception: History of Forecasts never rounds (evolution labels and the all-sources table use
   `formatTemp` / `formatTempLabel`).
3. Per-day accuracy rows (Android `DailyAccuracyAdapter`, desktop `StatisticsWindow`) print tenths;
   signed errors use the shared `TempUtils.formatSignedDelta`.
4. Storage round-trip is covered by `DesktopWeatherDaoTest` (desktop SQLite) and `ForecastRoundingTest`
   / `OpenMeteoIntegrationTest` (Room), so no new persist-host contract case was added.
5. Emulator, after one FULL_SYNC: tomorrow stored as WAPI 73.7, Open-Meteo 71.6, Silurian 70.13,
   OWM 66.09 (NWS sends 72.0). Widget printed Open-Meteo's tomorrow as 72°/47°; the all-sources table
   printed 71.6°. Accuracy shifted (Google "% within 3°" 50% → 0%: both its days missed by just over 3°).
