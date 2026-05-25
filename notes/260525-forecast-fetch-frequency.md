# Weather Forecast Fetch Intervals

The widget uses an adaptive, state-aware update system that scales fetch frequency based on battery levels, charging state, and user activity.

## Standard Forecast Fetches (WorkManager)

### When Plugged In (Charging)
- **Active Weather Source:**
    - Every **60 minutes** when the screen is interactive (on).
    - Every **120 minutes** when the screen is off.
- **Non-Active Weather Sources:**
    - Every **120 minutes** when the screen is interactive (on).
    - Every **240 minutes** when the screen is off.

### When Unplugged (On Battery)
Updates scale according to the `BatteryFetchStrategy`:
- **Battery > 70%:** Every **240 minutes** (4 hours).
- **Battery > 50%:** Every **480 minutes** (8 hours).
- **Battery <= 50%:** Automatic background fetches are **suspended**.

---

## Opportunistic Fetches

Opportunistic fetches are designed to update the widget's data or UI by "piggybacking" on the device when it's already awake, rather than forcing the device to wake up independently and drain the battery.

### Battery Limits
- **Threshold:** `BatteryFetchStrategy.MIN_BATTERY_FOR_OPPORTUNISTIC_FETCH = 30`
- **Behavior:**
    - **Battery >= 30%:** Allows network-capable paths in opportunistic contexts (e.g., screen unlock).
    - **Battery < 30%:** Opportunistic network fetches are blocked. The widget only performs UI-only updates using cached data.

### Triggers
Opportunistic updates are triggered by events where the system is already active:

1. **Screen On / Device Unlock (`ScreenOnReceiver`):** 
   - Fires on `ACTION_USER_PRESENT`.
   - Triggers an update because the user is actively interacting with the device.

2. **System JobScheduler (`OpportunisticUpdateJobService`):**
   - Scheduled with `JobScheduler` (Android 8+).
   - Runs roughly every 30 minutes when the system decides it is efficient to do so (piggybacking on other work).
   - Does not require charging or idle state.

3. **AlarmManager UI Updates (`UIUpdateScheduler`):**
   - Uses `setAndAllowWhileIdle()` for updates without a guaranteed strict wakeup.
   - Typically used for interpolated current temperature updates (~15-60 min).

### Network vs. UI-Only Updates
- **Opportunistic Network Fetch:** If the battery is above the 30% threshold and the logic determines new data is needed, a network call is made to the weather APIs.
- **Opportunistic UI Update:** If a network fetch is not allowed (low battery) or not yet necessary, the widget updates its visual state (e.g., moving the temperature curve or updating the "Now" indicator) using existing forecast data stored in the local database.

---
*Date: May 25, 2026*
