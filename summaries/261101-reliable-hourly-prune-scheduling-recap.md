# Reliable hourly prune scheduling: recap

Plan: `plans/261101-reliable-hourly-prune-scheduling.md`. Evidence: `notes/261101-hourly-rows-pruning-strategy.md`.

## Outcome
The desktop prune is running again. Its first run cut `hourly_forecasts` from 53,116 rows to 20,334 in 383 ms.

## Cause
Desktop's prune ran at the end of a refresh and needed the screen off, but the away rule stops fetches after an hour
away. It barely ran after 2026-10-09, and 61% of the table was deletable by 2026-11-01. This cause comes from the code
and row counts, not a direct trace.

## Changes
1. `DesktopHousekeepingLoop` checks every 15 minutes, separately from refresh and the away rule.
2. `HistoryPruneRun` owns the order on both platforms. `HistoryPruneSchedule` adds the 3-day overdue rule, and a prune
   that has never run counts as overdue. Overdue ignores the screen on desktop but still needs AC. Android is unchanged
   and only logs `overdue=`.
3. The 18-month age delete on hourly rows moved out of the fetch path into the housekeeping pass.
4. Rule 2 is removed: the only code merging those fields reads rule 5's rows. The remaining rules are renumbered 1–4,
   so rule 5 is now rule 2.
5. Desktop keeps a durable `HISTORY_PRUNE_LAST` marker. Skips are logged at most once a day.
6. `arch/data-pruning.md` is updated, with a new `HistoryPruneHostContract` suite on both platforms.

## Verification
| Check | Result |
|---|---|
| Full gate | Passed: JVM tests plus 114 emulator tests; the last full JVM run was 6,535 |
| Kotlin prune on a desktop copy | 52,360 → 20,317 rows, 9,756 values compared, 0 changed |
| Kotlin prune on a Pixel copy | 69,846 → 67,210 rows, 12,983 values compared, 0 changed |
| Live desktop | 32,782 rows deleted, `integrity_check` ok, app running |
| Emulator worker (forced) | 9,666 rows deleted on the merged table, 1.1 s |
| Rule 2 test | Fails with rule 2 put back |

The values compared were stitched hourly values, prior-day bands and rain-accuracy rows, at the four busiest sites on
each copy.

## Findings
- The desktop file stays 29.6 MB: the prune freed 6.4 MB, under the 8 MB `VACUUM` threshold, and SQLite reuses that space.
- `VACUUM` never runs on Android. Its databases use `auto_vacuum=1`, so free space always reads 0. That is how it
  already behaved and it is harmless, but the Android path is effectively unused code.

## Deviations
- `installDebug` installed on the Pixel and Samsung as well as the emulator.
- The plan's same-values step did not cover daily freeze values.
- No before-and-after graph screenshots: the value comparison on copies replaced them.
- Only the rule 2 test was confirmed to fail on old code. The schedule tests cannot compile against the old signatures.

## Not verified
- The first merged-table prune on the phones, due at their next charge and idle.
- Daily freeze values, which stay open from the phase 3 recap.
- A normal screen-off prune and the logged skip on the live desktop: unit tests only, as the first run took the overdue path.

Backup: `~/.local/share/weather-widget/weather.db.bak-pre-prune-20261101`.
