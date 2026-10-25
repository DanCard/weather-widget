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

`adb` warnings can corrupt redirected PNG output on some setups. Convert before viewing:

```bash
$ADB -s <serial> exec-out screencap -p > /tmp/weather-screen.png
convert /tmp/weather-screen.png /tmp/weather-screen.jpg
```

Use focused instrumented runs through `scripts/emulator-tests.sh -c <fully.qualified.TestClass>` so
an attached physical phone is not included accidentally. Keep emulators running unless the user asks
otherwise. See [testing strategy](testing-strategy.md) for the full test commands.

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
