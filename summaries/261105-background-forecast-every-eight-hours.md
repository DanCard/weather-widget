# Background forecast updates every 8 hours (2026-11-05)

User prompts:

1. "Changed my mind about updates every 4 hours while not in use. Lets change that to every 8 hours."
2. "How often are updates while in use?"

## Change

Background forecast updates now run every 8 hours instead of 4. This applies at battery ≥ 70 % or
charging, whenever the user is not tapping the widget or opening the desktop popup. Screen on and
screen off now share the 8-hour interval.

1. `ForecastHorizonCadence.SCREEN_ON_HOURS` changed from 4 to 8, matching `SCREEN_OFF_HOURS`.
   `screenOnDueAtMs()` is kept, so a different screen-on interval later is a one-line change.
2. `SourceToggleRefreshPolicy` used to borrow the screen-on interval for its low-battery limit. It now
   has its own `LOW_BATTERY_STALE_MS` (4 h), so switching sources behaves as before.
3. `arch/hourly-forecast-update-cadence.md` now shows the 8-hour row and notes that turning the screen
   on no longer brings the next update forward.
4. Updated 10 test files that hard-coded 4 hours. For example, a full day at 85 % battery with the
   screen on is now 3 fetches instead of 6.

## Verification

- `./scripts/unit-tests.sh`: all 6,622 tests pass.
- Fold (SM-F936U1), after `installDebug` and a debug REFRESH:
  `FETCH_PLAN … NWS:none(every=8h/0h),OPEN_METEO:none(every=8h/0h),SILURIAN:none(every=8h/0h)`.
- Desktop, after `scripts/buildStart-desktop.sh`: `app_logs` shows `every=8h` at 06:44. Before the
  change it logged `every=4h` (06:21).

## Updates while in use (unchanged)

| What you do | Refreshes when older than |
|---|---|
| Tap the widget, open the desktop popup, or open Forecast History or Observations | 2 h (battery 70 % or more, or charging) |
| Switch to another source | 2 h, or 4 h on a low battery |
| Current conditions, screen on | every 20 min (battery 70 % or more) |
| Battery below 70 % | 24 h in the background |

Neither platform can tell whether the widget is actually on screen, so "in use" means a tap or a
click. Turning the screen on fetches nothing by itself.
