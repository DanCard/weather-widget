# History shortcut showed the previous source

**Report (emulator, 2026-10-21):** daily view on NWS → History of Forecasts button → the screen
showed Silurian.

## Cause
- `ForecastHistoryActivity` has `taskAffinity=""` and reads source/date/location only in `onCreate`.
- `WidgetDayClickCoordinator.navigateToHistory` launched it with `FLAG_ACTIVITY_NEW_TASK` alone, so
  a history task still alive from an earlier open (on Silurian) was brought to the front unchanged.
  logcat: `START … ForecastHistoryActivity … result code=2` (START_TASK_TO_FRONT),
  `onActivityRestartAttempt … wasVisible=false`; no `Loading forecast history` line.
- Same for a stale date or location, not just the source.

## Fix
- Launch with `NEW_TASK | CLEAR_TASK`: the old history task is replaced and the new extras are read.

## Same bug in the other widget-launched screens
Every `taskAffinity=""` screen the widget opens, checked:

| Screen | Launch | Status |
|---|---|---|
| Forecast history | broadcast → `navigateToHistory` | fixed (above) |
| Observations | `setupWeatherStationsShortcut`, NEW_TASK | **same bug, reproduced** (widget Silurian → screen Meteo); fixed |
| Months view | `setupMonthsViewShortcut`, NEW_TASK | same bug (reads source in onCreate); fixed |
| Source error details | `SourceErrorDetailsActivity.intent`, NEW_TASK | same bug (page built once in onCreate); fixed |
| Rain forecast / Open-Meteo rain loop | `SimLoopTouchTarget`, NEW_TASK \| CLEAR_TOP | fine: CLEAR_TOP recreates a standard activity |
| Settings | PendingIntent, no extras | fine: nothing per-launch to go stale |

**Flags on an activity PendingIntent need a new request code.** First try added CLEAR_TASK and
the emulator still logged `flg=0x10000000`: `FLAG_UPDATE_CURRENT` replaces only the *extras* of a
PendingIntent the launcher still holds, and that record survived repaints and an app update.
Observations / error pill / months moved to new `WidgetRequestCodes` bases (800→5700,
5400→5750, 5500→5800). History needed none — its PendingIntent is a broadcast; the activity
intent is built fresh in the receiver.

## Verification
- `WidgetActivityPendingIntentFlagsGuardTest` (Robolectric) — pinned (base, flags) per widget
  activity PendingIntent; catches a flag change without a new code, a retired base reused, an
  unpinned new `getActivity` site.
- `WidgetScreenReopenSourceIntegrationTest` (emulator) — NWS → Home → Open-Meteo; fails without the
  fix (`expected:<Meteo> but was:<NWS>`), passes with it.
- `WidgetScreenLaunchFlagsRoboTest` (history, observations, months, error details) — all 4 fail
  on the missing CLEAR_TASK without the fix, pass with it.
- Emulator: NWS → History shows NWS (`result code=0`); Home → toggle to Open-Meteo → History shows
  Open-Meteo, with the NWS history task still alive.
- Emulator: Observations on NWS, Home → toggle to Open-Meteo → Observations shows Meteo
  (`flg=0x10008000 result code=0`).
