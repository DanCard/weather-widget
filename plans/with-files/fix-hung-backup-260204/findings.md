# Findings - Fix Hung Backup Script

## Investigation - 2026-02-04 16:20
- Script: `scripts/backup_databases.sh`
- Hang point: `adb -s "$DEVICE_ID" shell pm list packages`
- Device: `emulator-5556`
- `adb devices` shows `emulator-5556` is online.
- `adb -s emulator-5556 shell uptime` works but shows high load (41+).
- `timeout 10s adb -s emulator-5556 shell pm list packages` finished quickly with no results in my test.

## Hypotheses
1. **ADB Hang:** ADB might occasionally hang on commands when the device is under high load.
2. **PM Hang:** The `pm` (Package Manager) command on Android can be slow or hang if the system server is struggling.
3. **Infinite Loop:** Unlikely in the script itself, but if `adb` never returns, the script waits forever.

## Verification - 2026-02-04 16:24
- Ran the updated script.
- `RFCT71FR9NT` and `emulator-5554` were backed up successfully.
- `emulator-5556` timed out during the package check as expected (due to high load/unresponsiveness).
- The script continued and finished successfully instead of hanging.
- Device detection correctly found all 3 devices and excluded the one with permission issues.