# Reconcile NWS station rows with their own METARs once per app start

User decision (2026-11-17), on `plans/261117-metar-reconcile-cadence-options.md`: "Only phone start,
emulator start, or desktop start." This is a variant of option 4 (one-time repair) that repeats on
every start, rather than option 3 (Refresh and wake).

## What "start" means on each platform

- **Android (phone, emulator):** a process start (`Application.onCreate`). The OS decides when that
  happens. Opening the app or tapping the widget doesn't start a new process while the old one is alive.
  - Process starts seen (`COLD_START_PERF`, one per process, 11-14 → 11-17 10:06): Pixel 49 (about
    16 a day), Samsung 18 (about 6 a day), emulator 1 (this morning's install).
  - So the phones repair a few times a day. The emulator kept one process for days, so all 3 of its
    holes (11-16 13:15, 11-17 00:55, 11-17 05:55) would have stayed until the next install or reboot.
- **Desktop:** app launch (login autostart, `buildStart-desktop.sh`, an upgrade). Wakes from sleep
  and source changes reuse the process (their passes log `reason=launch:…` too) and don't count.

## Proposed fix (`:shared`, both platforms)

1. `NwsGapFillRun.dailyForSources` calls `reconcileMetars` only on the first pass for a site in this
   process: an in-memory set of reconciled sites in `NwsGapFillRun`, keyed by the shared
   `LocationMatch` quantization. No reason plumbing, preference or table: a new process starts with
   an empty set, which is exactly "start".
2. A site is marked done only when the request was answered. An `unreachable` reconcile (offline at
   start) is tried again on the next pass, so a cold start without a network still repairs once.
3. A skipped pass logs `NWS_METAR_RECONCILE skipped=already_this_process` at `VERBOSE` (logcat only).
4. Unchanged: `reconcileMetars` itself, the need check, the per-station fill and the stale-history leg.

## Tests

1. `NwsGapFillRunTest`:
   - two passes in one process reconcile once;
   - a failed first request is retried on the next pass;
   - a second site reconciles separately;
   - a reset of the set (new process) reconciles again.
   - A test-only reset hook, like `StoredDataVersion`'s.
2. `DeferredObservationWindowTest` (`a wake with every day complete makes no request`): the first wake
   reconciles, so the station-lookup count goes from 1 to 2; the second wake makes no request, as now.
3. `DesktopMetarReconcileIntegrationTest` stays as it is (it calls `reconcileMetars` directly). Add one
   case through `refreshObservationWindow` twice on real SQLite: one Aviation Weather request, not two.
4. Gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`. The install starts
   a new process, so the emulator's first pass reconciles. Check: an `NWS_METAR_RECONCILE … added>0`
   line, KNUQ NWS rows for 06:15–08:35, no `DAILY_FOG_DRAWN date=2026-11-17`, and a screenshot. Then
   the same on desktop after its restart.

## Docs

`arch/forecast-and-observation-cadence.md`, gap-fill section: the reconcile runs once per process
start and site, and a complete week otherwise makes no request.
