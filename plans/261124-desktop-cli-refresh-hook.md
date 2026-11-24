# Desktop command-line refresh hook

## Why

Verifying a fix that only shows after a fetch (OWM's cut-off Sunday, 2026-11-24) needed a human to
click Settings → Refresh data: the desktop has no scriptable refresh. Android has debug adb hooks
(`arch/operations.md` § Debug-build adb hooks): `REFRESH` (exactly the Refresh tap, stale sources only)
and `FULL_SYNC` (every enabled source, forced, billed).

## Evidence (current code)

- Every storing fetch already runs in the daemon behind a Unix socket: `DaemonFetch.kt`
  (`FETCH_REQUEST_SOCKET`, one `FetchRequest` line → one `FetchResponse` line). The daemon publishes the
  result itself: `executeFetchRequest` → `publish(snapshot)` for the displayed source,
  `notifyDataUpdated()` after `OTHER_SOURCES`. So a client only sends and waits.
- Settings → Refresh data (`DesktopUiApplication`, ~line 997–1095) partitions sources with
  `desktopSettingsRefreshPartition` (shared `SettingsRefreshAllSources.partition`: not fetched or
  refreshed in 10 min), then sends `FORECAST` (displayed source, `observationWindow = true`) and
  `OTHER_SOURCES` (`sourceIds` = the rest of the partition).
- `Main.kt`: any argument other than `ui`/`--ui`/`show`/`--show` runs `runDaemon()`, whose first step
  `signalIncumbentToQuit` stops the running daemon. Today `weather-widget-desktop refresh` would
  restart the app.
- `.refresh-requested` only reloads the cache after the UI's own fetch; it is not a refresh request.

## Proposal

1. **`Main.kt`**: before the UI/daemon branch, `refresh` and `full-sync` run a client and `exitProcess`
   with its code. No daemon start, no incumbent signal, no AWT.
2. **`DesktopRefreshRequests`** (new, `:desktop`): builds the request list once for both callers:
   - `settingsRefresh(config, partition, origin)`: `FORECAST` for the displayed source when it is in
     the partition's fetch set (`observationWindow = true`), plus `OTHER_SOURCES` for the rest. This is
     exactly what Settings sends; `DesktopUiApplication` switches to it (no behaviour change).
   - `fullSync(config, origin)`: `FORECAST` for the displayed source plus `OTHER_SOURCES` with
     `sourceIds = null` (every usable source), no partition.
3. **`DesktopCli`** (new, `:desktop`): loads `DesktopConfigStore`, opens the DAO read-only for the
   partition, sends each request through the existing `DaemonFetchClient`, prints one line per request
   (`FORECAST OPEN_WEATHER_MAP ok` / `failed: <class> <message>`), and logs
   `CLI_REFRESH cmd=… requests=… ok=…` (`Log.d` → `app_logs`). Origin `cli`, reason
   `user_refresh:cli` / `debug_full_sync:cli`.
   - `refresh`: Settings semantics (stale sources only). All fresh → prints "all sources fresh", exit 0,
     no request. The UI's in-memory per-source user-refresh stamps are not visible to the CLI; only the
     stored last-fetch times decide.
   - `full-sync`: forced, every usable source (billed, like Android `FULL_SYNC`).
   - Exit codes: 0 all ok; 1 a fetch failed; 2 daemon not running (`DaemonUnavailable`); 3 no location
     configured.
4. No new socket, trigger file, or daemon code.

Not proposed: a `--source` option (Android's hooks have none; `refresh` covers the stale-source case).

## Tests

- `DesktopRefreshRequestsTest` (unit): Settings partition → the same two requests the button sent
  before the refactor; displayed source fresh → `OTHER_SOURCES` only; full sync → `sourceIds = null`.
- **Integration `DesktopCliRefreshIntegrationTest`**: a real `DaemonFetchServer` on a temp dir's Unix
  socket with a recording `execute`, a temp-file SQLite DAO with stored fetch times, and `DesktopCli`
  run against them: asserts the requests received, the printed lines and exit codes (0; a failing
  execute → 1; no server → 2; no config → 3). Never `DesktopDbPaths.defaultDbPath()`; `DesktopCli`
  takes `appDir`, DAO and config as parameters, and only `main` passes the real ones.

## Docs

`arch/operations.md`: "Desktop command-line hooks" beside the adb hooks, with both commands and exit
codes.

## Verification

`./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`, then
`~/projects/weather-widget/desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop refresh`
against the running daemon: OWM's Sunday row stored with `highTemp NULL`, `OWM_PARTIAL_DAY` logged, the
desktop column shows the green top half (screenshot). Confirm the daemon PID is unchanged afterwards.
