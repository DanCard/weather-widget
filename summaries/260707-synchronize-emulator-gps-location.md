# Synchronize Emulator GPS Location with Physical Samsung Phone via ADB

## Context
The goal was to set the Android emulator's location to fixed GPS coordinates retrieved from a connected physical Samsung phone (`SM-F936U1` / `RFCT71FR9NT`) via ADB. Additionally, we addressed an issue on `emulator-5554` where the Settings screen was displaying stale coordinates (Austin, TX) instead of the synced phone location.

---

## What Was Done

### 1. Created ADB Location Synchronization Script
We wrote a Python utility script, [set_emulator_gps.py](file:///home/dcar/projects/weather-widget/scripts/set_emulator_gps.py), that performs the following steps automatically:
- **Device Discovery**: Calls `adb devices` and runs `getprop ro.product.manufacturer` to identify the Samsung device.
- **Coordinate Extraction**: Parses the latest location coordinates from the Samsung phone's `dumpsys location` dump, prioritizing the `gps` provider.
- **Dual-Method Coordinates Injection**:
  - Sends the traditional `adb emu geo fix <longitude> <latitude>` to update the emulator's virtual QEMU GPS hardware.
  - Grants mock location permissions (`appops set 2000 android:mock_location allow`) to the ADB shell user, sets up mock `gps`/`fused` test providers, and sets their mock location directly using `cmd location`. This ensures the system `LocationManager` updates immediately.

### 2. Committed and Pushed Changes
Staged, committed, and pushed the new sync script along with earlier local changes:
- Commit Subject: `Add script to synchronize emulator locations with Samsung phone`
- Pre-push check ran the entire unit test suite (`./gradlew :app:testDebugUnitTest --rerun-tasks`) to verify all 100+ tests pass successfully.
- Pushed to the remote repository.

### 3. Resolved Location Discrepancy on Emulator 5554 Settings Screen
We found that `emulator-5554` had its location mode set to `fixed` (pinned) with widgets hardcoded to Austin, TX coordinates in a prior test. This prevented them from auto-healing/updating when the GPS location changed.
- Created a scratch script, `fix_emulator_prefs.py`, that:
  - Force-stops the `com.weatherwidget` process to prevent concurrent file locks.
  - Overcomes Android SELinux write restrictions on `shared_prefs` by writing the updated XML files to the app's standard `files/` directory first, and then copying them to `shared_prefs/` within the `run-as` shell context.
  - Modifies `location_mode` to `follow_device`.
  - Sets the widget coordinates (`52` and `53`) directly to the synced Googleplex coordinates (`37.416797`, `-122.089`).
  - Broadcasts `com.weatherwidget.ACTION_REFRESH` to paint the changes and restarts `SettingsActivity`.

---

## Verification
- Verified on both `emulator-5554` and `emulator-5556` using `dumpsys location` that coordinates were updated.
- Verified on `emulator-5554` Settings screen that the description updated correctly to:
  **`Widget Location: 37.4168, -122.0890 • Follows device`**
