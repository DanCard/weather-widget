# Widget screens reopened on the previous source

Plan: plans/261021-history-shortcut-showed-previous-source.md

- Bug (emulator): widget on NWS → "History of Forecasts" showed Silurian. Observations, Months view
  and Source error details had the same bug.
- Cause: these screens have `taskAffinity=""` and read their source only in onCreate; a
  `NEW_TASK`-only launch brought a still-alive task to the front unchanged (`result code=2`).
- Fix: all four widget launches add `FLAG_ACTIVITY_CLEAR_TASK`.
  - History: `WidgetDayClickCoordinator.navigateToHistory`.
  - Observations / Months: `TemperatureTouchTargets`; error details: `SourceErrorDetailsActivity.intent`.
- Second cause found on the emulator: `FLAG_UPDATE_CURRENT` keeps the **flags** of an activity
  PendingIntent the launcher still holds (across repaints and app updates); only extras change.
  - Observations / error pill / months moved to new `WidgetRequestCodes` bases
    (800→5700, 5400→5750, 5500→5800); old bases retired.
  - History unaffected: its PendingIntent is a broadcast; the receiver builds the activity intent.
- Unchanged, checked: rain forecast / Open-Meteo rain loop (`CLEAR_TOP` already recreates),
  Settings (no per-launch extras).
- `WidgetRenderer.setLocationIntent`: `private` → `@VisibleForTesting internal` (for the guard).
- Tests:
  - `WidgetScreenLaunchFlagsRoboTest` — the 4 launches carry `NEW_TASK | CLEAR_TASK` and the source;
    all 4 fail without the fix.
  - `WidgetActivityPendingIntentFlagsGuardTest` — pins every widget activity PendingIntent's
    (request-code base, flags); fails if flags change under the same base, if a retired base is
    reused, or if a new `PendingIntent.getActivity` site appears unpinned. Mutation-checked: all
    three fail as intended. Robolectric reproduces the UPDATE_CURRENT flag retention (probed).
  - `WidgetScreenReopenSourceIntegrationTest` (emulator) — open on NWS → Home → open on Open-Meteo;
    history and Observations must show Meteo. Without the fix both fail with
    `expected:<Meteo> but was:<NWS>`; with it, 2/2 pass.
  - App unit suite: 2,628 passed, 0 failed, 1 skipped.
- Emulator, by hand: history and Observations reopen on the widget's current source
  (`flg=0x10008000 result code=0`).
- Memory: `pendingintent_flags_need_new_request_code.md`.
