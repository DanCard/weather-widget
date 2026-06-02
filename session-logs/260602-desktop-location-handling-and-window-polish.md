# Session Log: Desktop Location Handling, ADB Phone GPS, and Window Behavior
**Date:** June 2, 2026
**Status:** Implemented and verified with focused desktop/shared JVM checks

## Goal

Implement real location handling for the Linux desktop weather app and iterate on the desktop
window behavior. The desktop app previously hardcoded Google HQ in `DesktopWeatherService`. This
session added a saved desktop config, phone GPS through ADB, IP/timezone fallback prefill, manual
location picker controls, location diagnostics in the picker, and normal desktop window behavior.

The work was driven by a pre-existing implementation plan in:

```text
plans/260602-Desktop-App—Location-Handling.md
```

and a follow-up plan created during this session:

```text
plans/260602-desktop-location-picker-parallel-acquisition.md
```

## Conversation History & Prompts

1. User provided AGENTS.md instructions for `/home/dcar/projects/weather-widget`.

   The instructions established the project overview, build/test commands, code style, evidence-first
   debug protocol, desktop/widget-specific conventions, ADB device-identification rules, testing
   preferences, and git/session-log conventions. Key session-relevant instructions included:

   1. Use plain `./gradlew ...` by default.
   2. Prefer Robolectric/JVM tests over instrumented tests where possible.
   3. For live widget/API mismatch questions, inspect runtime state and logs before guessing.
   4. Use `~/.Android/Sdk` for ADB/emulator commands on this machine.
   5. Always verify device identity with `getprop` rather than serial shape.
   6. Write detailed session logs with prompts preserved when requested.

2. User prompt:

   ```text
   Start in plan-first mode: inspect first, propose a short plan, and wait for approval before making changes. Do not edit files or run mutating commands until I confirm.
   ```

3. User interrupted the previous turn:

   ```text
   <turn_aborted>
   The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed.
   </turn_aborted>
   ```

4. User prompt:

   ```text
   Implement plans/260602-Desktop-App—Location-Handling.md
   ```

5. User prompt:

   ```text
   The plan is to use attached phone location.  If that is not available then try option #2.  That didn't happen.  Also I would like the app to to display a log while it is trying to fetch location from phone.  If there are multiple devices connected should try real phones not emulators.
   ```

6. User prompt:

   ```text
   The log disappeared before I could tell if it worked.  I see a location picker.  Does that mean the fetch location from phone didn't work?  Don't show the location picker if fetch location from phone worked.
   ```

7. User interrupted the previous turn:

   ```text
   <turn_aborted>
   The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed.
   </turn_aborted>
   ```

8. User prompt:

   ```text
   Make the log part of the location picker window.
   ```

9. User interrupted the previous turn:

   ```text
   <turn_aborted>
   The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed.
   </turn_aborted>
   ```

10. User prompt:

    ```text
    Don't hide the manual location picker while attempting to retrieve location from phone.
    ```

11. User interrupted the previous turn:

    ```text
    <turn_aborted>
    The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed.
    </turn_aborted>
    ```

12. User prompt:

    ```text
    Put the log at the bottom.
    ```

13. User interrupted the previous turn:

    ```text
    <turn_aborted>
    The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed.
    </turn_aborted>
    ```

14. User prompt:

    ```text
    Why not have two parallel phone acquistion paths?
    ```

15. User prompt:

    ```text
    The location via adb won't contradict.  It will close the picker if it succeeds.  If it doesn't succeed the other option is available.
    ```

16. User interrupted the previous turn:

    ```text
    <turn_aborted>
    The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed.
    </turn_aborted>
    ```

17. User prompt:

    ```text
    Also run in parallel the location via ip lookup.
    ```

18. User prompt:

    ```text
    write plan to plans/ dir and implement
    ```

19. User prompt:

    ```text
    If I reside the window to be taller, I expect the log text box to grow larger.
    ```

20. User interrupted the previous turn:

    ```text
    <turn_aborted>
    The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed.
    </turn_aborted>
    ```

21. User prompt:

    ```text
    If I resize the window to be taller, I expect the log text box to grow larger.
    ```

22. User prompt:

    ```text
    Are you able to look at the logs?  Why is fetching location from attached phones not working?
    ```

23. User prompt:

    ```text
    Why reject as stale?
    ```

24. User prompt:

    ```text
    I don't think gps is 12days stale.  Something else is the issue.  Is there a way to collect info why it says gps is 12 days stale?  The phone is active and has been used.
    ```

25. User prompt:

    ```text
    do it
    ```

26. User prompt:

    ```text
    adb location lookup still failing says timeout
    ```

27. User prompt:

    ```text
    how to have the app exit cleanly?
    ```

28. User interrupted the previous turn:

    ```text
    <turn_aborted>
    The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed.
    </turn_aborted>
    ```

29. User prompt:

    ```text
    how to have the desktop app exit cleanly?
    ```

30. User prompt:

    ```text
    do it
    ```

31. User prompt:

    ```text
    should the app have a standard system title to be able to close and minimize?
    ```

32. User prompt:

    ```text
    I'd like to be able to resize the widget
    ```

33. User prompt:

    ```text
    I hit the x on the system title bar but the widget didn't exit, is that expected?
    ```

34. User prompt:

    ```text
    write a very detailed session log to session-logs/ dir, include all prompts.
    ```

## Implementation Summary

### 1. Shared HTTP geolocation APIs

Added no-key geolocation clients to `:shared`, following the existing `(HttpClient, Json)` API-client
pattern from `NwsApi` and `OpenMeteoApi`.

1. `NominatimApi`
   - Supports search by address, ZIP, or city via `/search`.
   - Supports reverse geocoding via `/reverse`.
   - Sends an identifying `User-Agent`.
   - Returns `GeocodeResult(displayName, lat, lon)`.

2. `IpGeolocationApi`
   - Uses `https://ipapi.co/json/`.
   - Maps IP lookup response to `IpLocation(lat, lon, city, region, country)`.

3. Added shared JVM test dependencies:
   - JUnit
   - Ktor mock client

### 2. Desktop config and location sources

Added desktop-only location/config classes:

1. `DesktopConfig`
   - Serializable config storing `lat`, `lon`, `label`, and `source`.
   - Stored at `${XDG_CONFIG_HOME:-$HOME/.config}/weather-widget/config.json`.

2. `TimezoneLocator`
   - Reads `/usr/share/zoneinfo/zone1970.tab`.
   - Parses ISO-6709 coordinates such as `+4042-07400` and `+404251-0740023`.
   - Used only as an offline coarse fallback after IP lookup fails.

3. `PhoneLocator`
   - Enumerates ADB devices.
   - Skips emulators.
   - Targets real phones with `adb -s <serial> ...`.
   - Reads `dumpsys location` and prefers `gps`, then `fused`.
   - Initially misinterpreted `Location[...] et=...` as fix age; later corrected it to elapsed
     realtime timestamp.

4. `LocationResolver`
   - Coordinates phone, IP, timezone, Nominatim search, and manual coordinate reverse lookup.
   - Defines the freshness gate: phone fixes are fresh if computed age is under 24 hours.
   - Produces `ResolvedLocation`, which converts to `DesktopConfig`.

### 3. Initial desktop location picker flow

Changed `Main.kt` and `DesktopWeatherService.kt` so the desktop app no longer uses Google HQ as the
normal location source:

1. `DesktopWeatherService`
   - Now accepts coordinates from `DesktopConfig`.
   - Keeps Google HQ only as an absolute fallback.

2. `Main.kt`
   - Loads `DesktopConfig` on startup.
   - If config exists, opens the weather popup.
   - If config is missing, opens the location picker.
   - Adds tray action: `Update location...`.

3. `LocationPicker`
   - Shows IP/timezone suggestion.
   - Supports address/ZIP/city search through Nominatim.
   - Supports manual latitude/longitude entry.
   - Supports connected-phone GPS.

### 4. Phone-first behavior and real-device selection

The user clarified that the intended order was:

1. Try attached phone location.
2. If phone location is not available, use fallback options.

The first implementation did not behave well with multiple devices. Corrections:

1. `PhoneLocator` now enumerates `adb devices`.
2. It skips emulator serials such as `emulator-5554`.
3. It uses `adb -s <real-phone-serial>` to avoid ambiguous multi-device ADB commands.
4. It logs which real phone is being tried.

The physical devices present during investigation were:

```text
2A191FDH300PPW  Pixel 7 Pro
RFCT71FR9NT     Samsung SM-F936U1
```

### 5. Location picker logging and parallel acquisition

The user requested that the log be part of the picker window, that manual controls remain visible
while phone lookup runs, and that IP lookup run in parallel. The final picker behavior:

1. With no saved config, the location picker opens immediately.
2. Phone GPS lookup starts in a background coroutine.
3. IP lookup/timezone fallback starts in another background coroutine.
4. Manual address search and coordinate entry remain visible and usable.
5. The phone/IP progress log appears at the bottom of the picker.
6. If phone lookup returns a fresh fix, the picker auto-saves it and closes.
7. If phone lookup fails or returns stale, the picker remains open.
8. IP lookup only pre-fills fields; it does not auto-save.

Created the follow-up plan artifact:

```text
plans/260602-desktop-location-picker-parallel-acquisition.md
```

### 6. Resizable picker log

The user noticed that resizing the picker taller should grow the bottom log text box. The original
implementation used a fixed `120.dp` height and a spacer consumed the extra height.

Fix:

1. Removed the fixed height from the log `LazyColumn`.
2. Removed the weighted spacer above the log.
3. Applied `Modifier.weight(1f)` to the log list so extra vertical space goes to the log panel.

### 7. ADB path investigation

When phone lookup failed, live inspection showed:

```bash
/home/dcar/.Android/Sdk/platform-tools/adb devices -l
```

worked and showed:

```text
2A191FDH300PPW         device ... model:Pixel_7_Pro
RFCT71FR9NT            device ... model:SM_F936U1
emulator-5554          device ...
emulator-5556          device ...
```

but:

```bash
which adb
```

returned nothing. The shell `PATH` did not include:

```text
/home/dcar/.Android/Sdk/platform-tools
```

Therefore `PhoneLocator`, which originally ran plain `adb`, could fail when the desktop app launched
without ADB on `PATH`.

Fix:

1. Try plain `adb`.
2. Fall back to `${user.home}/.Android/Sdk/platform-tools/adb`.
3. Also try `ANDROID_HOME/platform-tools/adb`.
4. Also try `ANDROID_SDK_ROOT/platform-tools/adb`.
5. Log the selected ADB executable.

### 8. Correcting the stale GPS calculation

The user challenged the stale-fix explanation because the active phone clearly should not have a
12-day-stale GPS fix. Live evidence confirmed the user's concern.

Initial mistaken interpretation:

```text
Location[gps ... et=+12d22h56m41s540ms]
```

was treated as "fix age is 12 days".

Correct interpretation:

`et=` is the location's elapsed-realtime timestamp, not its age. The age must be computed as:

```text
phone_current_uptime - location_elapsed_realtime_timestamp
```

Evidence collected:

Pixel:

```text
/proc/uptime: 1119474.41 seconds
gps et:       ~1119401.54 seconds
real age:     ~73 seconds
```

Samsung:

```text
/proc/uptime: 67019.32 seconds
gps et:       ~66947.51 seconds
real age:     ~72 seconds
```

Fix:

1. Read `/proc/uptime` per phone.
2. Parse `et=` as elapsed duration.
3. Compute `fixAgeMillis = uptimeMillis - elapsedRealtimeMillis`.
4. Keep the 24-hour freshness rule, but apply it to the corrected age.
5. Log the computed age, e.g. `found gps fix (1m 12s old)`.

### 9. ADB timeout investigation and fix

After the age fix, the user reported:

```text
adb location lookup still failing says timeout
```

Manual command timing showed ADB itself was fast:

```bash
adb version
adb devices
adb -s <serial> shell cat /proc/uptime
adb -s <serial> shell dumpsys location
```

The full `dumpsys location` commands completed in about 0.1 seconds in the terminal.

Root cause:

`ProcessCommandRunner` started the process, waited for it to exit, and only then read stdout. For
large output like Samsung `dumpsys location`, the stdout pipe can fill. Then:

1. `dumpsys` blocks waiting for the parent process to read output.
2. The desktop app blocks waiting for `dumpsys` to exit.
3. The command hits the timeout.

Fix:

1. Start a background reader thread immediately after launching the process.
2. Drain stdout while the process is running.
3. Keep the timeout guard.
4. Join the reader thread after the process exits or is killed.

### 10. Clean desktop app exit

The user asked how to exit cleanly and then asked to implement it.

Issue:

`DesktopClients` owns a long-lived Ktor `HttpClient`, but the tray `Quit` action called
`exitApplication()` directly.

Fix:

1. Add `DesktopClients.close()`.
2. Add local `quit()` function in `main()`.
3. `quit()` closes `DesktopClients` and then calls `exitApplication()`.
4. Tray `Quit` now calls `quit()`.

The weather window `X` remains a hide-window action, not full app exit.

### 11. Standard title bar and widget resizing

The user asked whether the app should have a standard system title bar and later said they wanted to
resize the widget.

Initial weather popup behavior:

```kotlin
undecorated = true
alwaysOnTop = true
```

This removed the system title bar and standard resize controls.

Fix:

1. Removed `undecorated = true`.
2. Removed `alwaysOnTop = true`.
3. The weather window now uses the standard system frame, with normal close/minimize/resize controls.

The user then observed that clicking the title-bar `X` did not exit. That is expected because:

```kotlin
onCloseRequest = { popupVisible = false }
```

The `X` hides the weather window while the app remains in the system tray. Full exit is via tray
`Quit`.

## Key Files Added or Modified

### Shared module

1. `shared/src/main/kotlin/com/weatherwidget/data/remote/NominatimApi.kt`
2. `shared/src/main/kotlin/com/weatherwidget/data/remote/IpGeolocationApi.kt`
3. `shared/src/test/kotlin/com/weatherwidget/data/remote/NominatimApiTest.kt`
4. `shared/src/test/kotlin/com/weatherwidget/data/remote/IpGeolocationApiTest.kt`
5. `shared/build.gradle.kts`

### Desktop module

1. `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopConfig.kt`
2. `desktop/src/main/kotlin/com/weatherwidget/desktop/TimezoneLocator.kt`
3. `desktop/src/main/kotlin/com/weatherwidget/desktop/PhoneLocator.kt`
4. `desktop/src/main/kotlin/com/weatherwidget/desktop/LocationResolver.kt`
5. `desktop/src/main/kotlin/com/weatherwidget/desktop/LocationPicker.kt`
6. `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt`
7. `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt`
8. `desktop/src/test/kotlin/com/weatherwidget/desktop/TimezoneLocatorTest.kt`
9. `desktop/src/test/kotlin/com/weatherwidget/desktop/PhoneLocatorTest.kt`
10. `desktop/build.gradle.kts`

### Plan artifacts

1. `plans/260602-Desktop-App—Location-Handling.md`
2. `plans/260602-desktop-location-picker-parallel-acquisition.md`

### Session artifact

1. `session-logs/260602-desktop-location-handling-and-window-polish.md`

## Verification Performed

Focused desktop/shared tests and builds were run repeatedly after each implementation step.

Commands that passed:

```bash
./gradlew :shared:test :desktop:test
./gradlew :shared:build :desktop:build
./gradlew :desktop:test :shared:test
./gradlew :desktop:build :shared:build
```

The final verification after the window-resize/title-bar change passed:

```bash
./gradlew :desktop:test :shared:test
./gradlew :desktop:build :shared:build
```

Live ADB evidence gathered:

```bash
/home/dcar/.Android/Sdk/platform-tools/adb devices -l
/home/dcar/.Android/Sdk/platform-tools/adb -s 2A191FDH300PPW shell cat /proc/uptime
/home/dcar/.Android/Sdk/platform-tools/adb -s RFCT71FR9NT shell cat /proc/uptime
/home/dcar/.Android/Sdk/platform-tools/adb -s 2A191FDH300PPW shell dumpsys location
/home/dcar/.Android/Sdk/platform-tools/adb -s RFCT71FR9NT shell dumpsys location
```

Important runtime findings:

1. Plain `adb` was not available on `PATH`.
2. Full SDK-path ADB worked.
3. Both physical phones exposed GPS/fused last locations.
4. `et=` in `Location[...]` is elapsed realtime timestamp, not age.
5. `dumpsys location` is fast in the terminal but large enough to deadlock the old
   wait-before-read process runner.

## Open / Deferred Items

1. Live GUI verification of the final picker and weather window behavior was not performed in this
   final logging turn.
2. The weather window title-bar `X` hides the window instead of exiting the tray app. This is current
   expected behavior, but can be changed if the desired model becomes "close means quit".
3. The desktop app still uses a solid color tray icon placeholder.
4. The weather popup is now a normal resizable window; further sizing polish may be useful if the
   graph/header layout needs minimum-size constraints.

## Final State Notes

At the time this log was written, the worktree contained the desktop location implementation,
follow-up plans, tests, and this session log. The most recent verification commands had passed.
