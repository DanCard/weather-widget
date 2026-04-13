# Fix Staleness Dot While Plugged In

## Objective
Ensure the widget's current temperature observation fetch loop correctly resumes when the user unlocks their device while plugged in.

## Root Cause Analysis
1.  **The Fetch Loop Dies on Screen Off:** The 10-minute charging loop evaluates `CurrentTempFetchPolicy` when it runs. If the screen is off (`isScreenInteractive = false`), the fetch is skipped and the loop intentionally **does not reschedule itself**.
2.  **The Resume Trigger Fails:** `ScreenOnReceiver` is supposed to catch `Intent.ACTION_USER_PRESENT` (when the user unlocks the screen) to restart this charging loop.
3.  **The Bug:** In `AndroidManifest.xml`, `ScreenOnReceiver` is declared with `android:exported="false"`. This prevents the Android OS from delivering system broadcasts (like `ACTION_USER_PRESENT` and `ACTION_POWER_CONNECTED`) to the app. As a result, the receiver never fires, and the charging loop remains dead until the user manually interacts with the widget or an opportunistic job manages to complete.
4.  **Secondary Bug:** `ACTION_SCREEN_OFF` is also listed in the manifest for `ScreenOnReceiver`, but Android 8.0+ strict broadcast limitations prevent this intent from being received via manifest registration anyway.

## Implementation Steps
1.  **Update `AndroidManifest.xml`**:
    *   Change the `android:exported="false"` attribute on `.widget.ScreenOnReceiver` to `android:exported="true"`. This will allow the system to deliver the `ACTION_USER_PRESENT` and `ACTION_POWER_CONNECTED` intents.
    *   Remove `ACTION_SCREEN_OFF` from the `<intent-filter>` block, as it is ignored by the OS when registered in the manifest.

## Verification
1.  Plug in the device and verify the charging loop begins.
2.  Turn off the screen and wait for the loop to skip and cancel itself.
3.  Unlock the device and verify in `app_logs` that `ScreenOnReceiver` receives the `ACTION_USER_PRESENT` intent and successfully enqueues the `CurrentTempUpdateScheduler` to resume the loop.