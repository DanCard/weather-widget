# Reliable hourly prune scheduling

Evidence and reasoning: `notes/261101-hourly-rows-pruning-strategy.md`.

## Problem
The desktop snapshot prune has effectively stopped. A simulated prune would delete 32,025 of 52,360 rows
(61.2%), against 2,416 of 69,846 (3.5%) on the Pixel. It runs at the end of a refresh and needs the screen off,
but the away rule stops fetches after an hour away, so few refreshes can satisfy it. This cause comes from the
code and row counts, not a direct trace.

The stall is also hard to see: `HISTORY_PRUNE` rows expire with the 72-hour log window, and a blocked prune logs
nothing.

## Goals
1. Housekeeping runs when the device is not in use, independent of fetches.
2. A prune that cannot find a good moment still runs within a bounded time on desktop.
3. A skipped or overdue prune is visible in logs.
4. The rule and its order live in `:shared`, with platforms supplying only a `Host`.

Not in scope: the 30-day and 18-month windows, thinning by time, Android's 67-day scan, and any schema change.

## Design

**Shared schedule.** `HistoryPruneSchedule.decide` also takes whether housekeeping is allowed, and gains:
- `OVERDUE_MS = 3 days`.
- `Decision.WAITING_FOR_IDLE`: due, but the device is in use or not on power.
- Overdue overrides the screen-off condition only.

| Decision input | Android | Desktop |
|---|---|---|
| Normal | Charging and idle | AC and screen off |
| Overdue | Unchanged, logged only | AC, screen state ignored |

Android stays strict because housekeeping must never cost battery or interrupt a busy phone (user, 2026-10-09),
and it shows no starvation. A laptop on battery still waits.

No time-of-day trigger: "not in use" already covers overnight, and a clock time adds nothing.

**Shared run.** New `HistoryPruneRun` in `:shared` owns the order:
1. Age delete (18 months).
2. Snapshot prune.
3. `VACUUM` at `VACUUM_MIN_FREELIST_BYTES` or more free.
4. Logging, and recording the completed run.

`Host` supplies those storage calls. Android's `HistoryPruneWorker` and desktop's `pruneHistorySnapshotsIfDue`
become thin callers, which also removes their duplicated vacuum logic.

**Age delete moves out of the fetch path.** Remove it from `WeatherRetentionManager.cleanOldData` and
`DesktopWeatherDao.applyRetention`. It costs about 3 ms on the desktop today, so this is for tidiness and one
owner, not speed. It now waits for the prerequisite gate, which is acceptable: a row deleted a day late is harmless.

**Desktop timer.** New `launch` loop (3e) in `DaemonRuntime`, ticking every 15 minutes:
- Not gated by `presence.isAway`, because it uses no network.
- Calls the shared decision, and runs off the main thread.
- `refresh` no longer calls the prune.

**Durable last-run marker.** New `HISTORY_PRUNE_LAST` tag in `PERMANENT_LOG_MARKERS`, replaced on each completed
run so it stays one row. Without it, `last` falls to 0 once the log window passes, so "due" works only by accident
and the overdue state cannot be reported.

**Skip logging.** Log `skipped=waiting_for_idle` and `overdue=true` at most once a day. Android logs `overdue=true`
at the sync's existing check when the last completed run is over 3 days old.

## Steps
1. **Back up.** Keep a dated copy of the desktop database before the first real prune.
2. **Audit rule 2.** Check `HourlyForecastSelector` and the "newest row" readers for field merging. Rule 2 protects
   no rows of its own on either database, so remove it only if no reader needs it, with its tests updated.
   Otherwise keep it and record why.
3. **Schedule.** Extend `HistoryPruneSchedule`, tests first.
4. **Shared run.** Add `HistoryPruneRun` and both Hosts. Move the age delete.
5. **Desktop timer.** Add the loop and marker, and remove the call from `refresh`. Confirm `db.getConnection()`
   gives the prune its own connection, because it toggles `autoCommit` while refreshes can run.
6. **Docs.** Update `arch/data-pruning.md` for this behavior. The one-table rewrite stays in phase 5 of
   `plans/261101-one-hourly-forecast-table.md`.

## Tests
- `HistoryPruneSchedule`: not due, waiting, normal run, overdue on AC, overdue on battery, prerequisites pending.
- `HistoryPruneRun` with a fake Host: order, vacuum threshold, marker written only after success, failure leaves
  the marker unchanged.
- Integration, real SQLite (`DesktopHistoryPruneTest`): a prune while a refresh appends loses no new rows.
- Guard: `refresh` no longer prunes, and an away daemon still does. Update `DesktopHousekeepingGateTest`.
- Update `WeatherRepositoryTest` and `DesktopRetentionPolicyTest` for the moved age delete.
- Each new test must fail on the current code.

## Verification
1. **Desktop copy first.** Run the Kotlin prune on a copy and compare with the 20,335 rows the Python port kept. Run
   `integrity_check`.
2. **Same-values check on that copy.** Compare daily freeze, rain accuracy and prior-day values before and after.
   This also closes the "not verified" items in the phase 3 recap.
3. **Live desktop.** Rebuild, restart, lock the screen, and confirm `HISTORY_PRUNE`, the marker and any `VACUUM` in
   `app_logs`. Screenshot the graphs before and after.
4. **Overdue.** On a copy or with a short test override, confirm a prune runs with the screen on.
5. **Android.** Run the worker on the emulator with the debug hook, then confirm the first merged-table prune in
   `HISTORY_PRUNE` on a phone at its next charge.
6. **Gate.** `./scripts/staggered-tests.sh`. No Room or desktop schema change is expected.

## Risks
- **First desktop run is large:** about 32,000 deletes, by far the biggest on the merged table. Mitigated by steps
  1 and the copy run.
- **`VACUUM` needs exclusive access:** an overdue run could now happen with the screen on. It took 0.16 s for 100 MB
  on a copy, and failures are already logged and skipped, but skip it when overdue if it collides with a refresh.
- **Overdue prune while in use:** a full desktop scan touches every day of the table. Each day has its own
  transaction, so write locks stay short.
