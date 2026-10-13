# Daily view backfills NWS observations for sources that borrow NWS actuals

## Problem (emulator-5554, 2026-10-13 09:25)

The today column's low stayed white (46°, forecast) although the new low-settle rule
(`plans/261013-today-low-settles-at-sunrise-plus-30-and-forecast-rise.md`) had passed both its
time and forecast checks:

```
TODAY_LOW_SETTLED settled=false reason=NO_ACTUAL cutoff=07:44 from=sunrise actualLow=null lookaheadMin=64.0
```

- The emulator was off overnight; it booted around 08:28.
- The display source is **Google**, which borrows NWS actuals inside NWS coverage
  (`ActualsProviderResolver.borrowerDefault`).
- NWS observations for today started at **08:10** (6 rows). Synoptic had 541 rows from 00:00
  (lowest 47.0°), and Open-Meteo 42 rows.
- `DailyActualsAssembler` correctly suppressed the partial-day low
  (`suppressedLow=55.39 rows=6`): a minimum from 08:10 onward is not the day's low.
- **Nothing re-fetched the night.** The repair that exists for this case,
  `evaluateHourlyBackfillNeed` → `day_start_uncovered` → `backfillRecentNwsObservations`, is
  reachable from the daily view only through `DailyHistoryBackfillCoordinator.shouldProbeHistoryBackfill`,
  which requires `displaySource == WeatherSource.NWS`. Google displayed ⇒ no probe ⇒ the low stays
  withheld until something else happens to fetch NWS history.
- The forced `missing_actuals_GOOGLE_WEATHER_today` sync ran, but it doesn't help: its daily
  backfill (`backfillNwsObservationsIfNeeded`) looks at past days only, and logged `nwsObs=7ms`
  (no fetch).

Everything past the entry check is already in provider terms:
- `maybeEnqueueHourlyObservationBackfill` resolves `backfillActualsSource(displaySource, lat, lon)`
  (Google → NWS) before deciding.
- `hourlyBackfillCoolingDown` keys its cooldown on the provider.
- `ActualsReadScope.apisFor(displaySource)` reads the provider's rows.

The entry check is the one place still asking about the displayed source.

## Fix

`DailyHistoryBackfillCoordinator.shouldProbeHistoryBackfill` asks about the **actuals provider**:

```kotlin
fun shouldProbeHistoryBackfill(
    actualsSource: WeatherSource,      // was displaySource
    centerDate: LocalDate,
    today: LocalDate,
    visibleDays: Long = HISTORY_BACKFILL_VISIBLE_DAYS,
): Boolean = actualsSource == WeatherSource.NWS && !centerDate.isBefore(today.minusDays(visibleDays))
```

`maybeBackfillIncompleteHistory` calls it with `backfillActualsSource(displaySource, lat, lon)`, the
same resolver the enqueue path uses, so the two can't disagree. `displaySource` keeps going to
`maybeEnqueueHourlyObservationBackfill` unchanged, since that function resolves the provider itself
and logs both (`source=GOOGLE_WEATHER actuals=NWS`). The `DailyViewHandler` wrapper renames its
parameter to match.

Covered by this: Google and Silurian inside NWS coverage (borrower default), and any source whose
provider the user set to NWS in the picker. Not changed:
- METAR-provider sources (outside coverage): `evaluateHourlyBackfillNeed` has no gap fetch for them,
  and the probe stays off, so it costs no DB read.
- Sources with their own actuals (Open-Meteo, WeatherAPI, …): unchanged.

Cost: the probe is one observation read per daily paint for a borrowing source, behind the existing
per-source+site cooldown (`HOURLY_BACKFILL_COOLDOWN_MS`). That is what an NWS-displayed widget
already pays. `DailyBackfillProbeCostTest` pins the "no read when not probing" property and gets a
borrower case.

Desktop: not affected. Its observation loop fetches NWS history over a window (which also covers
wake from suspend), not through a display-source gate. To verify, open the desktop with Google
displayed after the machine was off overnight; no code change is planned there.

## Tests

- `DailyHistoryBackfillGateTest`: Google with provider NWS probes; Google with provider METAR
  doesn't; Open-Meteo doesn't. The existing NWS / visible-days cases are kept.
- `DailyBackfillProbeCostTest`: a Google widget inside NWS coverage reads observations and enqueues
  `day_start_uncovered`. Outside coverage it does neither.
- Integration (Robolectric; `DailyViewHandler` → coordinator → `maybeEnqueueHourlyObservationBackfill`):
  Google displayed, NWS rows only from 08:10, `now` 09:25. The backfill is enqueued with reason
  `day_start_uncovered firstToday=08:10` and `OBS_HOURLY_BACKFILL` logs
  `source=GOOGLE_WEATHER actuals=NWS`.

## Live check

On the emulator, which is in exactly this state today:
1. Install.
2. Repaint the daily view.
3. Expect an `OBS_HOURLY_BACKFILL` row with `actuals=NWS reason=day_start_uncovered`.
4. NWS observation rows from 00:00 appear.
5. `DailyEstimator: today: actual.low=` is non-null.
6. `TODAY_LOW_SETTLED settled=true`, and the low label is red.
