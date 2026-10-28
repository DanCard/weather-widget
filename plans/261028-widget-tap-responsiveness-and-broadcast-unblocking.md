# Widget Tap Responsiveness and Broadcast Queue Unblocking

**Date:** 2026-10-28
**Goal:** Eliminate the multi-second tap delay on Android home-screen widgets when tapping after screen-on or during background refresh cycles.

## 1. Problem & Verified Root Cause (SM-F936U1 Device Evidence)

On Samsung Galaxy Z Fold 4 (`SM-F936U1`), tapping the API source button on Widget 345 at `00:38:25.354` took ~2.7 seconds to render:
- **2,229 ms** was spent waiting inside Android `ActivityManagerService`'s `BroadcastQueue` before `WidgetActionReceiver.onReceive` was ever called by Android OS.
- Only **472 ms** was spent executing the toggle handler once received.

### Why Android's AMS delayed delivery for 2.23 seconds:
1. At `00:38:23.882`, screen turned on with `isPaintOwed() == true`. `ScreenOnReceiver` sent broadcast `ACTION_REFRESH (uiOnly = true)`.
2. `WidgetActionReceiver` received `ACTION_REFRESH` and executed `launchGlobal { WidgetRefreshCoordinator.refresh() }`.
3. `launchGlobal` invoked `launchAsync`, calling Android's `goAsync()`.
4. `WidgetRefreshCoordinator.refresh()` called `renderAllWidgetsFromCache()`, which repainted all 4 installed widgets (392, 345, 352, 383) sequentially over **2.86 seconds** (500–1080 ms per widget).
5. In Android OS (AOSP `BroadcastQueue`), manifest broadcast receivers are serialized per component. Because `WidgetActionReceiver` held an active `goAsync()` `PendingResult` for `ACTION_REFRESH`, AMS queued all incoming broadcasts targeted to `WidgetActionReceiver`—including the user's `ACTION_TOGGLE_API` PendingIntent.
6. Only when `ACTION_REFRESH` finished all 4 widgets at `00:38:27.570` and released `goAsync()` did AMS immediately dispatch `ACTION_TOGGLE_API` at `00:38:27.583`.
7. Compounding factor: `launchGlobal` ran on `WidgetInteractionDispatcher.dispatcher`, violating its documented invariant: *"This bounds the interaction path only... sharing one pool would let a 30-second background sync occupy every slot and starve the taps this exists to protect."*
8. Compounding factor: `UIUpdateReceiver` also fired an alarm (`ui_update_alarm`) at `00:38:24.404`, executing `WeatherWidgetWorker` simultaneously with `ACTION_REFRESH`, causing severe disk I/O and mutex contention across widgets.

---

## 2. Solution Architecture

### Phase 1: Separate User Interaction Receiver (`WidgetInteractionReceiver`)
Create a dedicated manifest receiver `com.weatherwidget.widget.WidgetInteractionReceiver` for all user-initiated interactive PendingIntents:
- Actions: `ACTION_TOGGLE_API`, `ACTION_DAY_CLICK`, `ACTION_NAV_LEFT`, `ACTION_NAV_RIGHT`, `ACTION_CYCLE_ZOOM`, `ACTION_SET_VIEW`, `ACTION_TOGGLE_VIEW`, `ACTION_TOGGLE_PRECIP`, `ACTION_RESET_SOURCE`, `ACTION_RECENTER_TODAY`, `ACTION_SHOW_TOAST`.
- Declared in `AndroidManifest.xml` (`exported="false"`).
- Uses `WidgetInteractionDispatcher.dispatcher`.
- In `WidgetActionReceiver`: Retain handler dispatching for these actions as a fallback so existing widgets placed on user home screens continue to work before being repainted.
- In `TemperatureTouchTargets`, `HourlyTouchTargets`, `PrecipTouchTargets`, `CloudCoverTouchTargets`, and `WidgetRenderer`: Update new PendingIntents to target `WidgetInteractionReceiver`.
- **Result:** Because `WidgetInteractionReceiver` is a separate component from `WidgetActionReceiver`, Android AMS dispatches user taps **immediately**, completely unblocked by any in-flight `ACTION_REFRESH`.

### Phase 2: Unblock `WidgetActionReceiver.ACTION_REFRESH`
1. Decouple `launchGlobal` from `WidgetInteractionDispatcher`:
   - Run `ACTION_REFRESH` on `Dispatchers.Default` (or `Dispatchers.IO`), never starving `WidgetInteractionDispatcher`'s 2 interaction slots.
2. Release `goAsync()` promptly:
   - For `ACTION_REFRESH` with `uiOnly = true` or passive updates, do not hold the `PendingResult` across a 3+ second sequential loop of all widgets. Finish the pending result once the coroutine is launched or allow it to finish promptly.

### Phase 3: De-duplicate Screen-On & UI-Update-Alarm Repaints
In `ScreenOnReceiver`:
- If an expedited UI-only sync is already running or scheduled (e.g. from `UIUpdateScheduler`), avoid launching an overlapping `ACTION_REFRESH`.
- When `ACTION_REFRESH` executes, reschedule `UIUpdateScheduler` to prevent an alarm from immediately firing duplicate work milliseconds later.

### Phase 4: Shared Hourly/Snapshot Cache in `renderAllWidgetsFromCache`
In `DailyInteractionRenderer` / `WidgetInteractionCache`:
- When multiple widgets share the same quantized location `(lat, lon, today)`, cache not only `weatherListRaw` and `dailyActuals` but also the shared snapshots, hourly rows, and current observation blend.
- Extend `WidgetInteractionCache` to cover the entire render dataset during a multi-widget burst, reducing subsequent widget repaints from ~900 ms to <100 ms.

---

## 3. Verification Plan
1. **Unit & Robolectric Tests**:
   - Run `./scripts/unit-tests.sh` to ensure all existing interaction, touch routing, and receiver tests pass.
   - Add unit tests for `WidgetInteractionReceiver` and verify unblocked concurrent dispatch.
2. **Device Verification on Fold-4 (`SM-F936U1`)**:
   - Build debug APK (`./gradlew assembleDebug`).
   - Install on Fold-4 (`./gradlew installDebug`).
   - Re-test screen-on followed by immediate API toggle (`adb shell input tap ...` or physical tap).
   - Verify in logcat and `weather_database` `app_logs`:
     - `INTERACTION_E2E queueMs` remains < 10 ms (no 2-second AMS queue wait).
     - `WidgetInteractionReceiver` handles the tap immediately.
     - Toggle completes under ~350-450 ms without stutter.
