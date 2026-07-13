# Resume hold-off + "waiting for network to warm up" banner

**Date:** 2026-07-13
**Status:** Implemented, unit-tested, running (uncommitted). Hardening below applied same session.

## Problem

Every resume from suspend, the desktop popup flashed a red "NWS current temp not updating"
banner. Root cause (from `app_logs`): the logind resume kick fetched immediately on wake, when
DNS is reliably dead for the first ~10s (`UnresolvedAddressException`), writing
`CURRENT_TEMP_STATUS ok=false` — which is exactly what drives the banner. The
NetworkManager-restored kick then healed everything ~14s later, so the error was always a false
alarm. Separately, fetching at the instant the link returns joins the post-wake thundering herd
of every client on the machine.

## What changed

- **`DaemonProcess.kt`** — `kickResumeRefresh` now pauses `RESUME_KICK_DELAY_MS` (15s) +
  0–10s jitter before `runLaunchRefresh` (mirrors the existing network-restored kick pause).
  Log row: `RESUME_DETECT … catch-up refresh in NNNNms`. If NM confirms connectivity during the
  pause, `kickNetworkRestoredRefresh` cancels the pending job and takes over (last-wins), so a
  confirmed-up network is never delayed by the full hold-off.
- **`Main.kt`** — when the latest `CURRENT_TEMP_STATUS` failure is offline-classified AND within
  `NETWORK_WARMUP_GRACE_MS` (90s) of the newest wake/network event, the banner renders as a calm
  blue "Waiting for network to warm up…" notice instead of the red error block. Escalates to the
  full red banner once the grace passes. Grace is anchored to the *wake event*, not the failure
  row — persistent offline writes fresh failures every cycle and must still escalate.
- **`ForecastTypes.kt` (:shared)** — extracted `isOfflineExceptionName(String)` (name-only half
  of `isOfflineException`) for the UI, which only has the class name from the log row.
- **`DesktopWeatherDao.kt`** — `getLatestWakeEventMs()` provides the cross-process wake anchor
  (daemon writes, UI reads, via `app_logs`).
- **`DesktopProcess.kt`** — new constants + pure `isNetworkWarmupWindow()`.
- **Tests** (`RefreshDelayTest.kt`): pause under debounce; grace > worst-case recovery pipeline
  (hold-off 25s + offline retries 5s/15s + NM kick pause 5s = 50s); window open/closed/clock-skew
  cases; offline name classification (`UnresolvedAddressException` etc.).

## Hardening (user review finding, same session)

`getLatestWakeEventMs()` originally selected `RESUME_DETECT`/`NETWORK_DETECT` rows filtered to
`level='INFO'` — piggybacking on diagnostic rows whose wording/levels can drift (the INFO filter
existed only to exclude the WARN "monitor stream ended" rows). Replaced with an explicit
contract: the daemon writes a dedicated **`WAKE_EVENT`** row (`reason=resume:…`,
`reason=network:restored`, `reason=startup`) at each accepted transition, and the DAO queries
that tag alone. Diagnostic rows are diagnostic again.

## Tests around the logging contract (user request, same session)

The feature rides on app_logs rows read back across the daemon/UI process boundary, but the
message formats were inline string templates at 9 write sites with independent inline parsing in
the UI — nothing pinned writer and reader together. Added:

- **`AppLogContracts.kt` (:shared)** — `WakeEventLog` and `CurrentTempStatusLog` objects own the
  tag names, encoders (`ok`/`failure`/`message`), and parsers (`isOk`,
  `parseFailureClassName`, `parseFailureDetail`). All 9 writer sites (DaemonProcess,
  DesktopWeatherRepository) and the readers (DAO SQL, Main.kt banner parse) now go through them —
  the format physically cannot drift on one side only.
- **`AppLogsContractTest.kt` (:shared)** — round-trips through a real sqlite temp DB:
  wake-event round-trip + null-on-empty; diagnostic `RESUME_DETECT`/`NETWORK_DETECT` rows
  explicitly do NOT count as wake events (pins the hardening); real
  `UnresolvedAddressException` → encode → `dao.log` → read → parse → `isOfflineExceptionName`
  end-to-end; non-offline failure keeps its detail; per-source isolation + latest-row-wins;
  source-id prefix cannot leak (`NWS_BLEND` must not satisfy a `NWS` lookup).
- **Latent bug found by writing the tests**: `getLatestCurrentTempStatus` used
  `LIKE 'source=NWS%'`, which a source id extending another's (e.g. `NWS_BLEND`) would wrongly
  match; now `LIKE 'source=NWS %'` (space-terminated). Mutation-checked: reverting the pattern
  fails the new test.

## Verification

- `:desktop:test` and `:shared:test` suites green.
- Rebuilt distributable via `scripts/buildStart-desktop.sh`; daemon + UI restarted healthy
  (startup `LAUNCH_REFRESH_CHECK action=NONE`, data fresh). Real suspend/resume not exercisable
  from the session — next actual resume is the live test (look for `catch-up refresh in NNNNms`
  and the blue notice instead of red).

## Follow-ups / notes

- Changes are uncommitted (user to decide on commit).
- The startup `WAKE_EVENT` row also gives login-autostart fetch failures the warm-up treatment.
