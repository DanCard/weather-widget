# Current temp: measure the observation correction against one forecast run

## Symptom (2026-10-03, OWM displayed)

- Desktop: current temp **100.4°F** (`resolve:final display=100.37 estimate=90.67 obs=90.36
  delta=9.70 estAtObs=80.66`). Synoptic stations read 88–96°F, and NWS / Open-Meteo / Silurian
  were about 91°F.
- Emulator-5556: **110°F** (`display=109.97 estimate=91.96 obs=95.36 delta=18.00 estAtObs=77.36`).

## Root cause

`display = estimate(now) + (observed − estimate(observed at))`. `resolveStrictForecastTemperature`
picks the newest row per hour (`pickBestForecast`), so for OWM the two estimates come from different
forecast runs. OWM's free `/2.5/forecast` returns only future 3-hour slots, so once an hour has
passed its row is never refreshed:

| Hour | OWM row | Fetched |
|---|---|---|
| 13:00–16:00 | 77.7–81.1 | 10:35 run (about 10° too cool) |
| 17:00–19:00 | 94.7–90.1 | 16:42 run |

`estimate(15:47)` came only from the stale run (80.66), so the correction (+9.70) measures how wrong
that run was. `estimate(16:42)` interpolates the stale 16:00 row into the fresh 17:00 row (90.67),
which already contains the heat. Adding the two counts the error twice.

## Fix (`:shared` `CurrentTemperatureResolver.resolve`, so Android and desktop both get it)

- Take the newest run = the display source's rows within `RUN_TOLERANCE_MS` (10 min) of the newest
  `fetchedAt` in the window.
- If the rows bracketing the observation time come from an older run, compute **both** estimates
  from the newest run alone.
- Before the newest run's first slot, hold that slot flat; between slots, interpolate as usual.
- Here: both estimates ≈ 94.7, correction = obs − 94.7, so the display is about the observation
  (desktop ≈ 90.4, emulator ≈ 95.4).
- Logged on `CURR_TEMP_RESULT` as `run=newest_only runFetchedAt=…`, so the trace shows when it applied.
- No change when the observation's bracket is already from the newest run (the usual NWS case).
- Graph rows are untouched: this is only the current-temperature calculation.

## Tests

- `CurrentTemperatureResolverMixedRunTest` (`:shared`), an OWM-shaped fixture: a cool 10:35 run for
  13:00–16:00 and a hot 16:42 run from 17:00, with the observation at 15:47.
  - Assert the display is within 1°F of the observation and below 96.
  - Confirm it fails on the current code (it would show ~100).
- A same-run fixture (NWS-like): unchanged result.

## Follow-ups (not in this change)

- The emulator uses OWM's own `OPEN_WEATHER_MAP_MAIN` reading (95.36) as the observation, while the
  desktop uses Synoptic (`actualsProviderOverrides`). Check the Android OWM → Synoptic mapping.
- Desktop Synoptic readings were 55 minutes old at 16:42.

## Revision: only when the newest run is closer to the observation

The first version switched whenever the observation's bracket came from an older fetch. That broke
`TemperatureConsistencyTest` (falling trend kept below the observation). NWS refreshes each hour
while it is current, so every past hour comes from a different fetch, and those runs agree; the
rule flattened a real trend.

Added gate `prefersNewestRun`: use the newest run only when
|obs − newest-run estimate| < |obs − older-run estimate|.

| Case | Older run's miss | Newest run's miss | Newest-run mode? |
|---|---|---|---|
| Desktop OWM | 9.7 | 4.3 | yes |
| Emulator OWM | 18 | 0.7 | yes |
| NWS-style fixture | 0 | 3 | no |

`CurrentTemperatureResolverMixedRunTest` has 6 tests. Without the fix: 100.33 (desktop showed
100.37) and 106.8 (emulator case). Full unit suite: 4,472 passed. The live desktop at 22:24 shows
73.5 (the runs agree again, so the afternoon case can't be recreated live).
