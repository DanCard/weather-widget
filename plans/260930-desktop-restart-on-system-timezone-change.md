# Desktop: restart when the system timezone changes

## Problem

2026-09-30 22:36:56 `systemd-timedated` changed the zone Europe/Warsaw → America/Los_Angeles. Both
running desktop JVMs (daemon pid 3453, UI pid 25636, started 22:08 / 22:35) still reported
`user.timezone=Europe/Warsaw` via `jcmd VM.system_properties`. The JVM resolves the default zone
once and caches it for the life of the process, so every "today", day column, hour label, NOW
marker and current-temp interpolation was 9 h off until a manual relaunch. Nothing in `:desktop` or
`:shared` watched for a zone change.

Android does not have this problem in the same form: the framework itself resets the default
`TimeZone` on `ACTION_TIMEZONE_CHANGED`.

## Fix

Restart, don't patch in place. `TimeZone.setDefault` would leave zone-derived state behind
(memoized blend series, captured `LocalDate`s, formatters) and would also have to reach the
separate UI process. A relaunch reuses the last-launch-wins path and gets everything right by
construction.

- `SystemTimeZoneWatch` (`:desktop`, pure + testable):
  - `readSystemZoneId(localtime, timezoneFile)`: the zone ID `/etc/localtime` links to (the part
    after `zoneinfo/`), else `/etc/timezone`'s content, else null (copied file, unreadable → no-op).
  - `shouldRestartForZoneChange(baseline, current, jvmZone)`: true only when the system zone
    **changed since this process started** (baseline read at start) AND its rules differ from the
    JVM's current zone. Comparing against the baseline, never the JVM's own ID directly, is what
    prevents a restart loop when the two name the same zone differently (`US/Pacific` vs
    `America/Los_Angeles`) or the JVM resolved something else entirely.
  - Disabled when `TZ` is set in the environment (the JVM uses it; `/etc/localtime` is irrelevant).
- Daemon heartbeat (30 s, already resume-aware) runs the check. On a change: log
  `TIMEZONE_CHANGE` to `app_logs`, spawn a successor daemon (same executable, full env), then
  `quit(killUi = true)` so the UI's stale JVM goes too. If the UI was alive, the successor gets
  `reopen-ui` and spawns a fresh UI on start, so an open popup comes back instead of vanishing.
  If the launcher is missing or spawning fails, log and stay up (wrong-zone beats no app).
- **Quit only after the successor takes over**, i.e. once its `.quit-<launchId>` token appears
  (`SUCCESSOR_TAKEOVER_TIMEOUT_MS` = 20 s); on timeout or successor exit, log `restart_failed` and
  stay up. While handing off, the existing newer-instance quit paths also kill the UI.

## Incident during verification (2026-09-30 23:48)

The first real round trip logged `action=restart` and left **no app running**: the successor was
spawned with `--reopen-ui`, and the jpackage launcher passed it to the JVM ("Unrecognized option:
--reopen-ui"), while the predecessor had already quit on spawn. Renaming to a dashless `reopen-ui`
was a misdiagnosis: the second attempt (01:51:17, → New York) failed with "Could not find or load
main class reopen-ui" — but this time the takeover wait kept the old daemon up.

Real cause, reproduced from a shell: the jpackage launcher sets `_JPACKAGE_LAUNCHER` in its own env
before loading the JVM, the successor inherited it, and with it set the launcher behaves as plain
`java`. `launchUiProcess` only escapes this because it clears the env. Fix: remove that variable in
`launchSuccessorDaemon`.

## Tests

- `SystemTimeZoneWatchTest`: symlink parsing (absolute, relative, `posix/` prefix), `/etc/timezone`
  fallback, non-symlink → null; restart decision: no change, alias change (same rules), real change,
  unknown current, JVM already matching.
- Manual: `timedatectl set-timezone` round trip on this machine; within ~30 s a new daemon pid
  with the new `user.timezone`, and a `TIMEZONE_CHANGE` row in `app_logs`.

## Android

The framework resets the process default zone on `ACTION_TIMEZONE_CHANGED`, and every render reads
`ZoneId.systemDefault()` per call (no class-level captures in the paint path), so only the trigger
was missing: the widget kept the old "today"/NOW until the next scheduled paint (up to an hour).

- Manifest: `TIMEZONE_CHANGED` on `WeatherWidgetProvider` (exempt from the implicit-broadcast ban,
  like the existing `LOCALE_CHANGED`).
- `WeatherWidgetProvider.isCacheRepaintBroadcast`: both actions take the existing
  `renderAllWidgetsFromCache` path (full render, not the `GraphRepaintGate` UI-only path); a zone
  change also logs `TIMEZONE_CHANGE action=repaint zone=…` to `app_logs`.
- Test: `WeatherWidgetProviderTimezoneChangeRoboTest` — manifest registration via
  `queryBroadcastReceivers` (shown to fail with the manifest line removed) + the predicate.
- Verified on `emulator-5554`: `cmd alarm set-timezone Asia/Tokyo` → repaint 0.7 s later, header
  "Thu 1" with Wed as yesterday; back to America/Los_Angeles → "Wed 30" within 1 s.
