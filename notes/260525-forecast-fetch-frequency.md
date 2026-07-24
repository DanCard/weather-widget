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

## Opportunistic Updates

Opportunistic updates piggyback on times when the device is already awake rather than creating an
independent wakeup. Being awake does not make a network request free, so the full-forecast unlock
path is cache-only whenever the phone is unplugged.

There is no longer a 30% battery exception for screen-unlock forecast fetches.

### Triggers
Opportunistic updates are triggered by events where the system is already active:

1. **Screen On / Device Unlock (`ScreenOnReceiver`):** 
   - Fires on `ACTION_USER_PRESENT`.
   - When unplugged, always repaints from cached data and never starts a full forecast fetch.
   - When charging, remains network-capable if visible forecast data is stale.

2. **System JobScheduler (`OpportunisticUpdateJobService`):**
   - Scheduled with `JobScheduler` (Android 8+).
   - Runs roughly every 30 minutes when the system decides it is efficient to do so (piggybacking on other work).
   - Does not require charging or idle state.

3. **AlarmManager UI Updates (`UIUpdateScheduler`):**
   - Uses `setAndAllowWhileIdle()` for updates without a guaranteed strict wakeup.
   - Typically used for interpolated current temperature updates (~15-60 min).

### Network vs. UI-Only Updates
- **Unplugged screen unlock:** UI-only at every battery level. The widget can move the temperature
  curve, update the “Now” indicator, and repair the launcher presentation from cached data.
- **Charging screen unlock:** A stale-data decision may start a network-capable forecast refresh.
- **Manual refresh:** Remains network-capable on battery because it is an explicit user request.
- **Other opportunistic jobs:** Continue to follow their own cache/current-temperature policies;
  they do not inherit a removed screen-unlock battery threshold.

---
*Date: May 25, 2026*
