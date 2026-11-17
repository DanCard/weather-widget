# Desktop: Fire Due `SOURCE_RETRY` During the Source-Change Settle Without Opening the Plan Gate

Supersedes the approach in [261116-desktop-source-retry-during-settle.md](261116-desktop-source-retry-during-settle.md)
(same diagnosis; that fix ended the settle early and ran a full loop pass).

## Evidence
Desktop `app_logs` (`~/.local/share/weather-widget/weather.db`), 2026-11-16:

1. `17:29:42`–`17:31:33`: 15 `CONFIG_SAVE weatherSource` clicks (popup click-through), each restarting
   the fetch loops with `reason=source_change`.
2. `17:30:34`: `SOURCE_RETRY scheduled source=OPEN_METEO trigger=launch:source_change attempt=1/4 delayMin=1`
   (due `17:31:34`, in the middle of the second click burst `17:31:11`–`17:31:33`).
3. `17:31:42`: `SOURCE_RETRY scheduled source=OPEN_METEO trigger=request:history attempt=2/4 delayMin=2`
   (due `17:33:42`).
4. `17:36:33`: `FORECAST_LOOP_START reason=source_change endedBy=settle waitedSec=300`, then
   `SOURCE_RETRY fire source=OPEN_METEO`, almost 3 min late.
5. `17:36:37`: `FORECAST_LOOP_SLEEP`, immediately followed by `FORECAST_LOOP_WAKE reason=source_retry_armed`
   (the signal conflated in `forecastLoopWake` since `17:31:42`) and a second identical plan/sleep.

Earlier incident with the same shape: `14:45:21` retry due `14:46:21`, fired at `14:49:56` after a
300 s settle (see the superseded plan).

## Root cause
- `awaitLaunch` (`desktop/.../LaunchRefreshJobs.kt`) runs a plain `delay(settleMs)` for `source_change` /
  `location_change`. Loop 3c (`DaemonRuntime.kt`) only checks `sourceRetryState` and reads
  `forecastLoopWake` inside `while (true)`, so a retry due within the 5 min settle waits for the settle.
- The settle is intentional (`forecastLoopWaitsForLaunch` KDoc, `DesktopProcess.kt`): planning at once
  would fetch the new source beside its launch refresh, and a loop fetch begun between two clicks is
  cancelled half-billed by the next restart. Item 1 above is that click-through in practice.
- So the fix must not end the settle (which opens cadence planning for every source). It must only
  let **retries** through, and those retries must survive the next click.

## Proposed fix (Claude proposal)
1. **Extract the retry step** from loop 3c into one desktop helper, e.g.
   `fireDueSourceRetries(config, nowMs, isCharging, screenOn, cadenceDue: Set<String>): Boolean`
   (returns `fetchFailed`), containing today's two blocks unchanged in behavior:
   - displayed source: `isDue` (with `ForecastSchedule.DUE_GRACE_MS`) → `consume` → skip if cadence-due
     → `SourceRetry.shouldFire` → `refresh(reason = SourceRetry.REASON)` → publish/notify;
   - non-displayed sources: the existing per-source retry loop.
   Each retry fetch runs under `launchRefreshJobs.fetchOnce(LaunchRefreshJobs.key(...))`: a retry for the
   source/site a launch refresh is already fetching is skipped (that refresh's own success or failure
   decides the next retry, as `launch:source_change` already arms one).
   The loop's main pass calls the helper inline as before, with `cadenceDue = due.keys`.
2. **Extract the earliest-retry lookup** (`DaemonRuntime.kt` ~1500–1502) into
   `earliestSourceRetry(config, nowMs): Pair<String, Long>?`, shared by the loop sleep and the settle.
3. **Settle wait that serves retries only.** Change `awaitLaunch`'s settle branch to take optional
   `wake: ReceiveChannel<String>?`, `nextRetryAtMs: (() -> Long?)?`, `onRetryDue: (suspend () -> Unit)?`,
   `nowMs: () -> Long` (defaults keep today's behavior and existing tests):
   - `deadline = nowMs() + settleMs`; loop until `nowMs() >= deadline`:
     - `retryAt = nextRetryAtMs()`; if `retryAt != null && retryAt - DUE_GRACE_MS <= nowMs()` →
       `onRetryDue()`, then continue (re-read; the retry consumed or re-armed itself).
     - else wait `min(deadline, retryAt - DUE_GRACE_MS (if any)) - nowMs()` (floor 1 ms) on
       `withTimeoutOrNull { wake?.receive() }`. A wake only re-evaluates; it does not end the settle.
   - Still returns `"settle"` at the deadline, so the cadence plan keeps its full 5 min gate. Wakes read
     during the settle are consumed, which removes the stale `source_retry_armed` double plan.
   - `FORECAST_LOOP_START` adds `retriesDuringSettle=<n>` so the log shows what happened.
4. **Retries during the settle outlive the next source click.** `onRetryDue` launches the helper through
   a new `LaunchRefreshJobs.launchRetry(block)` in the same scope as launch refreshes, so a
   `source_change` restart does not cancel it. `start()` with any non-source reason and `cancel()`
   also cancel tracked retry jobs (the site or client no longer applies), matching
   `launchRefreshSurvivesRestart`. The awaiting settle does not join it.
   Publishing the displayed-source result is gated by `launchRefreshPublishes(fetchedConfig, currentConfig)`,
   so a retry that finishes after a click stores its data but does not paint the wrong source.
5. **Android:** no change. Retries there are WorkManager work with no settle window, and the settle is
   desktop loop lifecycle, so this stays in `:desktop`. The retry policy (`SourceRetry`) is already shared.

### Out of scope
- Detaching the loop's own (post-settle) retries from restarts. They keep today's behavior. Open
  question for the user: unify both through `launchRetry` later?

## Tests
`desktop/src/test/kotlin/com/weatherwidget/desktop/ForecastLoopStartTest.kt` (`runTest`, virtual clock):
1. A retry armed before `source_change`, due at +60 s: `onRetryDue` runs once at +60 s − grace, and
   `awaitLaunch` still returns `settle` at 300 s.
2. A retry armed at +90 s via the wake channel, due at +210 s: runs at +210 s − grace (not at 300 s);
   the wake signal is consumed (channel empty afterwards).
3. A non-retry wake (`screen_on`) during the settle neither ends it nor calls `onRetryDue`.
4. Existing: `a source change keeps the whole settle even when its refresh is done` unchanged.

`LaunchRefreshJobs` unit tests (new or existing file):
5. A `launchRetry` job survives `start(SOURCE_CYCLE_REASON)` and is cancelled by `start("location_change")`
   and by `cancel()`.
6. `fetchOnce` with a launch refresh already fetching the same key skips the retry (returns null).

Integration (2+ classes, desktop SQLite host): helper + `DesktopSourceRetryState`: a due retry for the
displayed source whose launch refresh is in flight is consumed and not fetched twice.

## Verification
1. `./scripts/unit-tests.sh` (read the per-module summary).
2. `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
3. Live desktop check: force a source timeout (e.g. Open-Meteo per-request timeout lowered in an
   isolated XDG copy), click through several sources, and confirm in `app_logs`:
   `SOURCE_RETRY fire` within ~1 min of `at=`, no loop `FETCH_PLAN` before `endedBy=settle`, no
   cancelled retry after a click, and no `FORECAST_LOOP_WAKE reason=source_retry_armed` right after the
   first sleep.

## Docs
Update `arch/hourly-forecast-update-cadence.md` (forecast loop start / settle and retry timing) in the
same change.
