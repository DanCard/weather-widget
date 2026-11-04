# OWM forecast delta (+1.7) understates the graph gap; mid temperature label

## Evidence (desktop DB, 2026-11-04 ~11:15 local)

- `current_status`: OPEN_WEATHER_MAP display 64.23, appliedDeltaF 1.665, observed 64.2.
- OWM `hourly_forecasts` for the observation's hour: 11:00 = 58.1 and 12:00 = 59.0, both from the
  08:27 run. The newest run (10:27) only holds 13:00+ (OWM free `/2.5/forecast` returns future slots
  only), first slot 13:00 = 62.6.
- 64.225 - 1.665 = 62.56 = the 10:27 run's 13:00 slot. So `estAtObs` was that slot *held flat*
  backwards two hours, not the line the graph draws at 11:15 (~58.4). Real gap ~ +5.8, label says +1.7.

## Root cause

`CurrentTemperatureResolver.estimateFromRun` holds a run's first slot flat before it starts.
`prefersNewestRun` then accepts that flat value because it happens to sit closer to the observation
than the older run (rising morning temps make any later slot look "closer"). The delta is therefore
measured against a value that is neither on the drawn forecast line nor a real forecast for that hour.
This is the 2026-10-03 "newest_only" fix (plans/261003-current-temp-delta-same-forecast-run.md)
misfiring in the opposite direction of its original case.

## Proposed fix (shared, so desktop + Android both change)

Option A (recommended): `newestRunIfObservationBracketIsOlder` / `prefersNewestRun` only switch to the
newest run when that run actually brackets `observedAt` (first slot <= observedAt). Never extrapolate
a run backwards. Otherwise keep the per-hour estimate, so delta == obs - drawn line. Tests: the
2026-11-04 OWM fixture above (expect delta ~ +5.8 and the graph ghost line agreeing), plus the
10-03 fixture re-checked so we know what it now yields.

Option B: drop run switching entirely; delta always = obs - drawn line at obsAt. Simplest, but
re-opens the 10-03 double-count (estimate(now) interpolates into fresh rows).

## Mid temperature label (desktop hourly graph; mirror on Android hourly graph)

Needs a definition from the user: label at the temperature midway between the visible high and
low, or the temperature at the horizontal middle of the visible window? Plan to be filled after answer.
