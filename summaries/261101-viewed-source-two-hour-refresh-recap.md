# Viewed source stays within 2 hours (recap, 2026-11-01)

The same-day "every source every 2 h on a charger" rule is reverted. The source being viewed now
refreshes once over 2 hours old at battery ≥ 70% or on a charger. Other sources are back on the
4-hour screen-on background schedule. The threshold is ≥ 70%, matching the rest of the app.

## What changed

- **Background:** screen-on fetches every 4 hours, charging or not. Screen-off and low-battery timing
  are unchanged.
- **Viewed source:** shared `ViewingForecastRefresh` forces a fetch for that source alone, because a
  normal sync judges by the 4-hour cadence and would fetch nothing. Repeat requests wait 15 minutes.
  Log tag: `VIEWING_FORECAST`.
- **Android checks:** screen-on, after a graph paint, and each current-temp run with the screen on.
  Android cannot see a widget, so "viewed" means displayed on a widget with the screen on.
- **Desktop checks:** screen wake and each screen-on observation-loop tick.
- **Source switching:** `SourceToggleRefreshPolicy` uses 2 hours under the same battery rule, otherwise
  4 hours.
- **Removed:** `SCREEN_ON_CHARGING_HOURS`, `ForecastHorizonCadence.screenOnCatchUp`,
  `DueWakePlanner.planFromCurrentTempRun`.
- **Docs:** `arch/hourly-forecast-update-cadence.md`.

## Test results

- JVM: 9,295 tests pass across Android, shared and desktop. The instrumented emulator suite was not run.
- Emulator, source aged to 3 hours:

| Scenario | Result |
|---|---|
| Unplugged at 60% | no fetch |
| Unplugged at 85% | fetched only the viewed source |
| Screen on again right after a fetch | no second fetch |
| Charging at 60% | fetched |

The unplugged 85% fetch came from the current-temp check; screen-on itself fired in the charging runs.
`FETCH_PLAN` showed `every=4h`, and only the viewed source's marker advanced.

- Desktop: the new build started cleanly. The 2-hour rule was not seen firing live, because the displayed
  source was only 47 minutes old; only the automated tests cover that path so far. Look for
  `VIEWING_FORECAST` in `app_logs` once it reaches 2 hours.

## Other notes

- `WeatherWidgetProviderEnqueuePolicyTest` failed after a test class was added: the process-wide
  `isTesting` flag can stay on from another test. Its setup now clears the flag. The test that leaves
  it on was not identified.
- The repo requires a two-platform contract for each shared host, so `ViewingForecastHostContract` was
  added.
- Raising the emulator's battery level reads as charging (`BatteryChargeTrend`). Lower it to test unplugged.
