# One hourly table: phase 1 recap

Plan: `plans/261101-one-hourly-forecast-table.md`. Follows `plans/261101-every-fetch-saves-an-hourly-snapshot.md`.

## Outcome
Every hourly read site is classified by the rule it needs. Two readers on the wrong rule are fixed, and unused
code is deleted. There is no schema change.

The planned shared read layer was dropped. Most raw readers already want the newest row per hour, which the DAO
query can return directly in phase 3, and `HourlyForecastStitcher` is already the shared rule for settled reads.

## Audit
| Rule | Readers | Phase 1 |
|---|---|---|
| Settled, already | `HourlyForecastLoader`, `GraphDataLoader` graph and current temp, `WidgetStartupCoordinator`, `DailyInteractionRenderer`, `DailyHistoryRun`, `NwsStationActualsRun`, desktop `getHourlyWithHistory` | none |
| Settled, was wrong | Android observations table; header reserve on both platforms | fixed |
| Newest per hour | `DataFreshness`, `SourceStalenessProbe`, `NoHourlyDayClickCoordinator`, `UIUpdateScheduler`, `NowcastRefresher`, `DailyActualsLoader`, daily blend hosts, forecast-history look-ahead | none |
| Write path | `HourlyForecastStore`, both persist Hosts, `DesktopSourceFetchHost` | phases 3 and 4 |
| Dead | `CurrentTempRepository.getInterpolatedTemperature`, `getNextInterpolationUpdateTime` | deleted |

## Changes
1. Android `GraphDataLoader.loadSettledHourlyForecasts` and desktop `getHourlySettledAllSources`: live plus history
   through `stitchBySource`.
2. The header line reserve and the Android observations table read settled rows.
3. Removed the unused interpolation methods and `CurrentTempRepository`'s `hourlyForecastDao` parameter.
4. `HourlyProximityQueryAllowlistTest`: each raw reader's reason restated by rule.
5. `StitchedHourlyReadContract`: every source settles on its own pre-hour forecast, on Room and desktop SQLite.

## Verification
- `./scripts/unit-tests.sh`: exit 0, 6,506 passed, 1 skipped (`SettingsSourceListRoboTest`, "a source with no key is
  refused and the page shows the key fields").
- The new contract case fails on desktop against the old raw read.
- Desktop, Samsung, Pixel and emulator took the build. Pixel widget headers painted after a refresh with no crash,
  and desktop logs showed no errors.

## Not verified
- The observations table on a device: `am start` from adb failed, so the screen was not opened.
- The header's added history read: timing was not compared.

## Carried to phase 3
- `SettledForecastPick`'s note that the current hour stays live no longer matches its rule, which freezes it.
- The desktop blend host does not collapse sites as Android's does.
