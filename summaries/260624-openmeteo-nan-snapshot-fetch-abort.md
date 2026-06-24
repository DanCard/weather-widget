# Fix: recurring "Cannot round NaN value." aborting fetches (Open-Meteo NaN temps)

## Issue

The device `app_logs` showed a recurring `NET_FETCH_ERROR: Network fetch failed: Cannot round
NaN value.` — ~8 times per day on the Samsung (SM-F936U1), roughly every 30 min. Surfaced while
investigating an unrelated "did the widget redraw because of a crash?" question (it did not — that
redraw was a normal stale-data refresh on screen-unlock-while-charging).

## Root cause

A `NaN` poison-sentinel that defeats Kotlin null-safety:

1. **`OpenMeteoApi.kt`** emitted `Float.NaN` as a "missing value" sentinel for daily highs/lows
   (`toFloatOrNull() ?: Float.NaN` and `getOrNull(index) ?: Float.NaN`). It was forced to because
   `DailyForecast.highTemp/lowTemp` are non-null `Float`, while the DB's `ForecastEntity` is nullable
   `Float?` — the type mismatch left no clean way to represent "absent". Open-Meteo returns `null`
   daily entries at the edges of its forecast/history window, so those days became `NaN`.
2. **`ForecastRepository.saveForecastSnapshot` (~line 573)** did
   `forecast.highTemp?.roundToInt()?.toFloat()`. Kotlin's `?.` only guards `null`; `NaN` is a
   non-null `Float`, so `roundToInt()` runs and throws `IllegalArgumentException("Cannot round NaN
   value.")`. The only existing guard (`highTemp == null && lowTemp == null`) also treats `NaN` as
   present.
3. The throw is caught at `ForecastRepository.kt:231` → logged as `NET_FETCH_ERROR`, which **aborts
   the remaining per-source snapshot saves for that cycle** (data loss for sources after the bad one)
   and sets `lastFetchTime = 0L` (immediate retry → the ~30-min recurrence).

Diagnosis tell: the error fired immediately after NWS snapshots all logged `SNAPSHOT_SKIP` cleanly,
so it was the *next active source* in the save order (`ForecastRepository.kt:411-417`) — Open-Meteo.

## Fix

Two layers:

- **Source** (`shared/.../data/remote/OpenMeteoApi.kt`, both the forecast and historical blocks):
  parse temps as nullable (`toFloatOrNull()`, no `?: Float.NaN`) and build days with
  `mapIndexedNotNull`, skipping any day lacking a *finite* high and low
  (`getOrNull(index)?.takeIf { it.isFinite() }`). `NaN` never enters the data model.
- **Defense-in-depth** (`app/.../data/repository/ForecastRepository.kt`): sanitize with
  `?.takeIf { it.isFinite() }` before `roundToInt()`, so **no source's** non-finite value can ever
  abort a fetch or silently drop other sources' snapshots again.

## Verification

- `:shared` and `:app` compile clean.
- New regression test in `OpenMeteoApiTest.kt`: a response with `null` daily temps now skips the
  partial days and asserts all surviving temps are finite. Passes.
- Existing Open-Meteo + forecast snapshot/rounding/history suites all green.

## Note

This is the third member of the same NaN-poison family in this codebase (alongside the hourly
single-day pin crash and the decimal-high investigation). The unifying lesson: `NaN` is the worst
sentinel in Kotlin because `?.`, `?:`, and `== null` all treat it as a valid present value, so it
sails past guards and detonates at the first arithmetic that can't represent it (`roundToInt`).
Sanitize at the API-parser boundary so `NaN` never reaches the DB or renderers; when a non-null
numeric needs an "absent" state, use `null` (nullable type) or skip the record.

## Files changed

- `shared/src/main/kotlin/com/weatherwidget/data/remote/OpenMeteoApi.kt` — stop emitting `Float.NaN`;
  skip days without finite high/low.
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt` — `isFinite()` sanitize
  before rounding in `saveForecastSnapshot`.
- `app/src/test/java/com/weatherwidget/data/remote/OpenMeteoApiTest.kt` — regression test.
