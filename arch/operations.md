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
`Generic_Foldable_API36`, `Medium_Phone_API_36`, and `Pixel_Tablet_API36`. Capture screenshots, renderer-specific logcat,
widget preferences, and database state before diagnosing visual/data mismatches. Do not ask for the
location when runtime state can reveal it.

`INTERACTION_E2E queue=` starts at `onReceive`, so it cannot see a tap waiting for delivery. For a
slow tap, compare logcat's `InputReader` touch-up and `ActivityManager Received BROADCAST` lines with
`WidgetInteractionRcvr onReceive`. Repeat taps that arrive as a burst are the usual sign of a stall.

Samsung Freecess: when a Samsung widget stops advancing with the screen on, check
`adb logcat | grep -E "Freecess.*weatherwidget|due to freecess"` and
`dumpsys connectivity | grep <uid>` (`blockedReasons=c0` = app-background + OEM deny). While frozen,
alarms (wakeup and non-wakeup) are held and the uid's network is blocked; a tap, screen-on, widget
broadcast or job start unfreezes it, which is why the screen-on UI tick is a job (`UFZ … reason:`). See `plans/261108-samsung-freecess-stalls-now-dot.md`.

When an emulator widget looks wrong but the app logged a good paint, run
`scripts/capture-launcher-state.sh` before pressing HOME or restarting the launcher. It saves a
screenshot, the launcher's live view tree, and a bugreport for every running emulator under
`captures/`; use `-s SERIAL` for one target.

In-app bug reports (Settings → Submit Bug Report on both platforms) go to daniecarde55@gmail.com,
subject `Weather widget bug report: <description start> (<version>)`. The mail body is a short
summary (description, version, recent taps, device, `--- DISPLAY ---` pixels/dpi/dp/font scale,
launcher, and per widget its view, `size=WxHdp`, launcher min/max options and `header:` decision).
The evidence is the attached `weather-widget-report-<stamp>.zip` (shared `BugReportZip`):

- `summary.txt`: the body plus full `HEADER_FIT` lines and image notes.
- `logs.tsv`: every `app_logs` row of the last 2 h plus key events from the full 72 h
  (`BugReportLogs`: WARN/ERROR and `KEY_EVENT_TAGS`), oldest first, one row per line, column
  `part` = `recent`/`key`. Load it with `sqlite3 :memory: '.mode tabs' '.import logs.tsv logs'`.
- `widget-<id>.png` (Android): each widget as the app last pushed it, replayed from
  `WidgetViewsHistory` (last full RemoteViews + partials since); `window-<n>.png` on desktop.
- `weather_database.db`: a `VACUUM INTO` copy; open it directly instead of
  `backup_databases.py` for a phone that is not on adb. Android attaches it only with the
  metadata checkbox; API keys live in preferences and are never attached.

Android sends with `ACTION_SEND` + `FileProvider` (`${applicationId}.bugreports`, `cache/bug_reports/`);
desktop writes the zip to `~/Downloads`, opens the mail draft naming it, and opens the folder
(`mailto:` can't attach). Each build logs `BUG_REPORT_BUILT`. Replay a layout report on an AVD
matching the pixels and density, resized to the reported dp. Log times are device-local:
use the summary's `Timezone:` offset, not the host's `'localtime'`, when querying the attached DB.
Plan: `plans/261121-bug-report-attachments.md`.

Header buttons: every header render logs `HEADER_FIT` (width, measured pieces, plan) to logcat.
The shared `HeaderFitChangeLog` persists a line only for the first render of each widget and view
in a process and whenever its buttons change (shown/hidden, count, home, months). Android writes it
to `app_logs` at INFO (a key event, so it reaches `logs.tsv` for 72 h); desktop logs it on the same
rule. The report also lists each widget's latest decision (`header: daily 384dp buttons=SHOWN 3`,
or `not rendered since app process start`).

`adb` warnings can corrupt redirected PNG output on some setups. Convert before viewing:

```bash
$ADB -s <serial> exec-out screencap -p > /tmp/weather-screen.png
convert /tmp/weather-screen.png /tmp/weather-screen.jpg
```

Use focused instrumented runs through `scripts/emulator-tests.sh -c <fully.qualified.TestClass>` so
an attached physical phone is not included accidentally. Keep emulators running unless the user asks
otherwise. See [testing strategy](testing-strategy.md) for the full test commands.

### Slow first paint after a process start

`COLD_START_PERF` in `app_logs` (first paints of 8 s or more) splits the delay: `firstTriggerAgeMs`
is the OS delivering the broadcast, the rest is the app. `WIDGET_STARTUP_PERF` then names the slow
load step. Its `extremesMs` is the provisional stored-rows actuals read that the first paint waits
for. `liveExtremesMs` is the live blend that runs after the paint, and `liveRepainted` lists the
widgets it changed (logcat `DAILY_ACTUALS_STORED` gives the provisional split). See
[the daily view's actuals read](daily-history-extremes.md#the-daily-views-actuals-read).

Background syncs wait for the startup paint (`StartupCooldown`): a run arriving while it is pending
logs `SYNC_DEFERRED_STARTUP … cause=paint_pending`, and `cause=cooldown` when only the 6 s clock
applies. Every `SYNC_START` with `uiOnly=false` should follow `WIDGET_STARTUP_BATCH`. The hold ends
30 s after the claim even if the paint has not settled.

Sync work other than a repaint runs on a two-thread background-priority pool (`SyncDispatcher`), so
`SYNC_STAGE … thread=sync-pool-N` is expected; a `DefaultDispatcher-worker` there means the run
bypassed the pool. The repaint a sync ends with runs on the ordinary dispatcher.

A stretch where the app logs nothing at all is usually the cached-app freezer, not slow code:

```bash
$ADB -s <serial> logcat -d | grep -E "ActivityManager: (freezing|sync unfroze) .*com.weatherwidget"
```

See `performance/261115-boot-first-paint-frozen-behind-startup-syncs.md`.

### Slow source switches and desktop SQLite lock stalls

A switch that refetches is a targeted forecast sync ([cadence](forecast-and-observation-cadence.md#targeted-forecast-syncs)):
`TARGETED_SYNC stage=painted` marks the paint, and the next toggle's `SYNC_START` should follow within about a
second on Android. The observation fill and recompute come later as one `POST_SYNC_ACTUALS_RUN` /
`POST_SYNC_ACTUALS_RESULT changed= ms=` pair per burst; `POST_SYNC_ACTUALS_DEFERRED reason=settling|busy`
means it re-armed. A `recomputeActuals` inside a toggle's worker is a regression.

A source switch (API button, daily home button) logs `SOURCE_SWITCH_E2E` on both platforms:
`phase=critical … persistMs= paintMs= criticalMs=` as soon as the new source is painted, then
`phase=done … refresh=<QUEUED|FRESH|DELEGATED|SKIPPED|FAILED> deferredMs=`, also stored in `app_logs`.
A missing `phase=done` line after a `phase=critical` line means the deferred phase is stuck. Desktop
`paintMs` is ~0: Compose repaints from the saved config, while the UI serves from its in-memory
per-source snapshot cache (`DesktopSnapshotCache`, invalidated on daemon updates) or loads once from
SQLite, and the daemon checks staleness independently (`LAUNCH_REFRESH_CHECK reason=source_change`,
notifying the UI only when an actual fetch runs). Desktop therefore adds two post-critical lines:
`phase=data_ready sinceSwitchMs=` once the UI holds the new source's snapshot, and
`phase=rendered sinceSwitchMs= renderMs=` after the first Compose draw pass containing that snapshot
finishes (`DesktopSourceSwitch.onFrameRendered`). Until `data_ready`, the header updates in two parts:
the source label moves on the click, and rain, icon and the rest stay on the snapshot's own source
(`ForecastSnapshot.sourceId`). `HEADER_CONTENT` logs both on either
platform, as `source=` (the data) and `label=`. The graphs and daily columns also draw for
`ForecastSnapshot.sourceId`, so the old source's complete view stays up until the new data arrives, and
the hourly pan check waits for it too. `NAN_TEMP_INDICES` right after `CONFIG_SAVE` means something is
reading the new source's rows from the old snapshot.

Desktop blanks the graph, and the header's rain and icon, rather than keep the old source's view too
long (`DesktopSourceWait`, logged as `SOURCE_SWITCH_WAIT state=`). `LOADING` appears after 3 s, or at once
when the new source has no saved rows and its fetch is running. `NO_DATA` appears when that fetch has ended
and there are still no rows. Offline, the daemon's retries put `NO_DATA` about 20 s after the click. The
header and source button stay available throughout.

A fetch can outlive the repository it started on, so a late result reaches the popup only from the
repository still selected (`DesktopSnapshotPublish`, logged as `STALE_PUBLISH_DROPPED owner= current=
origin=`). A dropped result's rows are stored; a refresh then reloads through the selected repository. A
`SOURCE_SWITCH_WAIT state=LOADING` with `snapshot=` naming an older source and rows present means a load
bypassed this check. Compare `HEADER_CONTENT source=` with `label=`.

Desktop breadcrumbs written from code that must not wait on the database (`CURR_TEMP_RESULT`,
`CURRENT_STATUS_PUBLISH`, `CONFIG_SAVE`, refresh notices, `data_ready`, `rendered`) go through
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

### Provider timeouts (HTTP client)

Both Android and desktop use the OkHttp engine (`WeatherHttpClientDefaults` in `:shared`;
`DesktopHttpClient` evicts idle connections after 30 s). For the timeout, HTTP-fallback,
and retry numbers, see [Forecast and observation cadence](forecast-and-observation-cadence.md#transport-timeouts-ktor-retries-and-open-meteo-http-fallback).

Shared-module `Log` output goes to logcat on Android and only to the console log
(`~/.local/state/weather-widget/autostart-*.log`) on desktop, never to `app_logs`. In it:

- `W/HttpRetry: retryOnException host= path= cause=`: one transport failure that Ktor retried.
- `W/OpenMeteoApi: https failed after …ms, retrying over http in 250ms: ...`: HTTPS attempt failed over to the
  delayed `http://` retry.
- `W/OpenMeteoApi: getForecast: failed ms=` / `D/OpenMeteoApi: getForecast: Raw response length= ms=`:
  each forecast call's outcome and duration.
- `W/HTTP_PHASE_FAIL: host= scheme= remoteIp= protocol= dnsMs= connectMs= tlsMs= reqHeadersMs= ttfbMs= totalMs= reused= error=`:
  emitted by the shared OkHttp `EventListener` (`HttpPhaseEventListener` in `:shared`) only when a
  call fails or times out (zero logs on success), showing which phase stalled (`-1` = phase did not complete).
  Persisted to `app_logs` on both Android and desktop via `AppLog`. See
  [plans/261123-unify-shared-applog-and-http-telemetry.md](../plans/261123-unify-shared-applog-and-http-telemetry.md).

`app_logs` keeps the per-source outcome on both platforms: `FORECAST_FETCH` (`ok=true|false ms=…`),
`OPEN_METEO_HTTP_FALLBACK`, `HTTP_PHASE_FAIL`, `SOURCE_RETRY` (`scheduled` with `delaySec=`, `fire`,
`recovered`), plus desktop's `CURRENT_TEMP_STATUS` and `REFRESH_FAIL`. Both platforms record `api_usage_stats`
via the shared `WeatherHttpClientDefaults.installApiUsageInterceptor` (`HttpSend`), so transport timeouts
increment `errorCount` on both.

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
# The history prune now, ignoring its charging + idle wait (HISTORY_PRUNE in app_logs).
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.PRUNE \
  -n com.weatherwidget/com.weatherwidget.debug.DebugSyncReceiver
# Forwarded widget API toggle tap for interaction latency tests.
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.TOGGLE_API \
  --ei appWidgetId <id> -n com.weatherwidget/com.weatherwidget.debug.DebugSyncReceiver
# Location override for the passive background read (coordinates as strings), and its reset.
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.SET_LOCATION \
  -n com.weatherwidget/com.weatherwidget.debug.DebugLocationReceiver --es lat 37.7749 --es lon -122.4194
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.CLEAR_LOCATION \
  -n com.weatherwidget/com.weatherwidget.debug.DebugLocationReceiver
# Tint widget header/navigation touch zones for visual alignment verification (green=API, red=Settings).
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.TOUCH_ZONES \
  -n com.weatherwidget/com.weatherwidget.debug.DebugTouchZoneReceiver --ez enabled true
$ADB -s <serial> shell am broadcast -a com.weatherwidget.debug.TOUCH_ZONES \
  -n com.weatherwidget/com.weatherwidget.debug.DebugTouchZoneReceiver --ez enabled false
```

`FULL_SYNC` enqueues through `WidgetWorkScheduler.enqueueForcedSync` with KEEP, so it never cancels a
running worker. Prefer an emulator; a forced sync on a phone spends its provider quotas.

If launcher resize handles bounce back on an emulator, move the widget away from a constrained top
row/edge and retry. Verify whether `onAppWidgetOptionsChanged` fired before treating it as a renderer
bug.

### Desktop command-line hooks

The desktop analogue of the adb hooks (`DesktopCli`): each command sends its requests to the running
daemon over its fetch socket (`DaemonFetch`, `fetch-request.sock`) and exits. The daemon stores the rows
and repaints the window, so the window need not be open. Neither command starts or restarts the daemon.

```bash
BIN=desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop  # or the installed one
# Exactly Settings → Refresh data: sources not fetched in the last 10 min ("all sources fresh" otherwise).
$BIN refresh
# Every usable source, forced (billed), like Android FULL_SYNC: reason=debug_full_sync:cli.
$BIN full-sync
```

Settings and the commands build their requests in one place (`DesktopRefreshRequests`). Freshness
comes from stored fetch times only: the window's in-memory per-source refresh stamps are not visible to
the command. Each request prints one line (`FORECAST NWS ok`, `OTHER_SOURCES OPEN_METEO,SILURIAN ok`, or
`… failed: <error>`). `OTHER_SOURCES` reports `ok` even when one of its sources failed; those failures
are logged per source (`SETTINGS_REFRESH … failed`). `app_logs` gets `CLI_REFRESH cmd=… requests=… ok=…`,
and the daemon logs each request as `FETCH_REQUEST … origin=cli`.

Exit codes: `0` ok (or nothing stale), `1` a fetch failed, `2` the daemon is not running, `3` no
location configured. See `plans/261124-desktop-cli-refresh-hook.md`.

### ART VerifyError

`java.lang.VerifyError: Verifier rejected class …` means ART refused the class's dex; the app process
crashes the first time anything loads that class. To check any APK or AAB without installing it
(the emulator's installed app and widgets are untouched, so a release build can be checked beside the
debug one):

```bash
scripts/verify-apk-dex.sh app/build/outputs/apk/debug/app-debug.apk        # first running emulator (boots one if none; --no-boot to fail instead)
scripts/verify-apk-dex.sh -s emulator-5556 app/build/outputs/bundle/release/app-release.aab
# exit 0 clean, 1 rejected (methods printed), 2 setup error
```

For the app already installed on a device: `adb -s <serial> shell cmd package compile -m verify -f
com.weatherwidget`, then `logcat -d | grep "Verification error" | grep com.weatherwidget` (empty = clean).

On 2026-11-19 `CloudCoverViewHandler.updateWidget` (a ~500-line suspend function) was rejected in
every build from `b786b019e` until the fix in `11815ea3a`, on Generic_Foldable_API36 and
Medium_Phone_API_36 alike; a one-line addition had tipped the code generator. The gate runs this check
on every emulator run ([testing strategy](testing-strategy.md)); `scripts/dex-method-budget.sh` fails
the gate on methods over the size budget (`MAX_INSNS=0 MAX_REGS=0 REPORT_ONLY=1` lists them all).

Quote the device-side command as one string (`adb shell "date +'%m-%d %H:%M:%S.000'"`): unquoted, adb
re-splits it, `date` fails, and `logcat -T ""` shows only its last line, so the grep finds nothing.

## Enable or disable a forecast source without code changes

Do not change `WeatherSource`, source ordering, or defaults for a user's runtime preference.

| Platform | Store | Key |
|---|---|---|
| Android | SharedPreferences `widget_state_prefs` | `visible_sources_order` (CSV of IDs) |
| Desktop | `${XDG_CONFIG_HOME:-$HOME/.config}/weather-widget/config.json` | `settings.visibleSources` |

Display-source IDs are `NWS`, `OPEN_METEO`, `SILURIAN`, `TOMORROW_IO`, `WEATHER_API`,
`OPEN_WEATHER_MAP`, and `GOOGLE_WEATHER`. METAR is an actuals feed, not a display source.
Visual Crossing is not configurable.

Fresh-install defaults are `NWS`, `OPEN_METEO`, `SILURIAN` (`WeatherSourceOrdering.DEFAULT_VISIBLE_IDS`).
Android debug builds add `TOMORROW_IO` (`DefaultVisibleSources.forBuild`). A debug build bakes in
`GOOGLE_WEATHER_API_KEY` from the builder's gitignored `local.properties` or environment, or `""` when
neither is set; release builds always get `""`. Enabling `GOOGLE_WEATHER` appends it to the enabled list
like any other source.

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

`config.json` is written whole by several windows, so each save is reconciled against the persisted
config (`resolveDesktopConfigSave`): Settings owns `settings`, other windows may change only their own
fields, and the location writers (`location-picker`, Settings' `location-toggle`) change only `lat`,
`lon`, `label`, `recentLocations` and the toggle's `locationToggleUsedAtMs`. Toggle presses log
`LOCATION_TOGGLE from=… to=…` under app_logs tag `CONFIG` on both platforms. To trace a
setting or window state that changed unexpectedly, query `app_logs` tag `CONFIG_SAVE`:

- `source=… settings-fields-changed: field: old -> new` — a settings field really changed.
- `source=… merged-away-stale-settings: …` — a writer's stale or default settings were discarded, not
  saved.
- `source=… top-level-fields-lost: …` (WARN) — a save set a non-settings field (window bounds, zoom,
  view mode) to null, or reset three or more of them to their defaults at once. Every top-level change
  is also printed to the console as `top-level-fields-changed`.
- `CONFIG_LOAD_DIFF reader=daemon lost=…` (WARN) — the daemon re-read config.json and fields had gone
  without a UI save, e.g. another process or a hand edit. `CONFIG_LOAD_RESAVE` (console) means `load()`
  normalized and rewrote the file, and `CONFIG_LOAD_FAIL` (error) means the file could not be parsed,
  which the app treats as "no config".

See `plans/261121-desktop-location-change-freshness-and-config-clobber.md`.

## Release commands

Fastlane is installed at `~/.local/share/gem/ruby/3.3.0/bin/fastlane` on the development machine:

```bash
FASTLANE="$HOME/.local/share/gem/ruby/3.3.0/bin/fastlane"
$FASTLANE beta
$FASTLANE run upload_to_play_store track:beta track_promote_to:production version_code:<VERSION> changes_not_sent_for_review:false
```

The `build` lane (run by `internal`, `beta` and `production`) ART-verifies the release AAB with
`scripts/verify-apk-dex.sh` and fails on any rejected method. With no emulator running it boots the
next one in the rotation `staggered-tests.sh` uses (`scripts/lib/emulator-boot.sh`; boot log in
`logs/verify-apk-dex-boot-*.log`) and leaves it running. Exit 1 ("ART rejected methods") is a real
crash bug — do not upload; exit 2 is setup (boot failed, dex2oat failed). The R8 release dex differs
from debug, so it is checked on its own ([ART VerifyError](#art-verifyerror)).

Desktop APT publication is documented in [APT_REPO.md](../docs/APT_REPO.md). Release signing,
provider-key validation, and Play service-account setup remain in the build/Fastlane configuration;
do not copy secrets into documentation.
