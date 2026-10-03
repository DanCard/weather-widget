# unit-tests.sh: report :shared/:desktop when they finish, and say when they were cached

## Problem

- `:shared:test :desktop:test` run in a background Gradle process from t=0 and finish in ~10–25 s,
  but their summary lines print only after the foreground `:app` Gradle run returns (~60 s, bound by
  the Long bucket). The live poller watches only the four `:app` buckets. Reads as if they were
  slow / on the critical path; they are not.
- Unlike the `:app` `…Fresh` buckets, `:shared:test`/`:desktop:test` are not forced. When their
  inputs are unchanged Gradle skips them (UP-TO-DATE / FROM-CACHE) and the script summarizes the
  stale XML as "N tests passed in X seconds" — e.g. the 03:59 staggered run reported the 03:21 run.

User decision (2026-10-03): cached is fine for ALL tests — default `unit-tests.sh` to cached (`--fresh` still forces the `:app` buckets); say "cached" instead of a duration.

## Change (`scripts/unit-tests.sh` only)

1. The live report poller also tracks `shared` and `desktop` (default runs only). When the background
   PID has exited, it prints both module summaries immediately and marks them reported. The
   post-run block still counts their totals/failures and still prints the Gradle log on failure, but
   skips re-printing a line already shown.
2. Module summary reads the background Gradle log: if `> Task :<module>:test` is `UP-TO-DATE` or
   `FROM-CACHE`, the line reads `N <module> tests: cached (up to date; last ran HH:MM)` instead of a
   duration.

## Verify

- Run `scripts/unit-tests.sh` twice with no source change: second run shows shared/desktop as cached,
  printed before the Long bucket line.
- Touch a `:shared` source: shared runs for real and prints a duration, still before Long.
