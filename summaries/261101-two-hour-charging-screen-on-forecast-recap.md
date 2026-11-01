# Two-hour forecast fetch on charger with screen on: recap

Forecasts now fetch every 2 hours when the device is charging and the screen is on, on both Android
and desktop. The new interval shows up in the Samsung and desktop logs, but no real 2-hour fetch had
fired when this was written; the Pixel's next one was due about 13:38 on 2026-11-01. Not committed at
the time of writing.

| Power | Screen | Before | Now |
|---|---|---|---|
| Charging | On | 4 h | **2 h** |
| Battery ≥ 70%, not charging | On | 4 h | 4 h |
| Charging or battery ≥ 70% | Off | 8 h | 8 h |
| Battery < 70%, not charging | On or off | 24 h | 24 h |

## What changed

- **The rule:** added a charging-and-screen-on case (`SCREEN_ON_CHARGING_HOURS`) in
  `ForecastHorizonCadence.kt`, so the unplugged 4-hour rate stays as it was.
- **A fix not explicitly requested:** on Android, turning the screen on while charging did nothing if
  no fetch was due yet. A phone last synced with the screen off kept its 8-hour wake, so it would have
  missed the 2-hour fetch during a long screen-on session. `ScreenOnReceiver.kt` now moves that wake
  forward.
- **Docs and tests:** updated `arch/hourly-forecast-update-cadence.md`, corrected the tests that
  assumed 4 hours, and added new ones, including that 2 hours needs both charging and the screen on.

## Verification

- **Tests:** all JVM tests pass. The test script said the long tests had not run, but their results
  were written during that run and Gradle then reported them up to date, with no failures. Emulator
  tests were not run.
- **Both phones:** the build was installed on them, without a specific request for that in this task.
- **Pixel:** logged `SCREEN_ON_FORECAST outcome=replanned battery=80 charging=true delayMin=79`, so the
  new wake move works. It also counts USB power held at 80% (`status: 4`) as charging.
- **Samsung:** `FETCH_PLAN` went from `every=4h` to `every=2h`.
- **Desktop:** rebuilt and restarted. It logged `every=2h` and slept 109 minutes until its next fetch.

## Worth watching

- **API usage:** every enabled source fetches twice as often in this state, and both phones sit on USB
  power, so they qualify whenever the screen is on. Silurian and OpenWeatherMap are in the current
  fetch plans, so they will use more of their quota. Google is not in those plans, but if enabled, each
  fetch spends about 8 of its 200 daily `forecast/hours` calls.
- **Possible separate issue:** the Samsung fetched OpenWeatherMap at 11:52, 12:03, 12:18 and 12:19 even
  though the plan marked it not due. Three of those were before this build was installed, so this
  change did not cause it. Not investigated.

## Notes

- A shorter interval only helps if something wakes the app to check it. Android plans its next wake at
  each sync using the screen state at that moment, so a screen state change has to update the plan too.
  Desktop already re-plans on every screen wake, which is why only Android needed the fix.
- `FETCH_PLAN` reads `every=<interval>/<age of last fetch>`, so `every=2h/1h` means the rule is active
  and the next fetch is not due yet.
