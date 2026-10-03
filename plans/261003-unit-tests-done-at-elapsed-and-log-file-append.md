# unit-tests.sh: "done at" elapsed time, and keep summary lines in the log file

## Evidence (2026-10-03)

- The user saw "26 localization tests passed in 15 seconds" appear about a minute into
  `staggered-tests.sh`. A timestamped `unit-tests.sh --fresh` run shows each line printed within
  ~1 s of its group's last test (Localization: last test 15:58:52, line at 15:58:53). The
  "N seconds" is the span of test execution from the JUnit XML. Configuration and compilation
  before the first test (2 s when cached, 30–60 s after edits) are not in it, so the line reads as
  late.
- `--log-file` (used by `staggered-tests.sh`) points Gradle at the same file with `>"$gradle_log"`,
  while `log_and_echo` appends summary lines to it. Gradle's handle keeps writing from its own
  offset and overwrites them: `logs/staggered-tests/unit-20261003-154219.log` kept only the
  `1127 long tests passed` line and the total.

## Change

1. Each live per-group line also says when the group finished, measured from the script's start:
   `26 localization tests passed in 17 seconds (done at 20s).` The time comes from the newest test
   end in the XML, so it is correct whenever the line is printed. Cached lines are unchanged.
2. Empty the log file once at startup, then have Gradle append (`>>`, `tee -a`). Summary lines now
   stay in the log, and the cached/up-to-date detection still only sees this run's task lines.

## Verification

Run `unit-tests.sh --fresh --log-file <tmp>`: the terminal shows "done at", and the log file contains
every per-group line plus the Gradle output.
