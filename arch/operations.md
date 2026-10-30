# Operations and runtime verification

Use these procedures for live debugging, preference-only changes, desktop operation, and releases.
Follow the safety rules in [AGENTS.md](../AGENTS.md): do not uninstall/clear app data or cancel a
running widget worker without explicit authorization.

## Device and emulator evidence

The Android SDK on the development machine is under `~/.Android/Sdk`; desktop code also checks its
standard SDK environment variables when `adb` is not on `PATH`.

```bash
ADB="$HOME/.Android/Sdk/platform-tools/adb"
$ADB devices
$ADB -s <serial> shell "getprop ro.product.manufacturer && getprop ro.product.model"
$ADB -s <serial> logcat
```

Do not infer a phone model from its serial. For a safe SDK query across attached devices:

```bash
$ADB devices | sed '1d;/^$/d' | while IFS=$'\t' read -r serial state; do
  if [ "$state" = "device" ]; then
    sdk=$($ADB -s "$serial" shell getprop ro.build.version.sdk </dev/null 2>/dev/null | tr -d '\r')
    echo "$serial => sdk=${sdk:-<empty>}"
  else
    echo "$serial => state=$state (not queried)"
  fi
done
```

For live widget questions, inspect a running emulator first, then connected devices; start an
available emulator only if neither is available. Common installed targets are
`Generic_Foldable_API36` and `Medium_Phone_API_36`. Capture screenshots, renderer-specific logcat,
widget preferences, and database state before diagnosing visual/data mismatches. Do not ask for the
location when runtime state can reveal it.

When an emulator widget looks wrong but the app logged a good paint, run
`scripts/capture-launcher-state.sh` before pressing HOME or restarting the launcher. It saves a
screenshot, the launcher's live view tree, and a bugreport for every running emulator under
`captures/`; use `-s SERIAL` for one target.

`adb` warnings can corrupt redirected PNG output on some setups. Convert before viewing:

```bash
$ADB -s <serial> exec-out screencap -p > /tmp/weather-screen.png
convert /tmp/weather-screen.png /tmp/weather-screen.jpg
```

Use focused instrumented runs through `scripts/emulator-tests.sh -c <fully.qualified.TestClass>` so
an attached physical phone is not included accidentally. Keep emulators running unless the user asks
otherwise. See [testing strategy](testing-strategy.md) for the full test commands.

### Slow source switches and desktop SQLite lock stalls

A source switch (API button, daily home button) logs `SOURCE_SWITCH_E2E` on both platforms:
`phase=critical … persistMs= paintMs= criticalMs=` as soon as the new source is painted, then
`phase=done … refresh=<QUEUED|FRESH|DELEGATED|SKIPPED|FAILED> deferredMs=`, also stored in `app_logs`.
A missing `phase=done` line after a `phase=critical` line means the deferred phase is stuck. Desktop
`paintMs` is ~0: Compose repaints from the saved config, and the daemon's reload follows
(`LAUNCH_REFRESH_CHECK reason=source_change`). Desktop therefore adds
`phase=data_ready sinceSwitchMs=` once the UI holds the new source's data, about 600 ms normally.

Desktop breadcrumbs written from code that must not wait on the database (`CURR_TEMP_RESULT`,
`CURRENT_STATUS_PUBLISH`, `CONFIG_SAVE`, refresh notices, `data_ready`) go through
`DesktopAppLogQueue`: one `app-log-writer` thread per process, rows stamped when logged. Written inline,
a `CURR_TEMP_RESULT` row held both processes' source-change reloads for the 5 s busy timeout.

The desktop daemon and UI processes write one WAL database, so a write can wait up to the 5 s busy
timeout on the other process's transaction. Every desktop connection is timed and reports
`DB_WRITE_SLOW` to the console log (`~/.local/state/weather-widget/autostart-*.log`) only, never to
`app_logs`:

- `kind=autocommit caller=<Dao.method> ms=`: a single write that waited 200 ms or more (the victim).
- `kind=txn caller= waitMs= holdMs=`: a transaction that waited 200 ms or more for the lock, or held
  it 500 ms or more (a culprit). sqlite-jdbc re-begins the IMMEDIATE transaction inside `commit()`,
  so `holdMs` runs until the connection closes.
- `kind=open ms=`: opening a connection took 200 ms or more.
- `outcome=failed`: the write gave up, usually `SQLITE_BUSY` after the full 5 s timeout. It is logged
  whatever its duration.

`caller=` lists the DAO method and the two app frames above it, for example
`DesktopWeatherDao.log<-DaemonProcessKt.runDaemon$lambda$2<-CurrentTemperatureResolver.appLog`.
Match `pid=`/`thread=` between a victim and the culprit that overlaps it. To reproduce a stall, hold
the lock while switching source in the popup:

```bash
sqlite3 ~/.local/share/weather-widget/weather.db "BEGIN IMMEDIATE; SELECT 1;" ".shell sleep 8" "COMMIT;"
```

### Debug-build adb hooks

Debug APKs carry two receivers in `app/src/debug` (absent from release), exported so `adb shell` can
reach them and gated on `android.permission.DUMP` so other apps cannot:

```bash
# Exactly the widget's Refresh tap: syncs only stale sources ("up to date" banner otherwise).
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.REFRESH \
  -n com.weatherwidget/com.weatherwidget.debug.DebugSyncReceiver
# Forced full sync of every enabled source (billed): SYNC_START force=true reason=debug_full_sync.
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.FULL_SYNC \
  -n com.weatherwidget/com.weatherwidget.debug.DebugSyncReceiver
# Forwarded widget API toggle tap for interaction latency tests.
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.TOGGLE_API \
  --ei appWidgetId <id> -n com.weatherwidget/com.weatherwidget.debug.DebugSyncReceiver
# Location override for the passive background read (coordinates as strings), and its reset.
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.SET_LOCATION \
  -n com.weatherwidget/com.weatherwidget.debug.DebugLocationReceiver --es lat 37.7749 --es lon -122.4194
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.CLEAR_LOCATION \
  -n com.weatherwidget/com.weatherwidget.debug.DebugLocationReceiver
```

`FULL_SYNC` enqueues through `WidgetWorkScheduler.enqueueForcedSync` with KEEP, so it never cancels a
running worker. Prefer an emulator; a forced sync on a phone spends its provider quotas.

If launcher resize handles bounce back on an emulator, move the widget away from a constrained top
row/edge and retry. Verify whether `onAppWidgetOptionsChanged` fired before treating it as a renderer
bug.

## Enable or disable a forecast source without code changes

Do not change `WeatherSource`, source ordering, or defaults for a user's runtime preference.

| Platform | Store | Key |
|---|---|---|
| Android | SharedPreferences `widget_state_prefs` | `visible_sources_order` (CSV of IDs) |
| Desktop | `${XDG_CONFIG_HOME:-$HOME/.config}/weather-widget/config.json` | `settings.visibleSources` |

Display-source IDs are `NWS`, `OPEN_METEO`, `SILURIAN`, `TOMORROW_IO`, `WEATHER_API`,
`OPEN_WEATHER_MAP`, and `GOOGLE_WEATHER`. METAR and Synoptic are actuals feeds, not display sources.
Visual Crossing is not configurable.

Fresh-install defaults are `NWS`, `OPEN_METEO`, `SILURIAN` (`WeatherSourceOrdering.DEFAULT_VISIBLE_IDS`).
Android debug builds add `TOMORROW_IO`, and `GOOGLE_WEATHER` first only when the build baked a Google
key (`DefaultVisibleSources.forBuild`). A debug build bakes in `GOOGLE_WEATHER_API_KEY` from the
builder's gitignored `local.properties` or environment, or `""` when neither is set; release builds
always get `""`. A keyless debug build therefore neither defaults to Google nor runs the one-time
Google-primary migration. Release users can enable Google with their own key. Enabling
`GOOGLE_WEATHER` makes it primary (`PRIMARY_ON_ENABLE`); every other newly enabled source is appended.

NWS stays in the stored list everywhere. Outside NWS coverage, `SourceCoverage.effectiveSources`
filters it out each time the list is read, and falls back to Open-Meteo if nothing usable remains.
Neither change is ever written back. So a missing NWS abroad is expected, not a preference to edit;
do not remove NWS from the stored list, or it stays off after the user returns to the U.S.

1. Force-stop Android (`$ADB -s <serial> shell am force-stop com.weatherwidget`) or stop the desktop
   app so an in-memory save cannot overwrite the edit.
2. Edit the enabled list, using `run-as com.weatherwidget` for Android preferences. Keep at least one
   source enabled.
3. Reopen Android Settings or restart desktop.

API keys, actuals-provider choices, and a widget's current display source are independent preferences.
Do not clear them merely to disable a source.

## WeatherWidgetWorker native-crash trap

Immediate/expedited unique work must use `KEEP` or `APPEND_OR_REPLACE`, never `REPLACE`. Use
`ExistingPeriodicWorkPolicy.UPDATE` for periodic work. `REPLACE` is reserved for delayed work that
has not started. Cancelling a running worker can resume its coroutine into an ART native crash,
which never reaches the JVM crash logger.

Diagnose with `PROC_EXIT` rows from `ProcessExitLogger` and native tombstones:

```bash
$ADB -s <serial> shell dumpsys dropbox --print
```

A `SYNC_CANCELLED stopReason=1` followed by `PROC_EXIT reason=CRASH_NATIVE` is the characteristic
sequence. Relevant Dropbox tags include `data_app_native_crash` and `SYSTEM_TOMBSTONE`.

If a crash loop poisons WorkManager's persisted queue, preserve the weather database and clear only
the WorkManager DB on a debuggable build:

```bash
$ADB -s <serial> shell am force-stop com.weatherwidget
$ADB -s <serial> shell run-as com.weatherwidget rm -f no_backup/androidx.work.workdb no_backup/androidx.work.workdb-shm no_backup/androidx.work.workdb-wal
```

Do not substitute `pm clear`. After changing a database writer, deploy the new APK/desktop binary
before scrubbing bad rows; otherwise the old writer can recreate them on its next fetch.

## Desktop operation

Build and run the repo-local distribution used by daily autostart:

```bash
./gradlew :desktop:createDistributable
scripts/buildStart-desktop.sh
```

For development without the distributable wrapper, use `./gradlew :desktop:run`. Autostart runs
`scripts/desktop-app-launcher-and-autostart.sh`, which launches
`desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop` and builds
it once if missing. Packaging requires a full JDK with `jpackage`; Android Studio's JBR is insufficient.

The long-lived daemon owns storage, fetch loops, Linux integration, tray/IPC, and the genmon socket.
Compose windows run in an ephemeral UI process; closing the last window does not necessarily stop the
daemon. Last-launch-wins uses `.quit-<launchId>` files under
`~/.local/share/weather-widget/`: a new launch removes old tokens and writes its own, and older
instances detect it through file watching with polling fallback. Brief tray overlap is expected.

Desktop configuration is at `${XDG_CONFIG_HOME:-$HOME/.config}/weather-widget/config.json`; weather
and trigger/socket data are under `${XDG_DATA_HOME:-$HOME/.local/share}/weather-widget/`. The genmon
client is `genmon/genmon-weather-bin`, built with `make -C genmon`; it reads cached Pango markup from
`weather.sock` rather than querying the database.

## Release commands

Fastlane is installed at `~/.local/share/gem/ruby/3.3.0/bin/fastlane` on the development machine:

```bash
FASTLANE="$HOME/.local/share/gem/ruby/3.3.0/bin/fastlane"
$FASTLANE beta
$FASTLANE run upload_to_play_store track:beta track_promote_to:production version_code:<VERSION> changes_not_sent_for_review:false
```

Desktop APT publication is documented in [APT_REPO.md](../docs/APT_REPO.md). Release signing,
provider-key validation, and Play service-account setup remain in the build/Fastlane configuration;
do not copy secrets into documentation.
