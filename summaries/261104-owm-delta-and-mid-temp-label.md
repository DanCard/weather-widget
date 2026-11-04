# OWM forecast delta fix and mid temperature label (2026-11-04)

Plan: plans/261104-owm-delta-flat-hold-and-mid-temp-label.md. Not committed.

## User prompts

1. "Desktop: Hourly graph: OWM api source: +1.7 from forecast looks wrong. Seems much more than that
   based on graph. Can we get a mid temperature label on the hourly graph?"
2. "Approve. For mid temperature label, midpoint of the x axis. There is only a label on the left and
   right side. There is plenty of room for the label. If there is a collision than don't bother."
3. Asked for this summary, including a side agent's note (below).

## Evidence

1. Desktop DB, ~11:15: OPEN_WEATHER_MAP displayed 64.23, `appliedDeltaF` 1.665, observed 64.2.
2. OWM `hourly_forecasts`: 11:00 = 58.1 and 12:00 = 59.0 from the 08:27 run (the drawn line, ~58.3 at
   11:15). The newer 10:27 run only starts at 13:00 (62.6), because OWM's free forecast returns future slots only.
3. 64.225 - 1.665 = 62.56 = the 10:27 run's 13:00 slot, held flat back to the observation.
   Real gap ~ +5.8; label said +1.7.

## Root cause

`estimateFromRun` holds a run's first slot flat before it starts, and `prefersNewestRun` accepts it
when it is closer to the observation (rising morning temperatures make any later slot look closer).
The delta was measured against a value that is not on the drawn line.

## Changes

1. `CurrentTemperatureResolver.newestRunIfObservationBracketIsOlder` takes `nowMs` and returns null
   unless the newest run starts no later than the end of the hour interval `estimate(now)`
   interpolates over (`hourStart(now) + 1h`).
2. `TemperatureGraph.kt` (desktop only): mid-window temperature label at x = w/2, above the curve;
   value from the observed line, else forecast + delta after the fetch time; skipped on any collision
   or if outside the plot.
3. Tests: `CurrentTemperatureResolverMixedRunTest` gained the 2026-11-04 OWM case; the 10-03 helper
   call now passes `nowMs`.

## Side-agent note (received after the first fix landed)

> The OWM fix may undo the October overheating fix. The approved change only switches when the newest
> run covers the observation time. The 10-03 case, where the newest run started at 17:00 and the
> observation was at 15:47, no longer qualifies and would fall back to the stale-run behavior. The plan
> only promised to "re-check" 10-03; ask the main agent to check that the 10-03 test data still gives
> a sane temperature, and to fix it first if it doesn't.

Discussion and outcome: the concern was correct for the first guard written ("run must start at or
before the observation"). That guard would have given about 103° on the 10-03 data (delta +9.7 on the
stale run added to an estimate interpolating into the fresh 17:00 row). It was caught and replaced
before any test run or restart. What separates the two cases is whether the fresh run reaches the
interval `estimate(now)` interpolates over: 10-03 yes (17:00, now 16:42), 2026-11-04 no (13:00, now
11:15). The shipped guard uses that. The existing 10-03 tests (desktop 90.36 and emulator 95.36 cases)
pass unchanged in assertions.

## Verification

1. `./scripts/unit-tests.sh`: 6620 tests passed.
2. Rebuilt the desktop distributable and restarted: OWM `appliedDeltaF` went 1.665 -> 5.99.
3. Not verified: the mid label on screen (placement and collision behavior). Android not changed.

## Open items

1. Mid label is desktop only; mirror to Android if wanted.
2. Residual risk: a run starting exactly at the next hour while `now` is late in the hour still
   switches (intended, matches 10-03); a run starting two or more hours ahead never does.
