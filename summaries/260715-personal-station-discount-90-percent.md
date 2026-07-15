# Personal weather station discount: 100% -> 90% (2026-07-15)

## Change

Lowered `DEFAULT_PERSONAL_STATION_DISCOUNT` (Android, `WidgetStateManager.kt`) and
`personalStationDiscount` (desktop, `DesktopConfig.kt`) from `100` to `90`, so personal weather
stations get a 90% discount weight in the actual-temperature IDW blend instead of being ignored
entirely (set to 100 in `ae6af175`).

## Why a code default change wasn't sufficient by itself

`prefs.getInt(KEY, DEFAULT)` / a Kotlin data-class default only apply when no value was ever
explicitly written:

- **Both physical devices (`2A191FDH300PPW`, `RFCT71FR9NT`) and `emulator-5556`** had an explicit
  `<int name="personal_station_discount" value="100" />` in `shared_prefs/widget_state_prefs.xml`
  — almost certainly written when the Settings slider was manually dragged while testing yesterday's
  `ae6af175` change (`SettingsActivity.setupPersonalStationDiscount` only writes on
  `onStopTrackingTouch`, never on load). Changing the `const val` alone would have silently no-op'd
  on these three devices. Patched each device's XML directly (pulled the file, sed'd
  `value="100"` -> `value="90"`, force-stopped the app, pushed it back as the app's UID via
  `run-as`, relaunched).
- `emulator-5554` had the prefs file but no explicit key for this setting, so it already falls
  through to the code default — no device-side edit needed there.
- Desktop's `config.json` (`~/.config/weather-widget/config.json`) had no `personalStationDiscount`
  key either — `DesktopConfigStore`'s `Json { ... }` leaves `encodeDefaults` at its default
  (`false`), so a value matching the code default is simply omitted on write. No file edit needed;
  rebuilding and restarting picked up the new default automatically.

## Gotcha: `adb shell run-as ... sh -c '...'` quoting

Passing `run-as com.weatherwidget sh -c 'cat > file'` as separate Bash-tool arguments fails
silently in a confusing way: Bash consumes the inner quotes before `adb` sees them, so `adb shell`
rejoins bare, unquoted tokens on the remote side — the `>` redirect then belongs to `sh -c cat`'s
*caller* (an un-elevated `adb shell`, cwd `/`) rather than being inside the `sh -c` argument that
runs as the app's UID. Symptom: `No such file or directory` for relative paths, `Permission denied`
for absolute ones. Fix: wrap the entire `run-as ... sh -c '...'` remote command as **one**
double-quoted string passed to `adb -s <device> shell "..."`, preserving the inner quoting through
the extra hop.

## Verification

- `./scripts/buildStart-desktop.sh` — rebuilt and restarted the desktop app; picks up `90` from the
  new default (no explicit `config.json` override).
- `./gradlew installDebug` — installed on all 4 connected devices (`SM-F936U1`, `Pixel 7 Pro`,
  `emulator-5556`, `emulator-5554`) with the new default baked in.
- Re-read each device's `shared_prefs/widget_state_prefs.xml` after the patch to confirm
  `value="90"` before moving on.
