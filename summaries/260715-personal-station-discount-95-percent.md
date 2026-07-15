# Personal weather station discount: 90% -> 95% (2026-07-15)

Supersedes `260715-personal-station-discount-90-percent.md` (same day, earlier session).

## Change

Raised `DEFAULT_PERSONAL_STATION_DISCOUNT` (Android, `WidgetStateManager.kt`) and
`personalStationDiscount` (desktop, `DesktopConfig.kt`) from `90` to `95`. PWS blend weight in the
actual-temperature IDW blend drops from `0.10` to `0.05`
(`personalStationWeight() = 1.0 - discount/100`).

No test pinned the old default, so the constant change was clean.

## Device state found (not what the earlier summary implies)

| Device | Stored before | Now |
|---|---|---|
| Pixel 7 Pro (`2A191FDH300PPW`) | 90 | 95 |
| `emulator-5554` | 90 | 95 |
| Samsung SM-F936U1 (`RFCT71FR9NT`) | **100** | 95 |
| Desktop | key absent -> 90 | key absent -> 95 |

The Samsung was at **100**, not 90 — even though the earlier session's summary records patching it to
90. So it was set back to 100 via the Settings slider afterwards, i.e. a deliberate "ignore PWS
entirely" choice. Confirmed with the user before overwriting rather than assuming it was drift.

`emulator-5554` had *no* explicit key per the earlier summary but had one (`90`) today — the earlier
session's `installDebug` + slider activity presumably wrote it. Do not trust a previous summary's
claim about which devices have an explicit key; re-read the XML.

## Why the code default alone moves nothing on Android

`prefs.getInt(KEY, DEFAULT)` only applies when no value was ever written, and
`SettingsActivity.setupPersonalStationDiscount` writes on `onStopTrackingTouch`. All three devices
had an explicit `<int name="personal_station_discount" .../>`, so the constant change was a no-op for
every one of them. Patched each directly: force-stop -> pull XML via `run-as` -> edit -> push back as
the app's UID -> relaunch.

**Force-stop is required**: a live process holds SharedPreferences in memory and will clobber an edit
made underneath it. **Relaunch is also required**: a force-stopped package stays in a stopped state
and its widgets go dark until an explicit start (`monkey -c LAUNCHER` clears it; verified
`stopped=false` per device afterwards).

Desktop is the opposite case: `DesktopConfigStore` serializes with `encodeDefaults = false`, so a
field equal to the default is never written. `config.json` has no `personalStationDiscount` key —
it stores "follow the default", not `90` — so rebuilding picked up `95` automatically.

## Gotcha: quoting through the two shell hops

`adb shell run-as com.weatherwidget sed -i 's/.../.../' file` fails (`sed: bad pattern`) — the outer
shell eats the inner quotes before `adb` sees them. Same trap as the earlier summary records for
`sh -c`. Robust path used here instead: pull the file, edit locally in Python (assert exactly 1
substitution + re-parse the XML to prove well-formedness), then write back with the whole remote
command as **one** double-quoted string:

```
adb -s $S shell "run-as com.weatherwidget sh -c 'cat > $P'" < local.xml
```

## Verification

- Re-read every device's XML after patching: all `value="95"`, all `stopped=false`.
- `./scripts/buildStart-desktop.sh` — rebuilt/restarted; healthy (launcher + ui procs). `config.json`
  still has no discount key, so it inherits `95`.
- `:desktop:test` and `:app` tests for `WidgetStateManager` / `Settings` / `CurrentTempResolver` /
  `ActualTemperature` all pass.
- Screenshotted both physical devices: Pixel 7-day view and Samsung hourly graph both rendering live
  data — no dark-widget regression from the force-stops.
