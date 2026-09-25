# Open-Meteo gets no actuals when its actuals provider is set to Synoptic

## Symptom
Pixel 7 Pro and emulator-5554 (Lviv), `actuals_provider_OPEN_METEO = SYNOPTIC`. The Today column on
Open-Meteo shows a Synoptic-derived current temp (45.6°) over a low label of Open-Meteo's
**forecast** low (50.3°), which inverted the thermostat (see 260925-today-thermostat-bulb-at-bottom.md).
The Silurian widget, set to the same provider, shows the Synoptic low (46.9°) correctly.

## Evidence
- `DailyActualsStore: getDailyActualsWithLiveToday ... live=[SILURIAN[blendedHigh=49.82,blendedLow=46.94]]`,
  with no OPEN_METEO entry, on both devices, while displaying Open-Meteo.
- `DailyEstimator: source=OPEN_METEO actual.high=null actual.low=null ... solidLineLowSource=forecast_low`.
- Pixel `daily_history` 2026-09-24 OPEN_METEO row: computed high/low NULL (past days are affected too,
  not only today).

## Root cause
`ActualsAggregator.aggregate` builds a group for a source from two places:
1. **Own group**: rows whose `api == source`. For Open-Meteo these are its own analysis rows.
2. **Borrowed group**: only for `ActualsProviderResolver.borrows(source)`, meaning
   `!supportsTemperatureActuals`. Open-Meteo has `supportsTemperatureActuals = true`, so it is
   never a borrower, even when the user picked another provider.

The series builder then filters each group's rows through `matchesObservationSource`, which does
honour the preference (`providerIdFor(OPEN_METEO) = SYNOPTIC`). It drops all of Open-Meteo's own rows,
the blend returns null, and Open-Meteo gets no row at all. So the setting is respected at filter
time and ignored at grouping time.

Silurian is unaffected because it has no actuals of its own and so is always a "borrower".

Reproduced by the new shared test
`ActualsNonDisplayableSourceTest."a source with its own actuals that the user pointed at another
provider gets that provider's rows"`, which currently fails with 0 Open-Meteo rows.

## Fix
In `ActualsAggregator.aggregate`, key grouping on the resolved provider, not on `borrows()`:
- Borrowed groups: every `ALL_CONFIGURABLE` source whose `providerIdFor(source) != source.id`
  takes `byApi[providerId]`.
- Own groups: skip any source that was redirected (its own rows are rejected downstream anyway, so
  this also saves a wasted blend and avoids two groups with the same source id).

Shared code, so Android and desktop both get it. Also update the stale comments in the aggregator
and `WeatherSource` that still describe Open-Meteo as a forced borrower.

## Tests
- The new shared regression test (fails now, should pass).
- Existing `ActualsNonDisplayableSourceTest`, `BorrowedCloudActualsTest`,
  `ActualsProviderResolverTest`, and the full shared, app-unit and desktop suites.

## Verification
Install on the Pixel and emulator: expect `live=[OPEN_METEO[...], SILURIAN[...]]` and an Open-Meteo
Today low of about 46.9° (`min(Synoptic 46.94, forecast 50.3)` before 9am). Past days get filled on
the next recompute. Restart the desktop app.

## Result
Implemented as planned. Also: `DailyActualsStore`'s `live=[...]` diagnostic now counts provider rows
(it reported Open-Meteo's unused own-api rows). Pixel + emulator after install:
`live=[OPEN_METEO[blendedHigh=49.82,blendedLow=46.94]; SILURIAN[...same]]`, Open-Meteo Today low
46.9° (was the 50.3° forecast), bulb at bottom. shared/app-unit/desktop suites pass.
