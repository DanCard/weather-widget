# Setup screen: back during a running location fix asks to wait

## Problem (emulator, 2026-10-22)

Widget added on a freshly wiped Medium_Phone_API_36. Fine location granted 11:47:07, background
denied 11:47:19, `FIX_START mode=auto` 11:47:19.2, back tapped 11:47:21.3 (`BACK_SAVE usedFix=false`)
→ `Widget 6 saved with no location`. The fix ran in the activity's lifecycle scope, so finishing
cancelled it (no `GPS_FIX` line ever). The widget painted "No location — tap to set" and stayed that
way: background sampling is passive-only (`lastLocation`), and nothing on the device requested a fix.

The in-flight state was only the button text ("Getting location…"); backing out 2 s in reads as the
user taking the screen for stuck.

## Decision (user, 2026-10-22)

Ask the user to wait, in the foreground — not a background continuation. With while-in-use
permission Android stops delivering location once the screen is gone, so a fix carried past finish
would mostly fail; a foreground service would need FOREGROUND_SERVICE_LOCATION and a Play Console
declaration. The fix is bounded by `LocationFixFlow` (10 s active + 5 s cached).

## Behaviour

1. While a fix runs, a progress row under the button: spinner + "Getting your location — this can
   take up to 15 seconds" (seconds from the `LocationFixFlow` timeouts).
2. Back (system or in-app) during widget-add while a fix runs and none has landed: dialog
   "Still getting your location" — **Wait** / **Skip**.
   - Wait: stay; when the fix lands, save it (`saveDeviceFix`, follow-device) and finish.
     A fix that fails leaves the screen open with the usual message, so search is right there.
   - Skip, or back on the dialog: today's path — finish with no location.
   - Back again while waiting: leave now, no location.
   - The fix landing while the dialog is up: the user asked to leave — a fix is saved and the
     screen finishes; no fix finishes with no location.
3. Back with no fix running, or with a fix already offered: unchanged.
4. Settings (global) mode: unchanged (back leaves without changes).

Logs: `BACK_DURING_FIX`, `BACK_WAIT choice=wait|skip|second_back|fix_landed|no_fix`.

## Tests

`ConfigActivityAddFlowRoboTest`: progress row shown during a fix and hidden after; back during a fix
asks and does not finish; Wait + fix → RESULT_OK with the fix saved; Skip → RESULT_OK, nothing saved;
second back while waiting → finishes with nothing saved; Wait + no fix → screen stays open; fix
landing with the dialog up → saved and finished.
