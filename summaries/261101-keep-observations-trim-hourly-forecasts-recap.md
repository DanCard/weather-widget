# Keep blended hourly actuals, trim hourly forecasts: recap

Plan: [plans/261101-keep-observations-trim-hourly-forecasts.md](../plans/261101-keep-observations-trim-hourly-forecasts.md)

The retention flip is implemented on Android and desktop and the full test gate passes. The phones are
not updated. The graphs drawing archived hours have not been checked on screen: no device has archived
rows yet, so only automated tests cover that.

## What changed

| Data | Rule now |
|---|---|
| `hourly_actuals` (new table) | Blended temperature, rain and cloud per hour, display source and site; written at 11 days, kept 18 months, never rewritten |
| `observations` | Deleted only in the prune pass, after archiving succeeds, at about 13 days |
| `hourly_forecasts` | Newest-snapshot rows dropped after 5 days; everything deleted at 32 days, by forecast hour |

The hourly temperature, rain and cloud graphs on both platforms read archived hours, as does Android's
rain accuracy list. Live values win where raw rows still exist. Two existing bugs are also fixed: rain
accuracy can fill days 11-30, and desktop ages observations by reading time, like Android.

## Verification

| Check | Result |
|---|---|
| `./scripts/staggered-tests.sh` (JVM and emulators) | Passed |
| Android migration test on emulator | Passed |
| Real pass on an emulator's real database | Clean: schema 85, 1,378 forecast rows deleted (12,700 left), none over 32 days. Archived nothing, because its observations are not old enough yet |
| Real pass on a copy of the desktop database | Forecasts 20,334 to 11,046 rows in 278 ms; 265 rows archived; observations reached back only to 10-19 |
| Desktop app restarted | Running on schema 37 with the new table empty; no pass had run since the restart |

The desktop copy caught a bug the unit tests missed. Google and Silurian archived cloud but no
temperature, because they borrow their actuals and a `supportsTemperatureActuals` check excluded them.
That is fixed, with a regression test, and they now match the NWS line. They have no archived rain, which
is correct for forecast-only sources.

## Open items

- **Phones:** installed on emulators only. On a phone, the next prune deletes forecasts past 32 days for
  good.
- **First desktop archive:** expected after the 24-hour limit passes, on AC with the screen off. Look for
  an `hourly_actuals days=` line under `HISTORY_PRUNE`.
- **Android archive start:** a day or two after 2026-11-01, once readings reach 11 days old and the phone
  has been charging and idle.
- **Unexplained test failures:** `ObservationResolverTest` and `WidgetInteractionReceiverTest` failed in
  one run and passed in every later one, including the full gate. They look flaky; the cause was not found.
- **Disk estimates:** the 18-month sizes are projections. On the desktop copy, 265 rows took 36 KB, a
  little more per row than estimated.

Old data survives only in the device backups `backups/20261101_105806_*` and a temporary desktop snapshot
in the session scratchpad.
