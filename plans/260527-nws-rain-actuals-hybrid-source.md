# NWS Rain Actuals — Hybrid Source (measured-preferred, forecast fallback)

## Context

Rain actuals for past days must be **API-specific**: when NWS is displayed show NWS's rain,
when Silurian is displayed show Silurian's, etc. Investigation (emulator-5556, live DB) found:

- **Display is already API-specific.** `DailyViewHandler.kt:175` selects
  `dailyActualsBySource[displaySource.id]`, and `computeDailyExtremes` aggregates precip per
  `(date, source)`. No display change needed.
- **Silurian is already done** (commit `f8f6005`): `SilurianApi.parsePrecipAmountMm` parses
  `precipitation_accumulation` (in→mm). Null DB values are a dry-period/stale artifact, not a bug.
- **Open-Meteo / Tomorrow.io already work**: their `<SOURCE>_MAIN` pseudo-actual observations
  (from `ForecastRepository.saveHistoricalActuals`) carry `precipAmountMm`.
- **NWS is the gap.** NWS daily high/low actuals come from *real station observations*
  (IDW-blended), whose `precipitationLastHour` is reliably **null** here. NWS does **not** go
  through `saveHistoricalActuals`. So NWS rain actuals are always empty.

### Decisions (locked with user)

- **Hybrid source for NWS rain:** prefer **measured** observation precip (`precipLastHourMm`,
  already wired, 3-day network-backfillable via the existing temp-backfill call); **fall back**
  to **forecast** precip summed from retained NWS `hourly_forecasts` when observations are null.
  Reason: observations are truthful + backfillable but station-dependent/often null; NWS hourly
  forecasts always carry precip. Hybrid gives coverage without going blank.
- **Backfill:** network backfill of NWS rain is only possible via observations (the
  `/forecast/hourly` endpoint serves now→future only). The observation path already fetches precip
  in the same 3-day call. Forecast fallback is limited to retained hourly rows (~2 days now,
  self-accumulating going forward).
- Day/night split (8AM–8PM / 8PM–8AM) must work on both branches; observation
  `precipitationLastHour` and hourly forecasts both have hourly granularity.

## Key Changes

### 1. Forecast-fallback precip aggregation — `widget/ObservationResolver.kt`
Apply in **both** `computeDailyExtremes` (persisted past-day path) and
`aggregateObservationsToDailyBySource` (today's live actuals on tap, `WidgetIntentRouter.kt:442`),
which currently duplicate the precip block (lines ~145-150 and ~210-215).

Per `(date, source)` group, keep a single coherent provenance:
- If any observation that day has non-null `precipAmountMm` → use observation sums
  (`sumDaytimePrecip` / `sumNighttimePrecip` + total) as today.
- Else → fall back to that source's `hourlyForecasts` (filtered `source == sourceId`),
  summed over full-day / 8-20 / 20-08 windows.

Add forecast helper mirroring the obs helpers:
```kotlin
private fun sumForecastPrecip(hourly: List<HourlyForecastEntity>, startMs: Long, endMs: Long): Float? =
    hourly.filter { it.dateTime in startMs until endMs }
        .mapNotNull { it.precipAmountMm }.takeIf { it.isNotEmpty() }?.sum()
```
This is generic: non-NWS sources have non-null obs precip → unchanged behavior; NWS falls back.
**Precip-only — does not touch the temp blend; no `NWS_MAIN` row is created** (that would corrupt
the distance-0 IDW temp blend).

### 2. Supply hourly forecasts to recompute — `data/repository/ObservationRepository.kt`
`recomputeDailyExtremesForDay` (line 453) passes its `hourlyForecasts` straight to
`computeDailyExtremes`, but two callers pass `emptyList()`:
- `backfillHistoricalObservations` recompute (line 322-329)
- `backfillRecentNwsObservations` recompute (line 328)
Load the day's hourly forecasts (via injected `HourlyForecastDao.getHourlyForecasts` — verify it's
injected; add if not) for the affected window and pass them so the NWS fallback has data.

### 3. Recompute overwrite gate includes precip — `ObservationRepository.recomputeDailyExtremesForDay`
Currently writes a new row only when `highTemp`/`lowTemp`/`condition` change (both `isToday` and
past branches, lines ~486-514). Precip-only deltas are dropped → today's accumulating rain and
backfilled precip never persist. Add precip fields to the change detection / carry them on the
ratchet `copy(...)` in the `isToday` branch.

### 4. Tests — `widget/ObservationResolverTest.kt` (+ repository test if practical)
- Forecast fallback when observation precip is null (NWS-shaped input).
- Observation-preferred when observations carry precip (non-NWS unchanged).
- Day/night split via forecast fallback.
- Recompute gate: precip-only change now persists.

## Verification

- `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.ObservationResolverTest"`
- `./gradlew testDebugUnitTest --tests "com.weatherwidget.data.remote.SilurianApiTest"` (regression)
- `./gradlew installDebug`, then on emulator-5556 force a refresh
  (`adb shell am broadcast -a com.weatherwidget.ACTION_REFRESH -n com.weatherwidget/.widget.WeatherWidgetProvider --eia appWidgetIds 2`).
- Query DB: `daily_extremes` NWS rows should now show non-null `precipDayMm`/`precipNightMm`
  (0.0 in dry weather, derived from NWS hourly forecast where observations are null).
- Toggle API source on the widget and confirm the rain label tracks the displayed source.

## Notes / Risk

- Real rain is absent in current data (all precip 0.0), so live verification confirms *wiring*
  (non-null vs null), not non-zero rendering — covered by unit tests with synthetic precip.
- Keep the temp blend untouched: the fallback reads `hourly_forecasts`, never inserts observations.
