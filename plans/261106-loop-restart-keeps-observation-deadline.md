# Desktop loop restart keeps the observation deadline

## Problem

On desktop, switching the displayed source or saving settings postpones the next observation fetch by a
full interval (10 min with the screen on and the machine charging). If the user keeps switching, no
observation fetch runs at all.

## Evidence (2026-11-06, desktop `app_logs`)

1. The last observation fetch was at 10:30:31 (`lastObservationFetch=1793989831561`).
2. From 10:30:35 to 10:33:44 there were 10+ `CONFIG_SAVE` / `SOURCE_SWITCH_E2E` events. Each one was
   followed by `LAUNCH_REFRESH_CHECK … action=NONE` (the observation was 3 min old, so it was fresh) and
   `OBS_LOOP_PLAN … waitSec=599`, with no `wake=` suffix.
3. No `OBS_REFRESH` ran after 10:30:31. The next one was due at 10:43:44, which is 600 s after the last
   restart and 13 min after the last fetch.

## Root cause

`DaemonRuntime.startFetchLoops()` runs on every refresh-relevant config change
(`daemonFetchRestartReason`). It cancels `fetchJob` and relaunches the observation loop. That loop
seeds `lastLoopPassMs = System.currentTimeMillis()`, and the wait is
`interval − (now − max(lastObsFetch, lastLoopPassMs))`, so each restart starts a fresh full interval.

The seed assumes that "the launch refresh owns the first interval". The launch refresh only fetches
observations when they are stale. When it logs `action=NONE`, nothing covers that first interval.

## Fix (desktop only: loop glue in `DaemonRuntime`)

1. Before the observation loop plans its first pass, it waits for this restart's launch refresh
   (`launchJob?.join()`). Then `lastObsFetch` reflects any fetch the launch refresh made, and the loop
   never fetches at the same time as it.
2. Seed `lastLoopPassMs = 0L`, so the first wait counts from the last successful observation fetch.
   - Within the interval: wait only the remainder. The 10:33:44 restart would have waited 407 s, not 599 s.
   - Overdue: fetch on the first pass.
   - After each pass, `lastLoopPassMs` is still updated, so a failing fetch still retries once per
     interval and never spins.
3. Update the loop comment ("The launch refresh owns the first interval") and the observation loop
   section of `arch/hourly-forecast-update-cadence.md` if it describes the seed.

Trade-off: if observation fetches keep failing, each source switch now tries once immediately instead of
waiting. These retries are user-paced (one per deliberate toggle), and a restart cancels the previous
loop, so the retries can't pile up.

Android does not share this path. Its observation cadence runs through WorkManager, and a source switch
does not re-seed it. I'll confirm that while implementing, before closing this out.

## Tests

- Unit test (desktop, pure): extract the first-pass seed to `DesktopFetchStrategy`, so that
  `observationLoopWaitMs(10, lastFetch = now − 3 min, lastLoopPass = 0, now)` is 7 min and an overdue
  fetch is 0. The test must fail on the old seed (`lastLoopPass = now` → 10 min).
- Runtime: rebuild and restart the desktop app, toggle the source several times, and check that
  `OBS_LOOP_PLAN waitSec` counts down from the last `OBS_REFRESH` and that the fetch fires on time.

## Related

- plans/261106-screen-on-replans-observation-loop.md (same loop: wake re-plans)
